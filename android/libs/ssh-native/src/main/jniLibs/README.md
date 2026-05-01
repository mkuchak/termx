# jniLibs — mosh-client + bundled runtime deps

This directory contains the cross-compiled `mosh-client` binary (renamed to
`libmoshclient.so` so Android's installer treats it as a native library and
extracts it to `ApplicationInfo.nativeLibraryDir`) along with every shared
library `mosh-client` depends on at runtime.

## Layout

```
jniLibs/arm64-v8a/
  libmoshclient.so             <-- mosh-client executable (PIE; chmod +x after exec)
  libcrypto_mosh.so            <-- OpenSSL 3.x (renamed from libcrypto.so.3)
  libprotobuf_mosh.so          <-- Protocol Buffers
  libc++_shared_mosh.so        <-- NDK libc++
  libncursesw_mosh.so          <-- ncurses (renamed from libncursesw.so.6)
  libz_mosh.so                 <-- zlib (renamed from libz.so.1)
  libandroid-support_mosh.so   <-- Termux libandroid-support
  libutf8_validity_mosh.so     <-- protobuf runtime dep
  libabsl_*_mosh.so            <-- 45 Abseil libs (dragged in by protobuf 22+)
jniLibs/armeabi-v7a/
  (same file set, 32-bit ARM)
```

All files are plain `lib<name>_mosh.so` so they survive Android's install-time
`lib*.so` extraction filter. SONAMEs and `DT_NEEDED` entries were rewritten
with `patchelf` to use the new names (original names like `libcrypto.so.3`
would be stripped by the packager because of the version suffix).

## Runtime invocation (Phase 3 MoshClient.kt wrapper, issue #27)

Because `libmoshclient.so` is marked executable by the APK installer when it
lands in `nativeLibraryDir`, it can be spawned directly:

```kotlin
val nativeDir = context.applicationInfo.nativeLibraryDir
val moshClient = File(nativeDir, "libmoshclient.so")
val pb = ProcessBuilder(moshClient.absolutePath, "-#", mosey, ip, port)
pb.environment()["LD_LIBRARY_PATH"] = nativeDir
pb.environment()["TERMINFO"] = "${context.filesDir}/terminfo"   // see note
pb.environment()["MOSH_KEY"] = key                               // from mosh-server
pb.redirectErrorStream(true)
val proc = pb.start()
```

Important:

- **`LD_LIBRARY_PATH` must be set to `nativeLibraryDir`** so the dynamic linker
  can locate `libcrypto_mosh.so`, `libprotobuf_mosh.so`, etc. Without it,
  mosh-client fails to start with `CANNOT LINK EXECUTABLE ... cannot locate ...`.
- **`extractNativeLibs="true"`** is set in `AndroidManifest.xml`. With the
  default (`false` on AGP 4.2+ App Bundle output) libraries stay inside the
  APK and cannot be `exec()`'d.
- **`TERMINFO`**: ncurses needs a terminfo DB at runtime. Mosh uses it for
  the local echo predictor. Drop a minimal `xterm-256color` terminfo blob
  under `files/terminfo/x/xterm-256color` (see issue #27 checklist). If the
  DB is missing, mosh still runs but terminal emulation is degraded.
- **`MOSH_KEY`** comes from `mosh-server new -s -c 256` running on the VPS
  (our `termxd` already spawns mosh-server on demand in Phase 3).
- Don't copy `libmoshclient.so` to a different location before running —
  the installer marks it executable only inside `nativeLibraryDir`.

## Deps rewritten

Each bundled library's `SONAME` and every outgoing `DT_NEEDED` reference was
patched to the `lib<name>_mosh.so` form. The only unresolved `DT_NEEDED`
entries left in the closure are Android system libraries that the platform
linker always finds: `libc.so`, `libdl.so`, `libm.so`, `liblog.so`.

See `../BUILD.md` for the reproducer.
