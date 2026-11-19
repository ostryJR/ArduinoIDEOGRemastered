#!/bin/bash
#
# Remastered: build the compile-pipeline helpers natively for Apple Silicon,
# at the exact versions shipped with stock Arduino 1.8.19:
#
#   arduino-builder 1.6.1        <- github.com/arduino/arduino-builder (Go)
#   ctags 5.8-arduino11          <- github.com/arduino/ctags (C)
#
# The compilers themselves (avr-gcc, arm-none-eabi-gcc, ...) come from the
# board packages and are not touched here.
#
# Usage: build-builder.sh <output dir>
# Produces <out>/arduino-builder and <out>/ctags (arm64, ad-hoc signed).
# Everything else (Go toolchain, sources, caches) lives in ./work.

set -euo pipefail

OUT="${1:?output dir required}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$HERE/work"
mkdir -p "$WORK" "$OUT"
OUT="$(cd "$OUT" && pwd)"

BUILDER_TAG="1.6.1"
CTAGS_TAG="5.8-arduino11"
# Go 1.17 is the newest release that still builds the late-2020
# golang.org/x/sys pinned by arduino-builder 1.6.1 on darwin/arm64.
GO_VERSION="1.17.13"
GO_SHA256="e4ccc9c082d91eaa0b866078b591fc97d24b91495f12deb3dd2d8eda4e55a6ea"

is_arm64() { [ -f "$1" ] && [ "$(lipo -archs "$1" 2>/dev/null)" = "arm64" ]; }

if is_arm64 "$OUT/arduino-builder" && is_arm64 "$OUT/ctags"; then
  echo "native-arm64: arduino-builder and ctags already built in $OUT"
  exit 0
fi

# ------------------------------------------------------------------- Go
GOROOT_DIR="$WORK/go"
if [ ! -x "$GOROOT_DIR/bin/go" ]; then
  echo "native-arm64: downloading Go $GO_VERSION"
  tarball="$WORK/go$GO_VERSION.darwin-arm64.tar.gz"
  curl -sSfL -o "$tarball" "https://go.dev/dl/go$GO_VERSION.darwin-arm64.tar.gz"
  echo "$GO_SHA256  $tarball" | shasum -a 256 -c - >/dev/null
  rm -rf "$GOROOT_DIR"
  tar -xzf "$tarball" -C "$WORK"
  rm -f "$tarball"
fi

# ------------------------------------------------------- arduino-builder
cd "$WORK"
if [ ! -d arduino-builder ]; then
  git clone -q --depth 1 --branch "$BUILDER_TAG" https://github.com/arduino/arduino-builder.git
fi
(
  cd arduino-builder
  export GOROOT="$GOROOT_DIR"
  export PATH="$GOROOT/bin:$PATH"
  export GOPATH="$WORK/gopath"
  export GOCACHE="$WORK/gocache"
  export GOMODCACHE="$WORK/gopath/pkg/mod"
  export GO111MODULE=on
  export GOFLAGS="-mod=mod"
  export GOOS=darwin GOARCH=arm64 CGO_ENABLED=0
  go build -trimpath -ldflags "-s -w" -o "$OUT/arduino-builder" .
)

# ------------------------------------------------------------------ ctags
cd "$WORK"
if [ ! -d ctags ]; then
  git clone -q --depth 1 --branch "$CTAGS_TAG" https://github.com/arduino/ctags.git
fi
(
  cd ctags
  [ -f Makefile ] && make distclean >/dev/null 2>&1 || true
  # ctags' own __unused__ macro breaks the macOS SDK's use of
  # __attribute__((__unused__)) in <dirent.h>; it only silences warnings.
  sed -i '' 's/^# define __unused__  __attribute__((unused))$/# define __unused__/' general.h
  CC=clang \
  CFLAGS="-arch arm64 -mmacosx-version-min=11.0 -O2 -Wno-implicit-function-declaration -Wno-implicit-int -Wno-deprecated-non-prototype -Wno-int-conversion" \
  LDFLAGS="-arch arm64 -mmacosx-version-min=11.0" \
    ./configure --build=aarch64-apple-darwin --host=aarch64-apple-darwin >/dev/null
  make -j"$(sysctl -n hw.ncpu)" >/dev/null
  cp ctags "$OUT/ctags"
)

codesign -s - -f "$OUT/arduino-builder" "$OUT/ctags" 2>/dev/null

echo "native-arm64: built into $OUT"
file "$OUT/arduino-builder" "$OUT/ctags"
