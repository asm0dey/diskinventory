# Disk Inventory

Disk usage explorer. Scans a directory tree once, then lets you drill into the
usage breakdown with a clickable pie chart — one slice per subdirectory, plus a
`[files]` slice for files directly at that level.

## Design

- `com.kodewerk.diskinventory.model` — headless API. `DiskUsageModel.scan(Path)`
  walks the tree in a single pass (symlinks neither followed nor counted,
  unreadable entries counted and skipped) and returns an immutable
  `DirectoryNode` tree. Both sizes are tracked per file and per directory:
  **logical** (`Files.size()`, apparent) and **allocated** (`st_blocks * 512`
  via an FFM `lstat` downcall, what `du` reports — sparse files like
  `Docker.raw` show their true footprint). Fully testable without a display.
- `com.kodewerk.diskinventory.ui` — thin JavaFX wrapper. Clicking a slice
  navigates the already-built tree; no rescanning on drill-down. Breadcrumb and
  **Up** navigate back — Up keeps working past the launch point (the known
  subtree is grafted, only new siblings are walked) until `/`. **Rescan**
  re-walks the disk. An **Allocated / Logical** toggle switches every number in
  the view instantly (no rescan). Slices under 1.5% are grouped into one
  `[n small dirs]` slice (hover for names); hovering `[files]` lists that
  directory's files largest-first. During a scan: spinner, live dir/byte
  counts, and an **Analyze this path** button that stops the scan and re-roots
  the analysis at the directory being scanned. The bottom panel shows `df -h`
  for the current root as a table. A **Largest files** panel on the right lists
  the top 50 files under the current directory (recursive), biggest first. A
  directory tree of the whole disk sits in the left gutter, kept in sync with
  the pie: click a node to refocus, navigate the pie and the branch opens.

## Run

```
mvn javafx:run -Djavafx.args=/path/to/scan
```

or without an argument to get a directory chooser:

```
mvn javafx:run
```

## Test

```
mvn test
```

## Package

`bin/package.sh` builds a native installer for the current platform with
jpackage — `.dmg` on macOS, `.deb` on Linux, `.msi` on Windows — with a
minimal jlinked runtime (app module + JavaFX jmods) bundled, so the result
needs no Java on the target machine. Installers are unsigned for now: macOS
users right-click → Open the first time; Windows users click through
SmartScreen.

Pushing a `v*` tag runs `.github/workflows/release.yml`, which builds all
four artifacts (mac arm64 + x64, linux x64, windows x64) and attaches them to
a GitHub Release.
