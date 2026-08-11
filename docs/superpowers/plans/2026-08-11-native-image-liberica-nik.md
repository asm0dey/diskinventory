# Native Image with Liberica NIK Full — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship Disk Inventory as an ahead-of-time compiled native executable built with Liberica NIK Full, alongside the existing jpackage installers.

**Architecture:** A new `ui.Main` launcher class provides both the classpath-safe JavaFX entry point that native images need and a headless `--scan` mode used as a build-time canary. A Maven `native` profile drives `native-image` via `native-maven-plugin`; reachability metadata is captured once with the tracing agent and committed. Two release-only CI jobs produce per-platform tarballs.

**Tech Stack:** Java 25, JavaFX 25.0.4, Maven, Liberica NIK Full 25.0.4 (`native-image 25.0.4`), GraalVM `native-maven-plugin` 0.10.6, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-08-11-native-image-liberica-nik-design.md`

## Global Constraints

- Local NIK Full install: `~/.sdkman/candidates/java/25.0.4.fx-nik`. Every native build and every tracing-agent run uses that as `JAVA_HOME`. Referred to below as `$NIK`.
- `javafx.version` in `pom.xml` is `25.0.4`; NIK reports `25.0.4+1` in `$NIK/lib/javafx.properties`. Comparisons are prefix matches, never string equality.
- Never build with `--static` or musl. `Linker.defaultLookup()` is unsupported in static executables and `AllocatedSizeProbe` depends on it.
- Reachability metadata lives in `src/main/resources/META-INF/native-image/com.kodewerk/diskinventory/`.
- `-H:±ForeignAPISupport` is enabled by default in NIK 25.0.4; do not pass it. Native access is granted with `-H:EnableNativeAccess=ALL-UNNAMED`.
- Platform names in artifact filenames match `bin/package.sh`: `linux-x64`, `osx-aarch64`.
- The Linux native job builds inside `container: ubuntu:22.04` (glibc 2.35 floor). The existing `.deb`/`.dmg` jobs are untouched.
- Native CI runs on tags only. No push/PR builds, no xvfb, no UI self-test mode.
- The default build (`mvn test`, `mvn package`, `bin/package.sh`) must behave exactly as it does today when `-Pnative` is absent.

---

### Task 1: `ui.Main` entry point and headless `--scan`

**Files:**
- Create: `src/main/java/com/kodewerk/diskinventory/ui/Main.java`
- Create: `src/test/java/com/kodewerk/diskinventory/ui/MainTest.java`
- Modify: `src/main/java/com/kodewerk/diskinventory/model/DiskUsageModel.java` (add `allocatedSizeSupported()`)
- Modify: `src/main/java/com/kodewerk/diskinventory/ui/DiskInventoryApp.java:700-702` (delete `main`)
- Modify: `pom.xml` (javafx-maven-plugin `mainClass`)
- Modify: `bin/package.sh` (`MAIN_CLASS`)
- Modify: `README.md` (Run section)
- Test: `src/test/java/com/kodewerk/diskinventory/model/DiskUsageModelTest.java` (add one test)

**Interfaces:**
- Consumes: `DiskUsageModel.scan(Path)`, `DirectoryNode.totalSize(SizeMode)`, `DirectoryNode.errorCount()`, `Sizes.human(long)`, package-private `AllocatedSizeProbe.create()` and `AllocatedSizeProbe.allocatedOf(Path, long)` — all already exist.
- Produces:
  - `public static boolean com.kodewerk.diskinventory.model.DiskUsageModel.allocatedSizeSupported()`
  - `public static void com.kodewerk.diskinventory.ui.Main.main(String[])`
  - package-private `static int com.kodewerk.diskinventory.ui.Main.scan(Path)` returning the process exit code: `0` ok, `1` unreadable path, `3` FFM probe dead.

- [ ] **Step 1: Write the failing test for `allocatedSizeSupported()`**

Append to `src/test/java/com/kodewerk/diskinventory/model/DiskUsageModelTest.java`, before the closing brace:

```java
    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})    // Windows has no probe
    void allocatedSizesAreSupportedOnMacAndLinux() {
        assertTrue(DiskUsageModel.allocatedSizeSupported());
    }
