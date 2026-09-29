# Delete and partial rescan

Date: 2026-09-29
Status: approved, not yet implemented

## Goal

Let the user free space from inside the app and see the result without a full
rescan:

- **Delete** a file or directory, to the Trash or permanently. The pie, the
  directory tree, the Largest files list and the `df` table all reflect it.
- **Partial rescan**: re-walk one directory after changes made outside the app
  and fix the totals from there up to the root.

Both must stay correct with hardlinks. Deleting one link of a file whose other
link survives frees nothing on disk, and that other link can sit anywhere in the
tree, or outside the scanned root.

## Current state

- `DirectoryNode` is immutable and holds recursive totals, so changing a subtree
  means rebuilding every ancestor.
- `DiskUsageModel.scanParent` already grafts a known subtree into a fresh walk
  of its parent. Partial rescan is the inverse.
- Hardlinks are counted once per link. A 10 MiB file hardlinked into two
  directories scans as 20 MiB while `du` reports 10M. This design fixes that as
  a side effect.

## Glossary

- **Link**: one path naming a file. A file with `nlink > 1` has several.
- **fileKey**: `BasicFileAttributes.fileKey()`, which is `(dev, ino)` on Linux
  and macOS and `null` on Windows.
- **Owner**: the one link of a shared inode whose directory is charged the
  inode's bytes. Every other link in the tree contributes 0.
- **Orphan**: a shared inode whose owner was removed from the tree while other
  links to it may remain.

## Scope

- Platforms: Linux and macOS. Windows degrades (see *Windows*).
- Out of scope: filesystem watching, undo (Trash covers it), multi-select,
  deleting from the `[files]` tooltip, and deleting the scan root or anything
  above it.

## Model

```text
model/
  FileEntry(name, size, allocated, nlink, fileKey)   fileKey kept only when nlink > 1
  ScanResult(DirectoryNode root, InodeIndex inodes)  returned by every scan operation
  InodeIndex                                         fileKey → links in tree, owner, nlink, bytes
  TreeEdit                                           immutable path-to-root rebuild
  Deleter                                            trash / permanent, reports outcome
  DiskUsageModel
    scan(root)                      → ScanResult
    scanParent(ScanResult)          → ScanResult     graft now carries the index
    reconcile(ScanResult, Path p)   → ScanResult     partial rescan of p; used by ↻ AND by delete
    freedOnDisk(ScanResult, Path p) → Freed          bytes freed vs. bytes that stay
  AllocatedSizeProbe
    stat(path) → (allocated, nlink)                  same lstat call, one extra field
```

### Counting rule

```text
visitFile(f)
  (allocated, nlink) = probe.stat(f)
  nlink == 1                     → count f
  else key = attrs.fileKey()
    inodes.owner(key) is empty   → inodes.claim(key, f); count f
    else                         → inodes.addLink(key, f); count 0
```

`InodeIndex` holds only inodes with `nlink > 1`, so it stays small on a typical
disk.

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
reference. It is used for:

- reconcile: swap in the fresh subtree, or drop the node / `FileEntry` when
  the path is gone
- ownership moves: subtract `bytes` along the old owner's path and add them
  along the new owner's path. The totals at the common ancestor don't change.

## Delete flow

```text
Delete key / menu "Move to Trash"   (Shift+Delete / menu "Delete permanently")
  guard: target is not the scan root or above it; no operation is running
  confirm dialog
    path, bytes counted here
    Trash:     "space is not freed until the Trash is emptied"
    Permanent: "frees X on disk"; if some stays, "Y stays (hardlinked elsewhere)"
               Cancel is the default button
  Deleter on a background thread (spinner, same as scans)
    Trash:     Linux  → `gio trash <path>`
               macOS  → Finder via `osascript` (one-time Automation prompt; supports Put Back)
    Permanent: Files.walkFileTree, deepest first, continues past failures
    outcome:   OK | FAILED(list of paths it could not remove)
  reconcile(result, path)                      always, whatever the outcome
  trashed and the trash dir is under the scan root → also reconcile(result, trashDir)
  refreshDf()
```

- `gio` ships with glib, which GTK3 depends on, and JavaFX on Linux already
  requires GTK3.
- **A delete is a partial rescan of the deleted path.** After a clean delete
  the path is gone, so the walk is empty and costs nothing. After a partly failed
  delete, the walk covers exactly what's left. The same hardlink re-homing runs
  either way. The parent is not rescanned: it would not add hardlink correctness,
  and it can cost as much as a full scan.
