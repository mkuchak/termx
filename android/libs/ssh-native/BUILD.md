# BUILD.md — reproducing `jniLibs/<abi>/libmoshclient.so`

The `jniLibs/arm64-v8a/` and `jniLibs/armeabi-v7a/` trees are generated — not
hand-written — and should be rebuilt whenever we pick up a newer mosh,
protobuf, or abseil version. This file documents how to regenerate them so a
new contributor (or CI) can reproduce bit-identical output.

## Strategy — A (patchelf-rewrite termux binaries)

We use [termux/termux-packages](https://github.com/termux/termux-packages)
to cross-compile `mosh-client` for each Android ABI, then use `patchelf` to
rename every shared library the binary depends on so the output survives the
Android APK installer's `lib*.so` extraction filter.

Why this strategy wins over a fresh NDK build from source:

- `mosh-client` 1.4.0 pulls `libprotobuf` → `libabsl_*` → ~40 shared libs.
  Building a static mosh-client against a static protobuf requires
  `-DBUILD_SHARED_LIBS=OFF` all the way through abseil, which does not
  currently have a green path with NDK r26+ (absl requires C++17 but uses
  thread-local storage patterns that crash with Android's libc `__thread`
  handling on API levels <=28). Termux has the patches already upstream.
- Total binary+deps size is around 30 MB per ABI; compressed in the APK
  (deflate) this comes out to roughly ~12 MB per ABI.
- One reproducer script end-to-end, no build orchestration to maintain.

We looked at Strategy C (mosh 1.2.6, pre-protobuf) — it would reduce the
bundle to ~2 MB per ABI — but 1.2.6 is from 2017 and carries known
crypto bugs fixed in 1.3+. Not worth the size win given `termx`'s target
audience will install this as a single APK.

## Prerequisites

- Linux host (Ubuntu 24.04+ works)
- `docker` (the ghcr.io image the script pulls weighs ~8 GB)
- `patchelf` >= 0.18 (`apt-get install patchelf`)
- `dpkg` (for extracting `.deb` archives)
- `~20 GB` free disk (termux-packages build cache)

## Versions used for the checked-in binaries

| Component          | Version                                     |
| ------------------ | ------------------------------------------- |
| `termux-packages`  | commit `6fdea17` (tip of `master`, 2026-04) |
| `mosh`             | 1.4.0-16 (TERMUX_PKG_REVISION=16)           |
| `protobuf`         | 33.1-1                                      |
| `abseil-cpp`       | 20250814.1                                  |
| `openssl`          | 3.6.2                                       |
| `ncurses`          | 6.6.20260307+really6.5.20250830             |
| `zlib`             | 1.3.2                                       |
| `libc++`           | NDK r29 prebuilt                            |
| `libandroid-support`| 29-1                                       |
| Android NDK (via termux) | r29 (host toolchain in container)    |
| `patchelf`         | 0.18.0                                      |

`dev.kuch.termx` is injected as the Termux prefix so the binary's embedded
RUNPATH (if any) targets our data dir, not the standard `com.termux`.

## Reproduce

```bash
# 1. Clone termux-packages and patch the prefix
git clone https://github.com/termux/termux-packages /tmp/termux-packages
cd /tmp/termux-packages
git checkout 6fdea17
sed -i 's|com.termux|dev.kuch.termx|g' scripts/properties.sh

# 2. Build mosh for each ABI. Each of these takes 45-90 min on a 4-core VM
#    because the full dep chain (perl, openssh, krb5, abseil, protobuf, ...)
#    is rebuilt from source on the first run. Cache reuses: none (different prefix).
./scripts/run-docker.sh ./build-package.sh -a aarch64 mosh 2>&1 | tee /tmp/mosh-arm64-build.log
./scripts/run-docker.sh ./build-package.sh -a arm     mosh 2>&1 | tee /tmp/mosh-arm-build.log

# 3. Copy all per-ABI .debs out of the container
mkdir -p /tmp/mosh-bundle/{aarch64,arm}/debs
docker cp termux-package-builder:/home/builder/termux-packages/output/. /tmp/mosh-bundle/debs-all/
mv /tmp/mosh-bundle/debs-all/*_aarch64.deb /tmp/mosh-bundle/aarch64/debs/
mv /tmp/mosh-bundle/debs-all/*_arm.deb     /tmp/mosh-bundle/arm/debs/

# 4. Extract each .deb per package and build a flat pool of .so files per ABI.
#    The bundler only cares about mosh, libc++, libandroid-support, openssl,
#    ncurses, zlib, abseil-cpp, and libprotobuf — the rest of the deps chain
#    (perl, krb5, etc.) is only needed to satisfy termux-packages' own build
#    dependencies and does not show up in mosh-client's DT_NEEDED closure.
for abi in aarch64 arm; do
  mkdir -p /tmp/mosh-bundle/$abi/extracted /tmp/mosh-bundle/$abi/pool
  for pkg in mosh abseil-cpp libandroid-support libc++ libprotobuf ncurses openssl zlib; do
    mkdir -p /tmp/mosh-bundle/$abi/extracted/$pkg
    deb=$(ls /tmp/mosh-bundle/$abi/debs/${pkg}_*_${abi}.deb | head -1)
    dpkg-deb -x "$deb" /tmp/mosh-bundle/$abi/extracted/$pkg
  done
  # Flatten every .so (deref symlinks) into the pool
  find /tmp/mosh-bundle/$abi/extracted -type f -name "*.so*" \
    -exec cp -Lf {} /tmp/mosh-bundle/$abi/pool/ \;
  find /tmp/mosh-bundle/$abi/extracted -type l -name "*.so*" | while read l; do
    target=$(readlink -f "$l")
    [ -f "$target" ] && cp -Lf "$target" "/tmp/mosh-bundle/$abi/pool/$(basename "$l")"
  done
done

# 5. Run the bundler (computes closure, renames, patches DT_NEEDED).
REPO=/home/ubuntu/Workspaces/termx
BUNDLE=$REPO/android/libs/ssh-native/scripts/build-mosh-jni-bundle.sh
bash $BUNDLE aarch64 /tmp/mosh-bundle/aarch64/pool \
  /tmp/mosh-bundle/aarch64/extracted/mosh/data/data/dev.kuch.termx/files/usr/bin/mosh-client \
  $REPO/android/libs/ssh-native/src/main/jniLibs/arm64-v8a
bash $BUNDLE arm     /tmp/mosh-bundle/arm/pool \
  /tmp/mosh-bundle/arm/extracted/mosh/data/data/dev.kuch.termx/files/usr/bin/mosh-client \
  $REPO/android/libs/ssh-native/src/main/jniLibs/armeabi-v7a
```

The bundler lives at `scripts/build-mosh-jni-bundle.sh` — see the header of
that script for its signature and exit codes.

## What the bundler does

1. `dpkg-deb -x` extracts the `mosh` + all dep `.deb`s.
2. Starts from `mosh-client` and walks the dynamic-section closure
   (`readelf -d | grep NEEDED`). Skips Android system libs
   (`libc.so`, `libdl.so`, `libm.so`, `liblog.so`, `libandroid.so`).
3. For every closure member, produces a new filename of the form
   `lib<base-of-SONAME>_mosh.so`, dropping the version suffix that
   Android's installer would otherwise refuse to extract (e.g.
   `libcrypto.so.3` -> `libcrypto_mosh.so`).