```

`@Test`, `@EnabledOnOs`, `OS` and `assertTrue` are already imported in that file.

- [ ] **Step 2: Run it and watch it fail to compile**

```bash
mvn -q test -Dtest=DiskUsageModelTest
```

Expected: compilation error, `cannot find symbol: method allocatedSizeSupported()`.

- [ ] **Step 3: Implement `allocatedSizeSupported()`**

Add to `src/main/java/com/kodewerk/diskinventory/model/DiskUsageModel.java`, after the `scan(Path)` overloads:

```java
    /**
     * Whether allocated (on-disk) sizes are real on this platform and build.
     * Performs an actual lstat downcall rather than only constructing the
     * probe: a native image built without a registered downcall stub still
     * constructs a probe successfully, and only fails when the call is made.
     */
    public static boolean allocatedSizeSupported() {
        try (AllocatedSizeProbe probe = AllocatedSizeProbe.create()) {
            return probe != null && probe.allocatedOf(Path.of("."), -1L) >= 0L;
        }
    }
```

`Path` is already imported. Try-with-resources on a `null` resource is legal and skips `close()`.

- [ ] **Step 4: Run the test and watch it pass**

```bash
mvn -q test -Dtest=DiskUsageModelTest
```

Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 5: Write the failing tests for `Main.scan`**

Create `src/test/java/com/kodewerk/diskinventory/ui/MainTest.java`:

```java
package com.kodewerk.diskinventory.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {

    @Test
    void scanOfARealDirectoryExitsZero(@TempDir Path root) throws IOException {
        Files.write(root.resolve("a.bin"), new byte[4096]);

        assertEquals(0, Main.scan(root));
    }

    @Test
    void scanOfAMissingPathExitsOne(@TempDir Path root) {
        assertEquals(1, Main.scan(root.resolve("does-not-exist")));
    }

    @Test
    void scanPrintsRootLogicalAllocatedAndErrors(@TempDir Path root) throws IOException {
        Files.write(root.resolve("a.bin"), new byte[4096]);

        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            Main.scan(root);
        } finally {
            System.setOut(original);
        }

        String output = captured.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("root"), output);
        assertTrue(output.contains("logical"), output);
        assertTrue(output.contains("allocated"), output);
        assertTrue(output.contains("errors"), output);
    }
}
```

- [ ] **Step 6: Run them and watch them fail**

```bash
mvn -q test -Dtest=MainTest
```

Expected: compilation error, `cannot find symbol: class Main`.

- [ ] **Step 7: Write `Main`**

Create `src/main/java/com/kodewerk/diskinventory/ui/Main.java`:

```java
package com.kodewerk.diskinventory.ui;

