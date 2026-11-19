#!/bin/bash
#
# Remastered: package Arduino IDE 1.8 as a native arm64 macOS app.
#
# Produces: dist/Arduino 1.8 Remastered.app
#
# The IDE jars (arduino-core/arduino-core.jar, app/pde.jar) must already be
# built, or pass --compile to build them with the bundled Ant first.
#
# Boards, toolchains, examples and libraries are reused from an installed
# stock Arduino 1.8.x app (default /Applications/Arduino.app, override with
# ARDUINO_SOURCE_APP=/path/to/Arduino.app).
#
# Usage: build/macosx/build-remastered.sh [--compile]

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/jdk-22.jdk/Contents/Home}"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

SOURCE_APP="${ARDUINO_SOURCE_APP:-/Applications/Arduino.app}"
APP_NAME="Arduino 1.8 Remastered"
VERSION="1.8.19"
WORK="$ROOT/build/macosx/remastered-work"
INPUT="$WORK/input"
RUNTIME="$WORK/runtime"
DIST="$ROOT/dist"
APP="$DIST/$APP_NAME.app"

COMPILE=0
for arg in "$@"; do
  case "$arg" in
    --compile) COMPILE=1 ;;
    -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
    *) echo "Unknown argument: $arg" >&2; exit 1 ;;
  esac
done

log() { printf '\n==> %s\n' "$*"; }

# ------------------------------------------------------------------ compile
if [ "$COMPILE" = 1 ]; then
  ANT="$ROOT/tools/ant/bin/ant"
  [ -x "$ANT" ] || { echo "Ant not found at $ANT" >&2; exit 1; }
  log "Compiling arduino-core and app"
  (cd arduino-core && "$ANT")
  (cd app && "$ANT")
fi

for jar in arduino-core/arduino-core.jar app/pde.jar; do
  [ -f "$jar" ] || { echo "Missing $jar - build it first or pass --compile" >&2; exit 1; }
done
[ -d "$SOURCE_APP/Contents/Java" ] || {
  echo "Stock Arduino app not found at $SOURCE_APP (set ARDUINO_SOURCE_APP)" >&2; exit 1; }

# ------------------------------------------------------------------ staging
log "Staging resources from $SOURCE_APP"
rm -rf "$INPUT"
mkdir -p "$INPUT"
# Everything from the stock Contents/Java except its jars and x86_64 JNI libs
rsync -a \
  --exclude '/*.jar' \
  --exclude '/lib/libastylej.*' \
  --exclude '/lib/liblistSerialsj.*' \
  "$SOURCE_APP/Contents/Java/" "$INPUT/"

# Our own resources win over the stock ones (preferences, theme, fonts, ...)
rsync -a build/shared/lib/ "$INPUT/lib/"
cp build/shared/revisions.txt "$INPUT/revisions.txt"

