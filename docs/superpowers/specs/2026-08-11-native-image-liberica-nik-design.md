# Native image builds with Liberica NIK Full

Date: 2026-08-11
Status: approved, not yet implemented

## Goal

Ship Disk Inventory as an ahead-of-time compiled native executable built with
Liberica Native Image Kit (NIK) Full, alongside the existing jpackage
installers. A native binary starts in milliseconds instead of seconds and needs
no bundled runtime directory.

The jpackage path (`bin/package.sh`, `.deb`, `.dmg`) stays exactly as it is. The
native build is a second, independent target.

## Glossary

- **NIK Full** — BellSoft's GraalVM distribution, "Full" variant, which bundles
  JavaFX. Version 25.0.4 is installed locally at
  `~/.sdkman/candidates/java/25.0.4.fx-nik` and ships `native-image 25.0.4`.
- **LibericaFX** — the OpenJFX build inside NIK Full. It appears as ordinary
  platform modules (`javafx.controls@25.0.4`) in the runtime image, plus static
  libraries (`libglass.a`, `libglassgtk3.a`, `libprism_es2.a`) that native-image
  links into the executable.
- **Reachability metadata** — JSON describing reflection, resources, JNI and
  foreign calls that static analysis cannot discover. Without it the code is
  absent from the image and fails at run time.
- **Downcall stub** — machine code generated at image build time for one FFM
  function descriptor. Descriptors must be registered before the build;
  unregistered downcalls throw at run time.
- **glibc floor** — the minimum glibc version a dynamically linked binary
  requires, fixed by the machine that built it.

## Architecture

Three pieces change, in decreasing size.

### 1. `ui.Main` — new entry point

Native images are built from the classpath, where launching a class that
extends `Application` directly trips JavaFX's "runtime components are missing"
check. A plain launcher class avoids that, and is also where the headless mode
lives.

```java
public static void main(String[] args) {
    if (args.length == 2 && args[0].equals("--scan")) {
        System.exit(scan(Path.of(args[1])));
    }
    Application.launch(DiskInventoryApp.class, args);
}
```

`--scan <path>` is a documented feature, not a hidden test hook. It scans
headlessly and prints the root path, logical total, allocated total and error
count. Exit codes:

- `0` — scan completed
- `1` — path missing or unreadable
- `3` — the FFM probe is unavailable, so allocated sizes silently degraded to
  logical ones

Exit code `3` is the point. A native image that loses the `lstat` downcall keeps
running and quietly reports the wrong numbers; this turns that into a build
failure. An unregistered downcall stub surfaces either when the handle is
created or when the call is made, so the check performs a real call and
`AllocatedSizeProbe.create` catches `Throwable`, not `RuntimeException` —
`MissingForeignRegistrationError` is an `Error`. It needs one new method on
`DiskUsageModel`:

```java
public static boolean allocatedSizeSupported() {
    try (AllocatedSizeProbe probe = AllocatedSizeProbe.create()) {
        return probe != null && probe.allocatedOf(Path.of("."), -1L) >= 0L;
    }
}
```

`DiskInventoryApp.main` goes away. `module-info` (module main class),
`bin/package.sh` (`--module $MODULE/...ui.Main`), the `javafx-maven-plugin`
`mainClass`, and the README run instructions all follow. The positional path
argument the GUI already accepts is untouched, so `--scan` introduces no
ambiguity.

### 2. `native` Maven profile

`org.graalvm.buildtools:native-maven-plugin` 0.10.6, goal `compile-no-fork`
bound to `package`, active only under `-Pnative` so `mvn test` and
`bin/package.sh` behave exactly as they do today.

```xml
<imageName>diskinventory</imageName>
<mainClass>com.kodewerk.diskinventory.ui.Main</mainClass>
<metadataRepository><enabled>true</enabled></metadataRepository>
<buildArgs>
  <buildArg>-H:EnableNativeAccess=ALL-UNNAMED</buildArg>
  <!-- --gc=parallel, or no GC flag at all; decided by measurement, ADR 006 -->
</buildArgs>
```