import com.kodewerk.diskinventory.model.DirectoryNode;
import com.kodewerk.diskinventory.model.DiskUsageModel;
import com.kodewerk.diskinventory.model.SizeMode;
import com.kodewerk.diskinventory.model.Sizes;
import javafx.application.Application;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Entry point, kept separate from {@link DiskInventoryApp} because a native
 * image is launched from the classpath, where JavaFX refuses to start an
 * Application subclass directly ("JavaFX runtime components are missing").
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        if (args.length == 2 && args[0].equals("--scan")) {
            System.exit(scan(Path.of(args[1])));
        }
        Application.launch(DiskInventoryApp.class, args);
    }

    /**
     * Headless scan. Prints totals and returns the process exit code: 0 on
     * success, 1 if the path cannot be scanned, 3 if allocated sizes silently
     * degraded to logical ones because the FFM downcall is unavailable.
     */
    static int scan(Path root) {
        boolean allocatedWorks = DiskUsageModel.allocatedSizeSupported();
        DirectoryNode tree;
        try {
            tree = new DiskUsageModel().scan(root.toAbsolutePath().normalize());
        } catch (IOException e) {
            System.err.println("scan failed: " + e.getMessage());
            return 1;
        }
        System.out.println("root      " + tree.path());
        System.out.println("logical   " + Sizes.human(tree.totalSize(SizeMode.LOGICAL)));
        System.out.println("allocated " + Sizes.human(tree.totalSize(SizeMode.ALLOCATED)));
        System.out.println("errors    " + tree.errorCount());
        if (!allocatedWorks) {
            System.err.println("allocated sizes unavailable: the lstat downcall did not work");
            return 3;
        }
        return 0;
    }
}
```

- [ ] **Step 8: Run the tests and watch them pass**

```bash
mvn -q test
```

Expected: BUILD SUCCESS, `MainTest` and `DiskUsageModelTest` all green.

- [ ] **Step 9: Move the entry point over**

Delete these three lines from `src/main/java/com/kodewerk/diskinventory/ui/DiskInventoryApp.java` (currently at 700-702, immediately before the final closing brace), plus the blank line above them:

```java
    public static void main(String[] args) {
        launch(args);
    }
```

In `pom.xml`, in the `javafx-maven-plugin` configuration, change:

```xml
                    <mainClass>com.kodewerk.diskinventory.ui.DiskInventoryApp</mainClass>
```

to:

```xml
                    <mainClass>com.kodewerk.diskinventory.ui.Main</mainClass>
```

In `bin/package.sh`, change:

```bash
MAIN_CLASS=com.kodewerk.diskinventory.ui.DiskInventoryApp
```

to:

```bash
MAIN_CLASS=com.kodewerk.diskinventory.ui.Main
```

`src/main/java/module-info.java` needs no change: a module's main class does not have to be exported, and `com.kodewerk.diskinventory.ui` is already exported to `javafx.graphics` so JavaFX can instantiate `DiskInventoryApp` reflectively.

- [ ] **Step 10: Verify the GUI still launches from Maven**

```bash
mvn -q javafx:run -Djavafx.args=/usr/share/doc
```

Expected: the window opens showing a pie chart of `/usr/share/doc`. Close it.

- [ ] **Step 11: Verify `--scan` on the JVM**

```bash
mvn -q -DskipTests package
java --enable-native-access=ALL-UNNAMED,javafx.graphics \
    -cp "target/diskinventory.jar:$(ls ~/.m2/repository/org/openjfx/javafx-*/25.0.4/*.jar | tr '\n' ':')" \
    com.kodewerk.diskinventory.ui.Main --scan .
echo "exit=$?"
```

Expected: four lines (`root`, `logical`, `allocated`, `errors`) and `exit=0`. The `allocated` figure should differ from `logical` on a normal filesystem.

- [ ] **Step 12: Document `--scan` in the README**

In `README.md`, in the `## Run` section, after the directory-chooser example, add:

````markdown
Or headlessly, printing totals without opening a window:

```
mvn -q -DskipTests package
java -cp target/diskinventory.jar com.kodewerk.diskinventory.ui.Main --scan /path/to/scan
```

Exit codes: `0` scan completed, `1` path missing or unreadable, `3` allocated
sizes are unavailable on this build and the reported figures are logical only.
```

- [ ] **Step 13: Commit**

```bash
git add src/main/java/com/kodewerk/diskinventory/ui/Main.java \
        src/test/java/com/kodewerk/diskinventory/ui/MainTest.java \
        src/main/java/com/kodewerk/diskinventory/model/DiskUsageModel.java \
        src/test/java/com/kodewerk/diskinventory/model/DiskUsageModelTest.java \
        src/main/java/com/kodewerk/diskinventory/ui/DiskInventoryApp.java \
        pom.xml bin/package.sh README.md