log "Collecting runtime jars"
for jar in arduino-core/arduino-core.jar app/pde.jar app/lib/*.jar arduino-core/lib/*.jar; do
  name="$(basename "$jar")"
  [ -f "$INPUT/$name" ] || cp "$jar" "$INPUT/$name"
done

log "Building arm64 JNI libraries"
build/macosx/native-arm64/build.sh "$JAVA_HOME" build/macosx/native-arm64/out
for lib in libastylej.dylib libastylej.jnilib liblistSerialsj.dylib; do
  cp "build/macosx/native-arm64/out/$lib" "$INPUT/lib/$lib"
  chmod 755 "$INPUT/lib/$lib"
done

# ------------------------------------------------------------------ runtime
log "Creating trimmed Java runtime"
DETECTED="$(jdeps --print-module-deps --ignore-missing-deps --multi-release 22 \
  --class-path "$(ls "$INPUT"/*.jar | tr '\n' ':')" "$INPUT"/*.jar 2>/dev/null || true)"
MODULES="java.base,java.desktop,java.datatransfer,java.instrument,java.logging,java.management,java.naming,java.net.http,java.prefs,java.scripting,java.security.jgss,java.sql,java.xml,jdk.accessibility,jdk.charsets,jdk.crypto.ec,jdk.httpserver,jdk.localedata,jdk.net,jdk.unsupported,jdk.zipfs"
if [ -n "$DETECTED" ]; then
  MODULES="$MODULES,$DETECTED"
fi
MODULES="$(echo "$MODULES" | tr ',' '\n' | sed '/^$/d' | sort -u | paste -sd, -)"
echo "modules: $MODULES"
rm -rf "$RUNTIME"
jlink --add-modules "$MODULES" \
  --strip-debug --no-header-files --no-man-pages \
  --compress zip-6 \
  --generate-cds-archive \
  --output "$RUNTIME"

# ------------------------------------------------------------------ package
log "Packaging $APP_NAME.app"
ASSOC="$WORK/assoc"
rm -rf "$ASSOC"
mkdir -p "$ASSOC"
for ext in ino c cpp h; do
  case "$ext" in
    ino) mime="text/x-arduino" ;;
    c)   mime="text/x-c" ;;
    cpp) mime="text/x-c++src" ;;
    h)   mime="text/x-c-header" ;;
  esac
  cat > "$ASSOC/$ext.properties" <<EOF
extension=$ext
mime-type=$mime
description=Arduino Source File
icon=$ROOT/build/macosx/pde.icns
mac.CFBundleTypeRole=Editor
mac.LSHandlerRank=Alternate
EOF
done
# .ino files belong to us; plain C/C++ files only offer us as an alternative
sed -i '' 's/mac.LSHandlerRank=Alternate/mac.LSHandlerRank=Owner/' "$ASSOC/ino.properties"

JAVA_OPTS=(
  '-DAPP_DIR=$APPDIR'
  '-Darduino.remastered.preferences=preferences-remastered.txt'
  '-Dapple.awt.application.name=Arduino'
  '-Dcom.apple.macos.use-file-dialog-packages=true'
  '-Dcom.apple.smallTabs=true'
  '-Djava.net.preferIPv4Stack=true'
  '-Dfile.encoding=UTF-8'
  '-Dswing.aatext=true'
  '-Dawt.useSystemAAFontSettings=on'
  '-Xms128m'
  '-Xmx1g'
  '-XX:+UseSerialGC'
  '-splash:$APPDIR/lib/splash.png'
  '--add-opens=java.desktop/sun.awt=ALL-UNNAMED'
  '--add-opens=java.desktop/javax.swing.text=ALL-UNNAMED'
  '--add-opens=java.desktop/javax.swing=ALL-UNNAMED'
  '--add-opens=java.base/java.lang=ALL-UNNAMED'
  '--add-opens=java.desktop/java.awt=ALL-UNNAMED'
  '--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED'
  '--add-opens=java.desktop/com.apple.laf=ALL-UNNAMED'
  '--enable-native-access=ALL-UNNAMED'
)
JPKG_OPTS=()
for opt in "${JAVA_OPTS[@]}"; do
  JPKG_OPTS+=(--java-options "$opt")
done
ASSOC_OPTS=()
for f in "$ASSOC"/*.properties; do
  ASSOC_OPTS+=(--file-associations "$f")
done

rm -rf "$APP"
mkdir -p "$DIST"
jpackage --type app-image \
  --name "$APP_NAME" \
  --dest "$DIST" \
  --input "$INPUT" \
  --main-jar pde.jar \
  --main-class processing.app.Base \
  --runtime-image "$RUNTIME" \
  --icon build/macosx/remastered.icns \
  --mac-package-identifier cc.arduino.Arduino.remastered \
  --mac-package-name "Arduino" \
  --app-version "$VERSION" \
  --copyright "© Arduino LLC. Remastered for Apple Silicon by Mikolaj Ostrowski." \
  "${ASSOC_OPTS[@]}" \
  "${JPKG_OPTS[@]}"

# jpackage puts every jar it finds under --input on the classpath (including
# ones inside hardware/, tools/ and libraries/). Restrict it to the IDE jars.
CFG="$APP/Contents/app/$APP_NAME.cfg"
log "Restricting classpath in $(basename "$CFG")"
python3 - "$CFG" "$APP/Contents/app" <<'PY'
import os, sys
cfg, appdir = sys.argv[1], sys.argv[2]
jars = sorted(f for f in os.listdir(appdir) if f.endswith(".jar"))
# pde.jar first so its manifest/classes win, then arduino-core, then deps
jars.sort(key=lambda j: (j != "pde.jar", j != "arduino-core.jar", j))
out, done = [], False
for line in open(cfg).read().splitlines():
    if line.startswith("app.classpath="):
        if not done:
            out.extend("app.classpath=$APPDIR/" + j for j in jars)
            done = True
        continue
    out.append(line)
if not done:
    i = out.index("[Application]") + 1
    out[i:i] = ["app.classpath=$APPDIR/" + j for j in jars]
open(cfg, "w").write("\n".join(out) + "\n")
PY

# Info.plist touches matching the stock app
PLIST="$APP/Contents/Info.plist"
/usr/libexec/PlistBuddy -c "Delete :CFBundleDisplayName" "$PLIST" >/dev/null 2>&1 || true
/usr/libexec/PlistBuddy -c "Add :CFBundleDisplayName string $APP_NAME" "$PLIST"
/usr/libexec/PlistBuddy -c "Delete :NSHighResolutionCapable" "$PLIST" >/dev/null 2>&1 || true
/usr/libexec/PlistBuddy -c "Add :NSHighResolutionCapable bool true" "$PLIST"
/usr/libexec/PlistBuddy -c "Delete :LSApplicationCategoryType" "$PLIST" >/dev/null 2>&1 || true
/usr/libexec/PlistBuddy -c "Add :LSApplicationCategoryType string public.app-category.education" "$PLIST"
/usr/libexec/PlistBuddy -c "Delete :LSMinimumSystemVersion" "$PLIST" >/dev/null 2>&1 || true
/usr/libexec/PlistBuddy -c "Add :LSMinimumSystemVersion string 11.0" "$PLIST"

log "Ad-hoc signing"
codesign --force --deep -s - "$APP"

log "Done"
echo "App:     $APP"
echo "Arch:    $(lipo -archs "$APP/Contents/MacOS/$APP_NAME")"
echo "Size:    $(du -sh "$APP" | cut -f1)"