`-H:±ForeignAPISupport` is already default-enabled in NIK 25.0.4, so no FFM
flag is passed.

The profile also asserts the JavaFX coupling described in ADR 002:
`properties-maven-plugin:read-project-properties` reads
`${java.home}/lib/javafx.properties` with `keyPrefix=nik.`, then
`maven-enforcer-plugin:requireProperty` checks `nik.javafx.version` against
regex `\Q${javafx.version}\E(\+.*)?`. The prefix match is required because NIK
reports `25.0.4+1` where Maven says `25.0.4`. A missing properties file fails
the build too, which is the correct response to being run on NIK Standard.

### 3. Release workflow

Two jobs added to `.github/workflows/release.yml`, in parallel with the existing
`package` matrix. The existing `release` job already merges every uploaded
artifact, so it needs no change.

```yaml
native-linux:
  runs-on: ubuntu-latest
  container: ubuntu:22.04          # glibc 2.35 floor
  steps:
    - apt-get install -y build-essential zlib1g-dev curl git \
        libgtk-3-dev libpango1.0-dev libxtst-dev libgl-dev libasound2-dev
    - uses: actions/setup-java@v6
      with: {distribution: liberica-nik, java-version: '25', java-package: jdk+fx}
    - mvn -B -Pnative -DskipTests package
    - ./target/diskinventory --scan .        # fails the build on exit 3
    - tar czf diskinventory-$VERSION-linux-x64.tar.gz -C target diskinventory

native-macos:
  runs-on: macos-14                 # arm64, mirrors the existing matrix
  # same steps, no apt, artifact suffix osx-aarch64
```

Naming mirrors `bin/package.sh`: `<name>-<version>-<platform>.tar.gz` with
platform values `linux-x64` and `osx-aarch64`.

## Reachability metadata

Captured once, locally, with the tracing agent, then committed. Two runs, merged:

```bash
mvn -q -DskipTests package dependency:build-classpath -Dmdep.outputFile=target/cp.txt
CP=target/classes:$(cat target/cp.txt)
OUT=src/main/resources/META-INF/native-image/com.kodewerk/diskinventory

# 1. GUI, no argument, so the DirectoryChooser path is exercised too
$NIK/bin/java -agentlib:native-image-agent=config-output-dir=$OUT \
    -cp $CP com.kodewerk.diskinventory.ui.Main
# click: choose a directory, drill into a slice, up past the launch point,
# largest-files list, size-mode toggle, df panel, quit

# 2. headless scan, which is what registers the FFM downcall descriptor
$NIK/bin/java -agentlib:native-image-agent=config-merge-dir=$OUT \
    -cp $CP com.kodewerk.diskinventory.ui.Main --scan .
```

The generated JSON is reviewed for machine-local absolute paths before commit.

This config only has to describe application-level dynamic behaviour: the
`Application` subclass, `app.css`, and the foreign downcall. JavaFX internals are
registered by NIK's own build-time feature (`com.oracle.svm.hosted.javafx.`
`JavaFXFeature`, `JavaFXJNI`, `JavaFXReflection` in `svm.jar`), which covers
every platform — GTK and macOS Glass classes, the platform-factory template,
font factories, prism pipelines, the common file-dialog classes and the modena
resources. That is why a single Linux capture is valid for the macOS build.

`metadataRepository` stays enabled to pick up any published openjfx entries the
capture misses.

## Verification

- `mvn test` — unchanged, plus one assertion that `allocatedSizeSupported()` is
  true, scoped to macOS and Linux like the existing sparse-file test.
- CI, release only — `--scan` against the checkout on both platforms. Covers the
  model, the file walk, and the FFM downcall in a real native binary.
- Manual, before publishing — download the artifact, launch it, click through
  the GUI. The GUI paths of the native binary are verified by a human, by
  decision, not by CI (ADR 005).

## Risks accepted

- **Metadata rot.** UI changes can invalidate the captured config, and nothing
  catches it until someone clicks a release artifact. Accepted in exchange for
  not paying runner time on every push; regeneration is two documented commands.