git commit -m "feat: add ui.Main launcher with a headless --scan mode

Native images launch from the classpath, where JavaFX refuses to start an
Application subclass directly, so the entry point moves to a plain class.
--scan doubles as the canary for the FFM lstat downcall: allocatedSizeSupported
performs a real call, because a native image with an unregistered downcall stub
constructs the probe fine and only fails at invocation time."
```

---

### Task 2: `native` Maven profile with the JavaFX version assertion

**Files:**
- Modify: `pom.xml` (add `<profiles>` section)

**Interfaces:**
- Consumes: `com.kodewerk.diskinventory.ui.Main` from Task 1.
- Produces: `mvn -Pnative package` emits the executable `target/diskinventory`.

- [ ] **Step 1: Add the profile**

In `pom.xml`, after the closing `</build>` tag and before `</project>`, add:

```xml
    <profiles>
        <!--
            Native image via Liberica NIK Full. Run with JAVA_HOME pointing at a
            NIK Full install; JavaFX comes from NIK's own platform modules, not
            from the Maven jars, which are shadowed on the classpath. The two
            must agree, so the build asserts it rather than hoping.
        -->
        <profile>
            <id>native</id>
            <build>
                <plugins>
                    <plugin>
                        <groupId>org.codehaus.mojo</groupId>
                        <artifactId>properties-maven-plugin</artifactId>
                        <version>1.2.1</version>
                        <executions>
                            <execution>
                                <id>read-nik-javafx-version</id>
                                <phase>validate</phase>
                                <goals>
                                    <goal>read-project-properties</goal>
                                </goals>
                                <configuration>
                                    <!-- Absent on NIK Standard, which is exactly when this build must fail. -->
                                    <files>
                                        <file>${java.home}/lib/javafx.properties</file>
                                    </files>
                                    <keyPrefix>nik.</keyPrefix>
                                </configuration>
                            </execution>
                        </executions>
                    </plugin>
                    <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-enforcer-plugin</artifactId>
                        <version>3.6.3</version>
                        <executions>
                            <execution>
                                <id>assert-javafx-matches-nik</id>
                                <phase>initialize</phase>
                                <goals>
                                    <goal>enforce</goal>
                                </goals>
                                <configuration>
                                    <rules>
                                        <requireProperty>
                                            <property>nik.javafx.version</property>
                                            <!-- NIK reports 25.0.4+1 where Maven says 25.0.4. -->
                                            <regex>\Q${javafx.version}\E(\+.*)?</regex>
                                            <regexMessage>JAVA_HOME's LibericaFX is ${nik.javafx.version} but javafx.version is ${javafx.version}; compiling against one JavaFX and imaging another</regexMessage>
                                        </requireProperty>
                                    </rules>
                                </configuration>
                            </execution>
                        </executions>
                    </plugin>
                    <plugin>
                        <groupId>org.graalvm.buildtools</groupId>
                        <artifactId>native-maven-plugin</artifactId>
                        <version>0.10.6</version>
                        <executions>
                            <execution>
                                <id>build-native</id>
                                <phase>package</phase>
                                <goals>
                                    <goal>compile-no-fork</goal>
                                </goals>
                            </execution>
                        </executions>
                        <configuration>
                            <imageName>diskinventory</imageName>
                            <mainClass>com.kodewerk.diskinventory.ui.Main</mainClass>
                            <metadataRepository>
                                <enabled>true</enabled>
                            </metadataRepository>
                            <buildArgs>
                                <!-- ForeignAPISupport is on by default in NIK 25; only the permission is needed. -->
                                <buildArg>-H:EnableNativeAccess=ALL-UNNAMED</buildArg>
                            </buildArgs>
                        </configuration>
                    </plugin>
                </plugins>
            </build>
        </profile>
    </profiles>
