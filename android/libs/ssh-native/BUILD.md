# BUILD.md — reproducing `jniLibs/<abi>/libmoshclient.so`

`jniLibs/arm64-v8a/libmoshclient.so` and `jniLibs/armeabi-v7a/libmoshclient.so`
are generated, not hand-written. Each is a **single statically-linked
mosh-client PIE** with no dynamic dependency except bionic system libs.

## Why static-single-binary (and not the old Termux bundle)

The previous approach (`build-mosh-jni-bundle.sh`, **removed** — see git
history) shipped ~53 Termux-built shared objects per ABI (mosh-client + ~40
abseil `.so` + protobuf/openssl/ncurses/libc++), `patchelf`-renamed to a
`_mosh` SONAME so the APK installer would extract them. **That bundle SIGSEGV'd
pre-`main()` in the dynamic linker on real devices** (ODR / static-init across
the abseil `.so` soup, plus Termux-runtime assumptions) — mosh never actually
ran; it always fell back to SSH. See PROJECT_KNOWLEDGE **gotcha #32** for the
full diagnosis (the v1.7.4–v1.7.6 saga).

The fix is to remove all dynamic C++ loading. `build-mosh-static.sh`
cross-compiles each dependency **static** with one consistent NDK toolchain and
links them — plus `libc++_static` — into one binary. The only `DT_NEEDED` are
`libc/libdl/libm/liblog/libz`, all bionic system libs. No `.so` soup, no ODR
surface, nothing to misload before `main()`. This is how JuiceSSH/Sonelli ship
mosh. Bonus: ~8 MB total (both ABIs) vs the old ~46 MB.

The key simplification vs the old build: **protobuf 3.21.12** — the last C++
release *before* protobuf hard-depends on abseil. mosh only needs protobuf for
its wire messages, so dropping to 3.21 deletes the entire abseil dependency.

## Versions

| Component | Version | Notes |
|-----------|---------|-------|
| mosh      | 1.4.0   | release tarball |
| OpenSSL   | 3.0.15  | static `libcrypto`, `no-shared` |
| protobuf  | 3.21.12 | last pre-abseil C++ release; static |
| ncurses   | 6.4     | `--enable-widec --enable-static` |
| libc++    | NDK r27 | `-static-libstdc++` |
| Android NDK | r27 (27.0.12077973) | clang 18 / lld |
| host protoc | 3.21.12 | MUST match protobuf (Ubuntu 24.04: `apt install protobuf-compiler`) |

Target API 28 (== app `minSdk`). bionic ≥26 already provides `nl_langinfo`, so
the usual mosh-on-Android locale patch is **not** needed.

## Prerequisites

- Linux host (Ubuntu 24.04+); Android NDK r27+; the SDK `cmake` (3.22+)
- `protoc` 3.21.12 on `PATH` (`apt install protobuf-compiler` on noble)
- `curl`, `make`, a host C/C++ toolchain (`build-essential`) for ncurses' build tools
- ~2 GB scratch disk

## Reproduce

```bash
export ANDROID_NDK_ROOT=~/Android/Sdk/ndk/27.0.12077973
export ANDROID_SDK_CMAKE=~/Android/Sdk/cmake/3.22.1/bin/cmake   # or any cmake 3.22+
export MOSH_BUILD_DIR=/tmp/mosh-build

cd android/libs/ssh-native
./scripts/build-mosh-static.sh arm64-v8a
./scripts/build-mosh-static.sh armeabi-v7a

# install into the repo
cp "$MOSH_BUILD_DIR/out/arm64-v8a/final/libmoshclient.so"   src/main/jniLibs/arm64-v8a/
cp "$MOSH_BUILD_DIR/out/armeabi-v7a/final/libmoshclient.so" src/main/jniLibs/armeabi-v7a/
```

The script is resumable (per-stage `.done` markers under `$MOSH_BUILD_DIR/markers`).

## Verifying a rebuild

The script prints a VALIDATE block. Independently:

```bash
NDK=$ANDROID_NDK_ROOT; RE=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf
for abi in arm64-v8a armeabi-v7a; do
  bin=src/main/jniLibs/$abi/libmoshclient.so
  echo "=== $abi ==="
  $RE -hW "$bin" | grep -E "Type:|Machine:"          # DYN (PIE); AArch64 / ARM
  $RE -dW "$bin" | grep NEEDED                        # ONLY libc/libdl/libm/liblog/libz
  $RE -lW "$bin" | awk '/LOAD/{print $NF}' | sort -u  # 0x4000 (16 KB-page safe)
done
```

Every `NEEDED` must be a bionic system lib. `GNU_STACK` must be `RW` (not RWE)
and there must be no `TEXTREL`. The on-device acceptance test is checklist item
16 — connect and confirm mosh comes up (the v1.7.6 diagnostics dialog should
report `exit=0`, not the old `139`/SIGSEGV).