- **macOS artifacts are unsigned.** The tarball triggers Gatekeeper quarantine.
  The `.dmg` is unsigned too, so this is not a regression, but the README says
  so plainly.
- **JavaFX media and web are unsupported by NIK.** Neither is used.
- **A GTK-less Linux box cannot run the binary.** GTK3, pango, cairo and X11
  stay dynamically linked; any desktop system has them.

## Decision records

### ADR 001 — Native builds coexist with jpackage
Native image does not replace `bin/package.sh`. If JavaFX-on-native breaks on a
platform, the installers still ship. Cost: two packaging paths to maintain.

### ADR 002 — JavaFX comes from the NIK image; the Maven version is asserted
Under a classpath native build, `javafx.*` resolves from NIK's platform modules
and the Central jars are shadowed. This works today only because
`javafx.version` equals LibericaFX's version. The profile asserts the match
rather than trusting it, because a mismatch means compiling against one JavaFX
and imaging another, with no visible symptom.

The NIK version itself needs the same care. NIK 25.0.1+12's bundled
`JavaFXFeature` registers `com.sun.glass.ui.gtk.GtkView.notifyInputMethodDraw`,
a method no current OpenJFX has — it is now `notifyInputMethodLinux` — so every
JavaFX image build fails with `NoSuchMethodException` regardless of
`javafx.version` ([bell-sw/LibericaNIK#34](https://github.com/bell-sw/LibericaNIK/issues/34)).
Fixed upstream; the project builds on NIK 25.0.4, with `javafx.version` moved to
25.0.4 to match its LibericaFX. `bin/package.sh`'s `JFX_VERSION` tracks the same
value so the jpackage path links the jmods it compiled against.

### ADR 003 — No static or musl builds, ever
`Linker.defaultLookup()` is unsupported in static executables, and that is
exactly how `AllocatedSizeProbe` finds `lstat`. A static build would silently
reduce the app to logical sizes only.

### ADR 004 — The Linux binary is built in an `ubuntu:22.04` container
Building on `ubuntu-latest` stamps a glibc 2.39 requirement into the binary,
locking out Ubuntu 22.04 (supported to 2027) and Debian 12 (2028). The container
lowers the floor to 2.35 for the price of one `container:` line and an apt list
the job needs anyway. The `.deb` job stays on `ubuntu-latest`; the two are
independent.

### ADR 005 — Release-only CI, with a manual GUI gate
No push or PR builds, no xvfb smoke test, no `--selftest-ui` mode. Metadata is
captured once and expected to stay valid. `--scan` in CI covers the model and
FFM; a human clicking the artifact covers the GUI before publishing.

### ADR 006 — GC chosen by measurement
`scan` builds an immutable tree in one allocation-heavy pass. Native image
defaults to serial GC while the JVM build has been using G1, so the native
binary is not automatically faster. NIK also offers a parallel GC. During
implementation both variants are built and timed with `--scan` on a large tree;
the winner is pinned as a buildArg with a comment recording the numbers. Ties go
to serial, which needs no flag. Heap stays at the native default of 80% of
physical memory — more generous than the JVM's 25%, which suits a tool whose
whole job is large scans.

### ADR 007 — Metadata lives in `src/main/resources`
`META-INF/native-image/com.kodewerk/diskinventory/` is auto-detected off the
classpath, so the build needs no configuration flag. The cost is a few KB of
JSON riding along in the jar and therefore in every `.deb` and `.dmg`, which
never read it.

### ADR 008 — Bare tarballs, no native installers
`.tar.gz` per platform, attached to the release next to the `.dmg` and `.deb`.
jpackage cannot wrap a native binary, and hand-rolling `dpkg-deb`/`hdiutil` would
duplicate what the jpackage path already delivers. Revisit if anyone asks for a
menu entry on the native build.

### ADR 009 — `--scan` is a public feature
Documented in the README with stable output and defined exit codes, rather than
an undocumented `--selftest`. It costs the same either way, and a documented
flag is likelier to survive future refactoring than a hidden one.
