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

Or headlessly, printing totals without opening a window:

```
mvn -q -DskipTests package
java -cp target/diskinventory.jar com.kodewerk.diskinventory.ui.Main --scan /path/to/scan
```

Exit codes: `0` scan completed, `1` path missing or unreadable, `3` allocated
sizes are unavailable on this build and the reported figures are logical only.

## Test

```
mvn test
```

## Package

`bin/package.sh` builds a native installer for the current platform with
jpackage — `.dmg` on macOS, `.deb` on Linux — with a minimal jlinked runtime
(app module + JavaFX jmods) bundled, so the result needs no Java on the
target machine. Installers are unsigned for now: macOS users right-click →
Open the first time.

Pushing a `v*` tag runs `.github/workflows/release.yml`, which builds the
artifacts (mac arm64, linux x64) and attaches them to a GitHub Release.

Intel macOS and Windows builds are not maintained — PRs welcome. The
machinery is close: `bin/package.sh` handles osx-x64 already, and
`jpackage --type msi` under git-bash built successfully as of v1.0.0.

### Regenerating native-image metadata

Needed only if reflection, resources, or FFM usage changes. Requires NIK Full
and a display; the agent writes its config when the JVM exits, so quit the app
normally.

```
mvn -q -DskipTests package dependency:build-classpath -Dmdep.outputFile=target/cp.txt -Dmdep.includeScope=runtime
NIK=~/.sdkman/candidates/java/25.0.4.fx-nik
CP="target/classes:$(cat target/cp.txt)"
OUT=src/main/resources/META-INF/native-image/com.kodewerk/diskinventory

$NIK/bin/java -agentlib:native-image-agent=config-output-dir=$OUT -cp "$CP" \
    com.kodewerk.diskinventory.ui.Main            # click through every screen, then quit
$NIK/bin/java -agentlib:native-image-agent=config-merge-dir=$OUT -cp "$CP" \
    com.kodewerk.diskinventory.ui.Main --scan .
```

JavaFX internals need no entries here: NIK's own build-time feature registers
them for every platform, which is why one Linux capture is valid for the macOS
build too.
