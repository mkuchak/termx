#!/usr/bin/env bash
# Build a SINGLE statically-linked mosh-client PIE per ABI for Android.
#
# WHY THIS EXISTS (see BUILD.md + PROJECT_KNOWLEDGE gotcha #32): the earlier
# approach (build-mosh-jni-bundle.sh, removed) shipped ~53 Termux-built shared
# objects per ABI and patchelf-renamed their SONAMEs. That bundle SIGSEGV'd
# pre-main in the dynamic linker on real devices (ODR/static-init across ~40
# abseil .so) — mosh never actually worked. This script instead links OpenSSL,
# protobuf (3.21 — pre-abseil), ncursesw and libc++ STATICALLY into one binary
# whose only DT_NEEDED are bionic system libs. No dynamic C++ loading, no ODR
# surface, nothing to misload.
#
# Output: out/<abi>/final/libmoshclient.so  (a PIE executable, .so-named so the
# APK installer ships it in nativeLibraryDir where it can be exec'd).
#
# Requirements: Android NDK r27+, the SDK cmake, a host `protoc` whose version
# EXACTLY matches PROTOBUF_VER (Ubuntu 24.04: `apt install protobuf-compiler`
# gives 3.21.12), curl, make, a host C/C++ toolchain (for ncurses' build tools).
#
# Usage:  ./build-mosh-static.sh [arm64-v8a|armeabi-v7a]
# Env:    ANDROID_NDK_ROOT (required), ANDROID_SDK_CMAKE (cmake path),
#         MOSH_BUILD_DIR (scratch dir, default /tmp/mosh-build).
set -euo pipefail

ABI="${1:-arm64-v8a}"
case "$ABI" in
  arm64-v8a)   TARGET=aarch64-linux-android;    SSL_ARCH=android-arm64; HOST=aarch64-linux-android ;;
  armeabi-v7a) TARGET=armv7a-linux-androideabi; SSL_ARCH=android-arm;   HOST=armv7a-linux-androideabi ;;
  *) echo "unknown ABI $ABI"; exit 2 ;;
esac

NDK="${ANDROID_NDK_ROOT:?set ANDROID_NDK_ROOT to your NDK r27+ path}"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
CMAKE="${ANDROID_SDK_CMAKE:-cmake}"
API=28                       # == app minSdk; bionic >=26 has nl_langinfo (no mosh patch needed)
ROOT="${MOSH_BUILD_DIR:-/tmp/mosh-build}"
SRC="$ROOT/src"; OUT="$ROOT/out/$ABI"; JOBS=$(nproc)
OPENSSL_VER=3.0.15; PROTOBUF_VER=3.21.12; NCURSES_VER=6.4

mkdir -p "$SRC" "$OUT" "$ROOT/markers"
M() { echo "$ROOT/markers/$ABI-$1.done"; }
log() { echo "=== [$(date +%H:%M:%S)] $* ==="; }

export ANDROID_NDK_ROOT="$NDK" ANDROID_NDK_HOME="$NDK"
export PATH="$TC/bin:$PATH"
export CC="$TC/bin/${TARGET}${API}-clang"  CXX="$TC/bin/${TARGET}${API}-clang++"
export AR="$TC/bin/llvm-ar" RANLIB="$TC/bin/llvm-ranlib" STRIP="$TC/bin/llvm-strip" LD="$TC/bin/ld"

# ---------- fetch ----------
fetch() { [ -f "$SRC/$2" ] || { log "download $2"; curl -fsSL "$1" -o "$SRC/$2"; }; }
if [ ! -f "$(M fetch)" ]; then
  fetch "https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VER/openssl-$OPENSSL_VER.tar.gz" openssl.tgz
  fetch "https://github.com/protocolbuffers/protobuf/releases/download/v21.12/protobuf-cpp-$PROTOBUF_VER.tar.gz" protobuf.tgz
  fetch "https://ftp.gnu.org/gnu/ncurses/ncurses-$NCURSES_VER.tar.gz" ncurses.tgz
  fetch "https://github.com/mobile-shell/mosh/releases/download/mosh-1.4.0/mosh-1.4.0.tar.gz" mosh.tgz
  cd "$SRC"
  for p in openssl protobuf ncurses mosh; do
    [ -d "$p" ] || { mkdir "$p" && tar xzf "$p.tgz" -C "$p" --strip-components=1; }
  done
  touch "$(M fetch)"
fi

# ---------- host protoc (must EXACTLY match PROTOBUF_VER) ----------
PROTOC=$(command -v protoc || true)
PVER=$("$PROTOC" --version 2>/dev/null | awk '{print $2}')
[ "$PVER" = "$PROTOBUF_VER" ] || { echo "system protoc is '$PVER', need $PROTOBUF_VER (apt install protobuf-compiler)"; exit 1; }
log "host protoc $PROTOC (libprotoc $PVER)"