4. Uses `patchelf --set-soname` + `patchelf --replace-needed` to rewrite
   every `DT_NEEDED` and `DT_SONAME` entry in every closure member to
   point at the new names, so the Android linker can resolve the whole
   graph from `ApplicationInfo.nativeLibraryDir`.
5. `patchelf --remove-rpath` drops the embedded
   `RUNPATH=/data/data/.../usr/lib` that termux-packages emits — on
   Android the linker ignores it anyway, but clearing it keeps the
   binary portable.
6. Final sanity sweep: `readelf -d` against every file and assert that
   every `NEEDED` entry is either a known Android system lib or one of
   the `lib*_mosh.so` files we just wrote.

## Current bundle stats

### arm64-v8a
- 53 files total, ~30 MB on disk
- `libmoshclient.so`: 795 KB (mosh-client, stripped PIE)
- Biggest bundled deps:
  - `libprotobuf_mosh.so`: 10.2 MB
  - `libcrypto_mosh.so` (OpenSSL 3): 5.4 MB
  - `libc++_shared_mosh.so`: 1.5 MB
  - `libabsl_log_internal_message_mosh.so`: 1.1 MB
  - 44 other `libabsl_*_mosh.so`: ~10 MB combined

### armeabi-v7a
- 53 files total, ~16 MB on disk (32-bit is roughly ~53 % the volume of arm64).
- `libmoshclient.so`: 281 KB
- Biggest bundled deps:
  - `libprotobuf_mosh.so`: 8.8 MB
  - `libcrypto_mosh.so` (OpenSSL 3): 2.8 MB
  - `libc++_shared_mosh.so`: 1.0 MB
  - `libabsl_log_internal_message_mosh.so`: 278 KB
  - 44 other `libabsl_*_mosh.so`: ~1.8 MB combined
- SONAME set is identical to arm64-v8a (every bundled file has a matching
  counterpart), so the `DT_NEEDED` closure resolves with the same filenames
  across both ABIs and no ABI-specific loader logic is needed.

APK compressed size impact (deflate): roughly +12 MB for arm64-v8a and
+7 MB for armeabi-v7a, for a combined APK-size overhead of ~19 MB when both
ABIs are bundled.

## Verifying a rebuild

After regenerating, run:

```bash
for abi in arm64-v8a armeabi-v7a; do
  echo "=== $abi ==="
  for bin in android/libs/ssh-native/src/main/jniLibs/$abi/lib*.so; do
    # every NEEDED must be a system lib or a bundled lib*_mosh.so
    readelf -d "$bin" 2>/dev/null \
      | awk '/NEEDED/{gsub(/[\[\]]/,"",$5); print $5}' \
      | while read dep; do
        case "$dep" in
          libc.so|libdl.so|libm.so|liblog.so|libandroid.so) ;;
          lib*_mosh.so|libmoshclient.so) ;;
          *) echo "UNRESOLVED: $bin -> $dep"; exit 1 ;;
        esac
      done
  done
done
```

No `UNRESOLVED:` line should print. Also run `file` and confirm each file
identifies as an `ELF ... ARM aarch64` (resp. `ARM, EABI5 version 1`)
`shared object` / `stripped`.