- **Freed on disk.** Just before the confirm dialog, each shared inode in the
  selection is re-`lstat`ed, so a twin link changed outside the app is
  reflected. An inode is freed only when its fresh `nlink` equals the number of
  its links inside the selection. `nlink` includes links outside the scanned
  root, so this stays correct when the tree can't see them. Unshared files are
  always freed in full.
- **Failures.** FAILED shows a dialog listing up to 25 paths. The tree still
  matches the disk, because reconcile walked what was left.
- **Trash unavailable** (no `gio`, or osascript denied): an error dialog offers
  "Delete permanently instead?". There is never a silent fallback.

Example: `/a/x/big` and `/b/y/z/big` are one 10 MiB inode with `nlink=2`, and
`/a/x/big` owns it. Deleting `/a/x/big` subtracts 10 MiB along `/a`, `/a/x` and
adds 10 MiB along `/b`, `/b/y`, `/b/y/z`. The total at `/` stays 10 MiB, and
"freed on disk" is 0.

## Reconcile (partial rescan)

One routine serves ↻, right-click → Rescan, and every delete. `p` is a
directory or a single file.

```text
reconcile(result, p)
  p == scan root  → scan(root)
  1. release  inodes.removeLinksUnder(p); remember orphans (owner was under p)
  2. walk     if p still exists: same Visitor over p, sharing the index and the counting rule
              if p is gone: nothing to walk
  3. re-home  each orphan still unowned: the smallest remaining path in the tree becomes owner
              (add bytes along its path); none left → remove from index
  4. rebuild  TreeEdit.replace(root, p, fresh or null), then apply the step-3 moves
```

- The previous `ScanResult` is never mutated. Cancelling drops the half-built
  result and needs no rollback. `InodeIndex` is copied before step 1. It holds
  only shared inodes, so copying it is cheap.
- Changes outside `p`, such as a twin link deleted elsewhere, are not seen
  until those paths are rescanned. This limit is inherent and documented. The
  confirm dialog's `nlink` refresh is the one exception.

## UI

```text
DiskInventoryApp
  state        ScanResult result; List<Path> trail (re-resolved after each edit)
  toolbar      ↻ → reconcile(current dir); Shift+↻ → full scan
  pie slice    context menu: Rescan · Move to Trash · Delete permanently
               ([files] and [n small dirs] slices get no menu)
  dirTree      same context menu; after an edit the affected parent
               LazyDirectoryItem.reload() re-lists its children
  filesList    context menu: Move to Trash · Delete permanently; Delete / Shift+Delete keys
               non-owner hardlink rows show "⛓ counted elsewhere" and 0 B
  runScan      generalized to Task<ScanResult>; delete and rescan use it, one at a time
```

- After any edit, if the current directory no longer exists, the view moves to
  its nearest existing ancestor.
- Trash menu items are shown only when a trash mechanism is available on this
  platform.

## Windows

The app is not maintained on Windows upstream (`fc9ac02`). Without adding
Windows-specific code:

- partial rescan and permanent delete work (pure Java)
- Trash items are hidden
- `fileKey()` is `null`, so hardlinks keep being counted per link

## Testing

JUnit with `@TempDir`, like `DiskUsageModelTest`, so no display is needed.
Hardlink tests are skipped on Windows, like the sparse-file test.

| Test | Asserts |
|---|---|
| `hardlinkCountedOnce` | two links to 10 MiB → total 10 MiB |
| `deleteMovesOwnershipToSurvivingLink` | the `/a` + `/b/y/z` example above |
| `deleteLastLinkRemovesBytes` | totals drop along the deleted path |
| `freedOnDiskCountsLinksOutsideRoot` | other link outside root: freed 0, tree drops |
| `freedOnDiskUsesFreshNlink` | twin removed outside the app after the scan → full size freed |
| `rescanPicksUpNewFiles` | a file added after the scan appears with correct totals |
| `reconcileOfVanishedPathDropsIt` | path removed outside the app → dropped, ownership re-homed |
| `rescanReclaimsOrphanOwnership` | owner deleted outside app, rescan re-homes ownership |
| `treeEditSharesUntouchedSubtrees` | untouched siblings are the same instances |
| `permanentDeleteReportsPartialFailure` | unreadable subdir → FAILED lists it; tree matches disk after reconcile |
| `nlinkReadByProbeMatchesJdk` | `probe.stat` nlink equals `unix:nlink` on Linux/macOS |

Manual check (not in CI): on Linux and macOS, Move to Trash a directory, see it
in the Trash, use Put Back on macOS, then rescan and confirm the totals return.