```

The phases are deliberate: `properties` runs at `validate` and `enforcer` at `initialize`, so the property exists before the rule reads it.

- [ ] **Step 2: Prove the default build is unaffected**

```bash
mvn -q test
```

Expected: BUILD SUCCESS, no native-image invocation, no NIK required.

- [ ] **Step 3: Prove the version assertion fires**

```bash
JAVA_HOME=~/.sdkman/candidates/java/25.0.4.fx-nik \
    mvn -Pnative -DskipTests -Djavafx.version=25.0.2 package 2>&1 | tail -20
```

Expected: BUILD FAILURE from `maven-enforcer-plugin` with the message `JAVA_HOME's LibericaFX is 25.0.4+1 but javafx.version is 25.0.2`.

- [ ] **Step 4: Build the native image**

```bash
JAVA_HOME=~/.sdkman/candidates/java/25.0.4.fx-nik mvn -Pnative -DskipTests package
ls -lh target/diskinventory
```

Expected: BUILD SUCCESS after several minutes, and an executable of roughly 80-150 MB.

- [ ] **Step 5: Run the canary and record what it says**

```bash
./target/diskinventory --scan .
echo "exit=$?"
```

Two outcomes, both informative:
- `exit=3` plus `allocated sizes unavailable` — the expected result before metadata capture, and proof the canary works. Task 3 fixes it.
- `exit=0` with `allocated` differing from `logical` — the downcall stub was registered without help. Note this in the commit message; Task 3 still runs, because the GUI paths need the capture regardless.

Record the actual exit code; Task 3's verification compares against it.

- [ ] **Step 6: Commit**

```bash
git add pom.xml
git commit -m "build: add native profile for Liberica NIK Full

JavaFX resolves from NIK's platform modules under a classpath native build,
so the Maven version and LibericaFX's must agree; properties-maven-plugin reads
JAVA_HOME's javafx.properties and enforcer asserts the match, prefix-wise,
since NIK reports 25.0.4+1 against Maven's 25.0.4."
```

---

### Task 3: Capture and commit reachability metadata

**Files:**
- Create: `src/main/resources/META-INF/native-image/com.kodewerk/diskinventory/*.json` (generated)
- Modify: `README.md` (regeneration instructions)

**Interfaces:**
- Consumes: `Main.main` and `Main.scan` from Task 1, the `native` profile from Task 2.
- Produces: committed metadata that makes `./target/diskinventory --scan .` exit `0`.

**This task requires a human at a keyboard with a display.** The GUI run cannot be automated; an agent executing this plan must stop and ask the user to perform Step 2.

- [ ] **Step 1: Build the classpath the agent will use**

```bash
mvn -q -DskipTests package dependency:build-classpath -Dmdep.outputFile=target/cp.txt
export NIK=~/.sdkman/candidates/java/25.0.4.fx-nik
export CP="target/classes:$(cat target/cp.txt)"
export OUT=src/main/resources/META-INF/native-image/com.kodewerk/diskinventory
mkdir -p "$OUT"
```

- [ ] **Step 2: Agent run 1 — the GUI, with no path argument**

```bash
$NIK/bin/java -agentlib:native-image-agent=config-output-dir=$OUT \
    --enable-native-access=ALL-UNNAMED,javafx.graphics -cp "$CP" com.kodewerk.diskinventory.ui.Main
```

No argument, so the `DirectoryChooser` opens first — that path must be exercised. Then click through, in this order:

1. Choose a directory with a few levels of nesting (`/usr/share` works).
2. Click a pie slice to drill in.
3. Click the up button repeatedly, past the launch point, so `scanParent` runs.
4. Toggle the largest-files list on.
5. Toggle the size mode between logical and allocated.
6. Open the `df` panel.
7. Quit the app normally — the agent writes its config on JVM exit, so killing the process loses everything.

Expected: `ls $OUT` shows at least `reachability-metadata.json`.

