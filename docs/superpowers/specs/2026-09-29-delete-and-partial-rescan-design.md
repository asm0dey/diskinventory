# Delete and partial rescan

Date: 2026-09-29
Status: approved, not yet implemented

## Goal

Let the user free space from inside the app and see the result without a full
rescan:

- **Delete** a file or directory, to the Trash or permanently. The pie, the
  directory tree, the Largest files list and the `df` table all reflect it.
- **Rescan** one path after changes made outside the app, fixing the totals
  from there up to the scan root.

Both must stay correct with hardlinks. Deleting one link of a file whose other
link survives frees nothing on disk, and that other link can sit anywhere in the
tree, or outside the scan root.

## Current state

- `DirectoryNode` is immutable and holds recursive totals, so changing a subtree
  means rebuilding every ancestor.
- `DiskUsageModel.scanParent` already grafts a known subtree into a fresh walk
  of its parent. A rescan is the inverse.
- Hardlinks are counted once per link. A 10 MiB file hardlinked into two
  directories scans as 20 MiB while `du` reports 10M. This design fixes that.

## Glossary

- **Scan root**: the directory the scan started from.
- **Rescan**: re-reading one path (directory or file) from disk and folding it
  into the existing tree. A full rescan is a rescan of the scan root.
- **Link**: one path naming a file. A file with several links is a **shared
  file**.
- **Owner**: the one link of a shared file whose directory is charged the file's
  bytes: the lexicographically smallest of its paths in the tree
  (`Path.compareTo`). The other links add nothing to directory totals.
- **Shared bytes** of a directory: bytes of links under it whose owner is
  elsewhere.
- **Freed on disk**: the allocated bytes a delete returns to the filesystem.
- **fileKey**: `BasicFileAttributes.fileKey()`, which is `(dev, ino)` on Linux
  and macOS and `null` on Windows.

## Scope

- Platforms: Linux and macOS. Windows degrades (see *Windows*).
- Out of scope: filesystem watching, undo (Trash covers it), multi-select,
  deleting from the `[files]` tooltip, deleting the scan root or anything above
  it, and directory hardlinks (HFS+ Time Machine backups; APFS uses snapshots).

## Model

```text
model/
  FileEntry(name, size, allocated, nlink, fileKey)   fileKey kept only when nlink > 1
  ScanResult(DirectoryNode root, InodeIndex inodes)  returned by every operation
  InodeIndex                                         fileKey → links in tree, owner, nlink, sizes
  TreeEdit                                           immutable path-to-root rebuild
  Deleter                                            trash / permanent, reports outcome
  DiskUsageModel
    scan(root)                          → ScanResult
    scanParent(ScanResult)              → ScanResult   graft carries the index
    rescan(ScanResult, Path p)          → ScanResult   used by ↻ AND by every delete
    freedOnDisk(ScanResult, Set<Path>)  → Freed        allocated bytes freed vs. staying
  AllocatedSizeProbe
    stat(path) → (allocated, nlink)                    same lstat call, one extra field
```

### Ownership

Totals depend only on what is on disk, never on walk order or on the history of
rescans:

```text
visitFile(f)
  (allocated, nlink) = probe.stat(f)
  nlink == 1  → count f in its directory
  else        → inodes.addLink(attrs.fileKey(), f); count 0 for now

settleOwners(result, affected inodes)      after scan, scanParent, rescan
  for each inode
    owner' = smallest link in the tree
    owner' != owner → uncharge owner's directory, charge owner''s directory
    no links left   → remove from index
  apply all charges as per-directory deltas in one TreeEdit pass
```

`InodeIndex` holds only files with `nlink > 1`, so it stays small on a typical
disk. A charge is part of the owning directory's direct size, so replacing or
dropping a subtree removes its charges along with it.

Going Up past the scan root can move ownership into a new sibling with a
smaller path. The known subtree's total then drops without any disk change.
This is expected.

### nlink source

`AllocatedSizeProbe` already calls `lstat` for every file, and it reads one more
field. The `st_nlink` offset and width vary by platform:

| Platform | Offset | Width |
|---|---|---|
| linux x86_64 | 16 | u64 |
| linux aarch64 | 20 | u32 |
| darwin 64-bit | 6 | u16 |

When the probe is unavailable, use `Files.getAttribute(f, "unix:nlink",
NOFOLLOW_LINKS)`. That costs one extra stat per file, but only on the fallback
path.

### TreeEdit

`TreeEdit.replace(root, P, subtreeOrNull)` rebuilds only the nodes from `root`
down to `P`, recomputing their totals. Every other subtree is shared by
reference. `TreeEdit.charge(root, deltas)` applies per-directory size deltas the
same way, rebuilding each affected path once.

## Rescan

One routine serves ↻, right-click → Rescan, and every delete. `p` is a
directory or a single file.

```text
rescan(result, p)
  p == scan root  → scan(root)
  1. release  inodes.removeLinksUnder(p); remember those inodes as affected
  2. walk     if p still exists: same Visitor over p, sharing the index
              if p is gone: nothing to walk
  3. rebuild  TreeEdit.replace(root, p, fresh or null)
  4. settle   settleOwners(affected ∪ inodes with links found in step 2)
```

- The previous `ScanResult` is never mutated. Cancelling drops the half-built
  result, and nothing needs rolling back. `InodeIndex` is copied before step 1.
  It holds only shared files, so copying it is cheap.
- Changes outside `p`, such as a twin link deleted elsewhere, are not seen until
  those paths are rescanned. The delete dialog's `nlink` refresh is the one
  exception.

## Delete flow

