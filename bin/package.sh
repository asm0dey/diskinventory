#!/usr/bin/env bash
# Builds a native installer for the current platform with jpackage:
# .dmg on macOS, .deb on Linux. The bundled runtime is jlinked from the app
# module + JavaFX jmods, so the result needs no Java on the target machine.
# Windows is not maintained - contributions welcome (jpackage --type msi
# under git-bash worked as of v1.0.0 if you want a starting point).
set -euo pipefail
cd "$(dirname "$0")/.."

JFX_VERSION=25.0.1
MODULE=com.kodewerk.diskinventory
MAIN_CLASS=com.kodewerk.diskinventory.ui.Main

case "$(uname -s)" in
    Darwin)
        [ "$(uname -m)" = "arm64" ] && PLATFORM=osx-aarch64 || PLATFORM=osx-x64
        TYPE=dmg
        EXTRA_ARGS=()
        ;;
    Linux)
        PLATFORM=linux-x64
        TYPE=deb
        EXTRA_ARGS=(--linux-shortcut --linux-menu-group Utilities)
        ;;
    *)
        echo "Unsupported platform: $(uname -s) — Windows packaging is not maintained; patches welcome" >&2
        exit 1
        ;;
esac

VERSION=$(mvn -q help:evaluate -Dexpression=project.version -DforceStdout | sed 's/-SNAPSHOT//')
echo "Packaging DiskInventory $VERSION for $PLATFORM ($TYPE)"

mvn -q -DskipTests package

JMODS_PARENT=target/javafx-jmods-$PLATFORM
JMODS_DIR=$JMODS_PARENT/javafx-jmods-$JFX_VERSION
if [ ! -d "$JMODS_DIR" ]; then
    echo "Downloading JavaFX $JFX_VERSION jmods for $PLATFORM"
    curl -fsSL -o target/jmods.zip \
        "https://download2.gluonhq.com/openjfx/${JFX_VERSION}/openjfx-${JFX_VERSION}_${PLATFORM}_bin-jmods.zip"
    mkdir -p "$JMODS_PARENT"
    unzip -q -o target/jmods.zip -d "$JMODS_PARENT"
    rm target/jmods.zip
fi

rm -rf target/dist
jpackage \
    --type "$TYPE" \
    --name DiskInventory \
    --app-version "$VERSION" \
    --vendor Kodewerk \
    --module-path target/diskinventory.jar \
    --module-path "$JMODS_DIR" \
    --module "$MODULE/$MAIN_CLASS" \
    --java-options "--enable-native-access=$MODULE,javafx.graphics" \
    --dest target/dist \
    ${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"}

# Tag artifacts with the platform so the mac arm64/x64 builds don't collide.
for artifact in target/dist/*; do
    base=$(basename "$artifact")
    mv "$artifact" "target/dist/${base%.*}-$PLATFORM.${base##*.}"
done

ls -lh target/dist/