- [ ] **Step 3: Agent run 2 — the headless scan, merged**

```bash
$NIK/bin/java -agentlib:native-image-agent=config-merge-dir=$OUT \
    --enable-native-access=ALL-UNNAMED,javafx.graphics -cp "$CP" com.kodewerk.diskinventory.ui.Main --scan .
```

Expected: exit 0, and the JSON now contains a `foreign` section describing the `lstat` downcall.

```bash
grep -c foreign $OUT/reachability-metadata.json
```

Expected: at least 1. If it is 0, the FFM entries did not land and the native binary will keep failing the canary — re-run this step and confirm the JVM run itself printed a non-zero `allocated` figure.

- [ ] **Step 4: Strip machine-local entries**

```bash
grep -n "/home/finkel\|/tmp/" $OUT/*.json
```

Expected: no matches. If any appear, delete those entries by hand — they are absolute paths from this machine and mean nothing on a CI runner.

- [ ] **Step 5: Rebuild and verify the canary flips**

```bash
JAVA_HOME=~/.sdkman/candidates/java/25.0.4.fx-nik mvn -Pnative -DskipTests package
./target/diskinventory --scan .
echo "exit=$?"
```

Expected: `exit=0`, with `allocated` differing from `logical`.

- [ ] **Step 6: Verify the native GUI by hand**

```bash
./target/diskinventory
````

Expected: the directory chooser opens, a scan renders, drill-down works, the largest-files list and `df` panel populate, and no `ClassNotFoundException` / `MissingResourceException` appears on the console. This is the only GUI verification the project has, by design.

- [ ] **Step 7: Document regeneration in the README**

In `README.md`, under the native build section added in Task 5 — or at the end of the file if Task 5 has not run yet — add:

````markdown
### Regenerating native-image metadata

Needed only if reflection, resources, or FFM usage changes. Requires NIK Full
and a display; the agent writes its config when the JVM exits, so quit the app
normally.

```
mvn -q -DskipTests package dependency:build-classpath -Dmdep.outputFile=target/cp.txt
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
```

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/META-INF/native-image README.md
git commit -m "build: commit tracing-agent reachability metadata

Two merged runs: the GUI with no argument so the directory chooser is
exercised, then --scan so the lstat downcall descriptor is registered. Only
app-level entries matter here; NIK's JavaFXFeature registers JavaFX internals
for every target platform itself."
```

---

### Task 4: Choose the GC by measurement

**Files:**
- Modify: `pom.xml` (`native` profile `buildArgs`)

**Interfaces:**
- Consumes: the `native` profile from Task 2, working metadata from Task 3.
- Produces: a GC decision recorded as a comment plus, if parallel wins, one buildArg.

- [ ] **Step 1: Time the serial build (current state)**

```bash
JAVA_HOME=~/.sdkman/candidates/java/25.0.4.fx-nik mvn -q -Pnative -DskipTests package
cp target/diskinventory /tmp/diskinventory-serial
for i in 1 2 3; do /usr/bin/time -f "serial %e s" /tmp/diskinventory-serial --scan /usr >/dev/null; done
```

Expected: three `serial N.N s` lines on stderr. Record them.

- [ ] **Step 2: Build with the parallel GC**

Temporarily add to the `native-maven-plugin` `buildArgs` in `pom.xml`:

```xml
                                <buildArg>--gc=parallel</buildArg>
```

Then:

```bash
JAVA_HOME=~/.sdkman/candidates/java/25.0.4.fx-nik mvn -q -Pnative -DskipTests package
cp target/diskinventory /tmp/diskinventory-parallel
for i in 1 2 3; do /usr/bin/time -f "parallel %e s" /tmp/diskinventory-parallel --scan /usr >/dev/null; done
```

Expected: three `parallel N.N s` lines. Record them.

- [ ] **Step 3: Pin the winner**

Compare the best-of-three for each. If parallel is faster by more than 5%, keep the buildArg and change its comment to record the numbers:

```xml
                                <!-- Measured on /usr, best of 3: serial 12.4s, parallel 9.1s. -->
                                <buildArg>--gc=parallel</buildArg>
```

Otherwise remove the buildArg entirely and add above the remaining buildArg:

```xml
                            <!--
                                GC: serial (the native-image default) measured on /usr,
                                best of 3: serial 12.4s vs parallel 12.6s. No flag needed.
                            -->
```

Replace the example numbers with the real ones in either case. Ties go to serial, which needs no flag.

- [ ] **Step 4: Verify the pinned build still passes the canary**

```bash
JAVA_HOME=~/.sdkman/candidates/java/25.0.4.fx-nik mvn -q -Pnative -DskipTests package
./target/diskinventory --scan .
echo "exit=$?"
```

Expected: `exit=0`.

- [ ] **Step 5: Clean up and commit**

```bash
rm -f /tmp/diskinventory-serial /tmp/diskinventory-parallel
git add pom.xml
git commit -m "build: pin the native GC by measurement

scan builds an immutable tree in one allocation-heavy pass, and the native
default is serial where the JVM build has been on G1, so the choice was timed
rather than assumed. Numbers are in the pom comment."
```

---

### Task 5: Release workflow jobs and distribution docs

**Files:**
- Create: `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` (generated by the wrapper plugin)
- Modify: `.github/workflows/release.yml`
- Modify: `README.md`

**Interfaces:**
- Consumes: the `native` profile from Task 2, metadata from Task 3, the GC decision from Task 4.
- Produces: `target/dist/diskinventory-<version>-<platform>.tar.gz` uploaded as release artifacts, alongside the existing `.dmg` and `.deb`.

- [ ] **Step 1: Add the Maven wrapper**

The Linux job runs in a bare `ubuntu:22.04` container, which has no Maven, and 22.04's apt Maven is 3.6.3 — old enough to be a liability. The wrapper pins a modern Maven for CI and contributors alike.

```bash
mvn -q wrapper:wrapper -Dmaven=3.9.11
./mvnw -q -version
```

Expected: `Apache Maven 3.9.11`.

- [ ] **Step 2: Verify the wrapper drives the native build**

```bash
JAVA_HOME=~/.sdkman/candidates/java/25.0.4.fx-nik ./mvnw -q -Pnative -DskipTests package
./target/diskinventory --scan .
echo "exit=$?"
```

Expected: `exit=0`.

- [ ] **Step 3: Add the two native jobs**

In `.github/workflows/release.yml`, insert both jobs between the existing `package` job and the `release` job:

```yaml
  native-linux:
    runs-on: ubuntu-latest
    # 22.04 keeps the binary's glibc floor at 2.35, so Ubuntu 22.04 and
    # Debian 12 can run it. The .deb job above deliberately stays on the
    # runner's own image; the two floors are independent.
    container: ubuntu:22.04
    steps:
      - name: Install build dependencies
        run: |
          apt-get update
          apt-get install -y --no-install-recommends \
            build-essential zlib1g-dev curl git ca-certificates \
            libgtk-3-dev libpango1.0-dev libxtst-dev libgl-dev libasound2-dev
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v6
        with:
          distribution: liberica-nik
          java-version: '25'
          java-package: jdk+fx
      - name: Build native image
        run: ./mvnw -B -Pnative -DskipTests package
      - name: Smoke test
        run: ./target/diskinventory --scan .
      - name: Tarball
        run: |
          VERSION=$(./mvnw -q help:evaluate -Dexpression=project.version -DforceStdout | sed 's/-SNAPSHOT//')
          mkdir -p target/dist
          tar czf "target/dist/diskinventory-$VERSION-linux-x64.tar.gz" -C target diskinventory
      - uses: actions/upload-artifact@v4
        with:
          name: DiskInventory-native-linux-x64
          path: target/dist/*
          if-no-files-found: error

  native-macos:
    runs-on: macos-14        # Apple Silicon
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v6
        with:
          distribution: liberica-nik
          java-version: '25'
          java-package: jdk+fx
      - name: Build native image
        run: ./mvnw -B -Pnative -DskipTests package
      - name: Smoke test
        run: ./target/diskinventory --scan .
      - name: Tarball
        run: |
          VERSION=$(./mvnw -q help:evaluate -Dexpression=project.version -DforceStdout | sed 's/-SNAPSHOT//')
          mkdir -p target/dist
          tar czf "target/dist/diskinventory-$VERSION-osx-aarch64.tar.gz" -C target diskinventory
      - uses: actions/upload-artifact@v4
        with:
          name: DiskInventory-native-osx-aarch64
          path: target/dist/*
          if-no-files-found: error
```