# ---------- OpenSSL (static libcrypto) ----------
if [ ! -f "$(M openssl)" ]; then
  log "OpenSSL $OPENSSL_VER / $ABI"; cd "$SRC/openssl"; make clean 2>/dev/null || true
  ./Configure "$SSL_ARCH" -D__ANDROID_API__=$API --prefix="$OUT/openssl" no-shared no-tests no-ui-console no-engine
  make -j"$JOBS" build_libs && make install_dev && touch "$(M openssl)"
fi

# ---------- protobuf 3.21 (static, NO abseil) ----------
if [ ! -f "$(M protobuf)" ]; then
  log "protobuf $PROTOBUF_VER / $ABI"; B="$ROOT/build/protobuf-$ABI"; rm -rf "$B"; mkdir -p "$B"
  ( cd "$B" && "$CMAKE" "$SRC/protobuf/cmake" \
      -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
      -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="android-$API" -DCMAKE_INSTALL_PREFIX="$OUT/protobuf" \
      -Dprotobuf_BUILD_TESTS=OFF -Dprotobuf_BUILD_PROTOC_BINARIES=OFF -Dprotobuf_BUILD_SHARED_LIBS=OFF \
      -Dprotobuf_WITH_ZLIB=OFF -DCMAKE_BUILD_TYPE=Release
    "$CMAKE" --build . --parallel "$JOBS" && "$CMAKE" --install . )
  touch "$(M protobuf)"
fi

# ---------- ncursesw (static) ----------
if [ ! -f "$(M ncurses)" ]; then
  log "ncurses $NCURSES_VER / $ABI"; cd "$SRC/ncurses"; make distclean 2>/dev/null || true
  ./configure --host="$HOST" --prefix="$OUT/ncurses" \
      --without-ada --without-cxx --without-cxx-binding --without-manpages \
      --without-progs --without-tests --without-debug \
      --enable-widec --enable-static --disable-shared \
      --with-default-terminfo-dir=/data/local/tmp/terminfo ac_cv_header_locale_h=no
  make -j"$JOBS" && make install.libs install.includes
  ( cd "$OUT/ncurses/include" && ln -sf ncursesw ncurses 2>/dev/null || true )
  touch "$(M ncurses)"
fi

# ---------- mosh-client (single static PIE) ----------
log "mosh-client / $ABI"; cd "$SRC/mosh"; make distclean 2>/dev/null || true
PB="$OUT/protobuf"; SSL="$OUT/openssl"; NC="$OUT/ncurses"
export CPPFLAGS="-I$PB/include -I$SSL/include -I$NC/include -I$NC/include/ncursesw"
export CXXFLAGS="-O2 -fPIE -fno-strict-aliasing"
# 16 KB max-page-size so the PIE also loads on 16 KB-page devices (Android 15).
export LDFLAGS="-fPIE -pie -static-libstdc++ -Wl,-z,max-page-size=16384 -L$PB/lib -L$SSL/lib -L$NC/lib"
export PROTOC="$PROTOC"
export protobuf_CFLAGS="-I$PB/include"
export protobuf_LIBS="-L$PB/lib -lprotobuf -llog"   # -llog: Android libprotobuf.a calls __android_log_write
export OpenSSL_CFLAGS="-I$SSL/include"
export OpenSSL_LIBS="-L$SSL/lib -lcrypto"
export TINFO_CFLAGS="-I$NC/include/ncursesw"
export TINFO_LIBS="-L$NC/lib -lncursesw"
./configure --host="$HOST" --prefix="$OUT/mosh" \
    --with-crypto-library=openssl --disable-server --without-utempter --disable-hardening-check
make -j"$JOBS"

BIN="$SRC/mosh/src/frontend/mosh-client"
[ -f "$BIN" ] || { echo "mosh-client NOT produced"; exit 1; }
mkdir -p "$OUT/final"
cp "$BIN" "$OUT/final/libmoshclient.so"; "$STRIP" "$OUT/final/libmoshclient.so" || true

log "VALIDATE $ABI"; file "$OUT/final/libmoshclient.so"
echo "DT_NEEDED (must be ONLY bionic system libs):"
"$TC/bin/llvm-readelf" -dW "$OUT/final/libmoshclient.so" | grep NEEDED
"$TC/bin/llvm-readelf" -hW "$OUT/final/libmoshclient.so" | grep -E "Type:|Machine:"
log "DONE -> $OUT/final/libmoshclient.so  (install into jniLibs/$ABI/)"
