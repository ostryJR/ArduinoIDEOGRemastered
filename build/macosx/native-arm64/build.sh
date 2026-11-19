#!/bin/bash -e
#
# Remastered: build the IDE's JNI helper libraries natively for Apple Silicon.
# The upstream 1.8.19 release only ships x86_64 builds, which cannot be
# loaded by an arm64 JVM.
#
#   liblistSerialsj.dylib  <- arduino/listSerialPortsC + libserialport
#   libastylej.dylib       <- Artistic Style 2.05.1 (+ Arduino's JNI rename)
#
# Usage: build.sh <JAVA_HOME> <output dir>

JAVA_HOME_ARG="${1:?JAVA_HOME required}"
OUT="${2:?output dir required}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$HERE/work"
ARCH_FLAGS="-arch arm64 -mmacosx-version-min=11.0"
JNI_INC="-I$JAVA_HOME_ARG/include -I$JAVA_HOME_ARG/include/darwin"

mkdir -p "$WORK" "$OUT"
OUT="$(cd "$OUT" && pwd)"

if [ -f "$OUT/liblistSerialsj.dylib" ] && [ -f "$OUT/libastylej.dylib" ]; then
  echo "native-arm64: already built in $OUT"
  exit 0
fi

# ---------------------------------------------------------------- listSerials
cd "$WORK"
if [ ! -d listSerialPortsC ]; then
  git clone -q --depth 1 --recursive https://github.com/arduino/listSerialPortsC.git
fi
cd listSerialPortsC/libserialport

# Hand-written equivalents of what ./configure would generate on macOS
cat > config.h <<'EOF'
#define SP_PACKAGE_VERSION_MAJOR 0
#define SP_PACKAGE_VERSION_MINOR 1
#define SP_PACKAGE_VERSION_MICRO 1
#define SP_PACKAGE_VERSION_STRING "0.1.1"
#define SP_LIB_VERSION_CURRENT 0
#define SP_LIB_VERSION_REVISION 0
#define SP_LIB_VERSION_AGE 0
#define SP_LIB_VERSION_STRING "0:0:0"
#define SP_API __attribute__((visibility("default")))
#define SP_PRIV __attribute__((visibility("hidden")))
#define HAVE_SYS_FILE_H 1
#define HAVE_REALPATH 1
#define HAVE_FLOCK 1
EOF
sed -e 's|^#undef SP_PACKAGE_VERSION_MAJOR|#define SP_PACKAGE_VERSION_MAJOR 0|' \
    -e 's|^#undef SP_PACKAGE_VERSION_MINOR|#define SP_PACKAGE_VERSION_MINOR 1|' \
    -e 's|^#undef SP_PACKAGE_VERSION_MICRO|#define SP_PACKAGE_VERSION_MICRO 1|' \
    -e 's|^#undef SP_PACKAGE_VERSION_STRING|#define SP_PACKAGE_VERSION_STRING "0.1.1"|' \
    -e 's|^#undef SP_LIB_VERSION_CURRENT|#define SP_LIB_VERSION_CURRENT 0|' \
    -e 's|^#undef SP_LIB_VERSION_REVISION|#define SP_LIB_VERSION_REVISION 0|' \
    -e 's|^#undef SP_LIB_VERSION_AGE|#define SP_LIB_VERSION_AGE 0|' \
    -e 's|^#undef SP_LIB_VERSION_STRING|#define SP_LIB_VERSION_STRING "0:0:0"|' \
    libserialport.h.in > libserialport.h
cd ..
clang $ARCH_FLAGS -O2 -shared -I. -Ilibserialport $JNI_INC \
  jnilib.c libserialport/serialport.c libserialport/macosx.c \
  -framework IOKit -framework CoreFoundation \
  -o "$OUT/liblistSerialsj.dylib"

# --------------------------------------------------------------------- astyle
cd "$WORK"
if [ ! -d astyle ]; then
  curl -sSL -o astyle.tar.gz \
    "https://sourceforge.net/projects/astyle/files/astyle/astyle%202.05.1/astyle_2.05.1_macosx.tar.gz/download"
  tar xzf astyle.tar.gz
  rm astyle.tar.gz
fi
cd astyle/src
# Arduino's patch: JNI symbols live in cc.arduino.packages.formatter
sed -i '' 's/Java_AStyleInterface_/Java_cc_arduino_packages_formatter_AStyleInterface_/g' astyle_main.cpp astyle_main.h
clang++ $ARCH_FLAGS -Os -shared -std=c++11 -fno-rtti -fno-exceptions -DASTYLE_JNI \
  $JNI_INC ASBeautifier.cpp ASEnhancer.cpp ASFormatter.cpp ASResource.cpp astyle_main.cpp \
  -liconv -o "$OUT/libastylej.dylib"
cp "$OUT/libastylej.dylib" "$OUT/libastylej.jnilib"

echo "native-arm64: built into $OUT"
file "$OUT"/*.dylib