```text
Delete key / menu "Move to Trash"   (Shift+Delete / menu "Delete permanently")
  guard: target is not the scan root or above it; no operation is running
  confirm dialog
    path, bytes counted here
    Trash:     "space is not freed until the Trash is emptied"
    Permanent: "frees X on disk"; if some stays, "Y stays (linked elsewhere)"
    shared file with other links in the tree → extra button "Delete all N links in the tree"
    Cancel is the default button
  Deleter on a background thread (spinner; its button reads "Stop")
    Trash:     Linux  → `gio trash <path>`
               macOS  → Finder via `osascript` (one-time Automation prompt; supports Put Back)
    Permanent: Files.walkFileTree, deepest first, continues past failures,
               checks for Stop between files
    outcome:   OK | FAILED(paths it could not remove) | STOPPED
  rescan(result, each deleted path)            always, whatever the outcome
  trashed and the trash dir is under the scan root → also rescan(result, trashDir)
  refreshDf()
```

- **A delete triggers a rescan of the deleted path.** After a clean delete the
  path is gone, so the walk is empty and costs nothing. After a failed or
  stopped delete, the walk covers exactly what's left. The parent is not
  rescanned: it would not improve hardlink correctness, and it can cost as much
  as a full scan.
- **Freed on disk** always uses allocated size, whatever the Allocated/Logical
  toggle says. Just before the dialog, each shared file in the selection is
  re-`lstat`ed. A shared file is freed only when its fresh `nlink` equals the
  number of its links in the selection. `nlink` counts links outside the scan
  root too, so a twin the tree can't see still keeps the space in use.
- **Delete all N links** deletes every link of that file known to the tree. If
  a link exists outside the scan root, the dialog still says it frees 0.
- **FAILED** shows a dialog listing up to 25 paths. The tree still matches the
  disk, because the rescan walked what was left.
- **Trash unavailable** (no `gio`, or osascript denied): an error dialog offers
  "Delete permanently instead?". There is never a silent fallback. `gio` ships
  with glib, which GTK3 depends on, and JavaFX on Linux already requires GTK3.
  Trash is a single call and can't be stopped.

Example: `/a/x/big` and `/b/y/z/big` are one 10 MiB file with `nlink=2`, and
`/a/x/big` owns it. Deleting `/a/x/big` uncharges `/a/x` and charges `/b/y/z`.
The total at `/` stays 10 MiB, and freed on disk is 0.

## UI

```text
DiskInventoryApp
  state        ScanResult result; List<Path> trail (re-resolved after each edit)
  toolbar      ↻ → rescan(current dir); Shift+↻ → full rescan
  pie slice    context menu: Rescan · Move to Trash · Delete permanently
               ([files] and [n small dirs] slices get no menu)
               tooltip adds "X shared with other directories" when shared bytes > 0
  dirTree      same context menu; after an edit the affected parent
               LazyDirectoryItem.reload() re-lists its children
  filesList    one row per file, not per link: owner path at full size, "⛓ +N links",
               tooltip lists the other links
               context menu: Move to Trash · Delete permanently; Delete / Shift+Delete keys
  runScan      generalized to Task<ScanResult>; delete and rescan use it, one at a time
```

- After any edit, if the current directory no longer exists, the view moves to
  its nearest existing ancestor.
- Trash menu items are shown only when a trash mechanism is available on this
  platform.
- A directory made only of links owned elsewhere (for example `node_modules`
  when scanning a home that also holds the pnpm store) totals near zero. The
  pie shows what deleting it would free, and the tooltip explains the rest.

## Windows

The app is not maintained on Windows upstream (`fc9ac02`). Without adding
Windows-specific code:

- rescan and permanent delete work (pure Java)
- Trash items are hidden
- `fileKey()` is `null`, so hardlinks keep being counted per link

## Testing

JUnit with `@TempDir`, like `DiskUsageModelTest`, so no display is needed.
Hardlink tests are skipped on Windows, like the sparse-file test.

| Test | Asserts |
|---|---|
| `hardlinkCountedOnce` | two links to 10 MiB → total 10 MiB |
| `ownerIsSmallestPathRegardlessOfWalkOrder` | same tree, owner and totals identical across full rescans |
| `deleteMovesOwnershipToSurvivingLink` | the `/a` + `/b/y/z` example above |
| `deleteLastLinkRemovesBytes` | totals drop along the deleted path |
| `deleteAllLinksFreesTheFile` | both links deleted → freed on disk = allocated size |
| `freedOnDiskCountsLinksOutsideRoot` | other link outside root: freed 0, tree drops |
| `freedOnDiskUsesFreshNlink` | twin removed outside the app after the scan → full size freed |
| `rescanPicksUpNewFiles` | a file added after the scan appears with correct totals |
| `rescanOfVanishedPathDropsIt` | path removed outside the app → dropped, ownership re-settled |
| `rescanAddingSmallerLinkMovesOwnership` | new link with smaller path → it becomes owner |
| `scanParentResettlesOwnership` | Up past root with a smaller sibling link → owner moves |
| `largestFilesListsSharedFileOnce` | two links to the top file → one row, 1 other link |
| `treeEditSharesUntouchedSubtrees` | untouched siblings are the same instances |
| `permanentDeleteReportsPartialFailure` | unreadable subdir → FAILED lists it; tree matches disk |
| `permanentDeleteStops` | interrupted mid-walk → STOPPED; rescan matches what's left |
| `nlinkReadByProbeMatchesJdk` | `probe.stat` nlink equals `unix:nlink` on Linux/macOS |

Manual check (not in CI): on Linux and macOS, Move to Trash a directory, see it
in the Trash, use Put Back on macOS, then rescan and confirm the totals return.