Then change the `release` job's `needs:` from:

```yaml
    needs: package
```

to:

```yaml
    needs: [package, native-linux, native-macos]
```

`release` already downloads with `merge-multiple: true`, so the tarballs join the installers without further change.

- [ ] **Step 4: Validate the workflow file parses**

```bash
python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/release.yml')); print('ok')"
````

Expected: `ok`.

- [ ] **Step 5: Document the native artifacts in the README**

In `README.md`, after the existing packaging/install section, add:

````markdown
## Native executable

A single ahead-of-time compiled binary, built with
[Liberica NIK Full](https://bell-sw.com/liberica-native-image-kit/) 25, which
bundles JavaFX. Starts in milliseconds and carries no runtime directory.

Download `diskinventory-<version>-linux-x64.tar.gz` or
`-osx-aarch64.tar.gz` from the releases page, unpack, run.

- **Linux:** needs glibc 2.35 or newer (Ubuntu 22.04+, Debian 12+) and the usual
  desktop GTK3 stack, which stays dynamically linked.
- **macOS:** the binary is unsigned, so Gatekeeper quarantines it after
  download. Clear it with `xattr -d com.apple.quarantine ./diskinventory`. The
  `.dmg` is unsigned too — this is not specific to the native build.

Build it yourself with NIK Full as `JAVA_HOME`:

```
JAVA_HOME=/path/to/liberica-nik-full-25 ./mvnw -Pnative -DskipTests package
./target/diskinventory --scan .
```

The `.deb` and `.dmg` installers built by `bin/package.sh` are unaffected and
remain the recommended install for most people.
```

- [ ] **Step 6: Commit**

```bash
git add mvnw mvnw.cmd .mvn .github/workflows/release.yml README.md
git commit -m "ci: build native tarballs for linux-x64 and osx-aarch64

Linux builds inside an ubuntu:22.04 container so the binary's glibc floor is
2.35 rather than the runner's 2.39, which would lock out Ubuntu 22.04 and
Debian 12. Each job smoke-tests the binary with --scan, whose exit code 3
catches an FFM downcall that silently degraded to logical sizes. Adds the Maven
wrapper because the container has no Maven and 22.04's apt build is 3.6.3."
```

- [ ] **Step 7: Exercise the workflow end to end**

```bash
gh workflow run release.yml
gh run watch
```

Expected: all four jobs succeed; `release` is skipped because this is not a tag. Download both tarballs from the run's artifacts, unpack the Linux one, and run `./diskinventory` — it should open the GUI. That manual click is the release gate this project chose over CI GUI testing.

---

## Verification checklist

Run before declaring the work done:

```bash
mvn -q test                                                          # default build untouched
JAVA_HOME=~/.sdkman/candidates/java/25.0.4.fx-nik ./mvnw -q -Pnative -DskipTests package
./target/diskinventory --scan .; echo "exit=$?"                      # expect exit=0
./target/diskinventory                                               # GUI opens, drill-down works
bash bin/package.sh                                                  # jpackage path still builds
````
