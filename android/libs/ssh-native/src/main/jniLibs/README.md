# jniLibs — the mosh-client binary

Each ABI directory contains exactly **one** file: the cross-compiled
`mosh-client`, renamed to `libmoshclient.so` so Android's installer treats it as
a native library and extracts it to `ApplicationInfo.nativeLibraryDir` (where it
can be `exec()`'d).

```
jniLibs/arm64-v8a/libmoshclient.so      <-- single static PIE (AArch64)
jniLibs/armeabi-v7a/libmoshclient.so    <-- single static PIE (ARM)
```

## It's a single statically-linked binary

OpenSSL, protobuf, ncursesw and libc++ are linked **statically** into the
binary. Its only `DT_NEEDED` are bionic system libs (`libc/libdl/libm/liblog/
libz`), which the platform linker always resolves. There are **no** bundled
`.so` dependencies anymore.

This replaced an earlier ~53-`.so` Termux bundle (mosh-client + ~40 abseil libs,
patchelf-renamed to `_mosh`) that **SIGSEGV'd before `main()`** in the dynamic
linker on real devices — mosh never ran. See `../BUILD.md` and PROJECT_KNOWLEDGE
**gotcha #32** for the diagnosis, and `../scripts/build-mosh-static.sh` for the
reproducer.

## Runtime invocation (`MoshClientImpl.spawnMoshClient`)

```kotlin
val nativeDir = context.applicationInfo.nativeLibraryDir
val moshClient = File(nativeDir, "libmoshclient.so")
// spawned under a pty (NativePty); env: MOSH_KEY (from mosh-server), TERMINFO,
// TERM, LANG/LC_ALL=en_US.UTF-8. argv = [path, host, port].
```

Notes:

- **`extractNativeLibs="true"`** (in `AndroidManifest.xml`) is required so the
  binary lands on disk in `nativeLibraryDir` and can be `exec()`'d.
- **`TERMINFO`** must point at the bundled terminfo tree (`TerminfoInstaller`);
  the static ncurses reads it for terminal capabilities.
- **`MOSH_KEY`** comes from `mosh-server new -s -c 256` on the VPS.
- `LD_LIBRARY_PATH` is no longer needed (the binary has no foreign deps), though
  the loader still sets it harmlessly.
- Don't relocate the binary — the installer marks it executable only inside
  `nativeLibraryDir`.
