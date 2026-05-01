#!/usr/bin/env bash
# build-mosh-jni-bundle.sh
#
# Given a pool of .so files and a mosh-client binary cross-compiled by
# termux-packages, produce a self-contained jniLibs/<abi>/ directory:
#   - Recursively compute mosh-client's DT_NEEDED closure.
#   - Rename every closure member from SONAME "libcrypto.so.3" / "libXYZ.so"
#     to "libXYZ_mosh.so" (safe for Android's APK installer filter).
#   - Use patchelf to rewrite every DT_NEEDED and DT_SONAME accordingly.
#   - Rename mosh-client to libmoshclient.so.
#
# See ../BUILD.md for the full reproducer, version pins, and rationale.
#
# Usage:
#   build-mosh-jni-bundle.sh <abi-name> <pool-dir> <mosh-client-binary> <output-jni-dir>
#
# Where <pool-dir> contains every candidate .so for the ABI (typically
# produced by `dpkg-deb -x` over the output of a `termux-packages` build).

set -euo pipefail

ABI="$1"
POOL="$2"
MOSHCLIENT="$3"
OUT="$4"

# Android system libraries — always present, never bundle.
SYSLIBS="libc.so libdl.so libm.so liblog.so libandroid.so libstdc++.so"

mkdir -p "$OUT"
rm -f "$OUT"/lib*.so

STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT

cp "$MOSHCLIENT" "$STAGE/mosh-client.orig"

# -----------------------------------------------------------------------------
# 1. Compute the DT_NEEDED closure
# -----------------------------------------------------------------------------
compute_closure() {
  local start="$1"
  declare -A SEEN
  local QUEUE=("$start")
  while [ "${#QUEUE[@]}" -gt 0 ]; do
    local current="${QUEUE[0]}"
    QUEUE=("${QUEUE[@]:1}")
    [[ -z "$current" ]] && continue
    [[ -n "${SEEN[$current]:-}" ]] && continue
    SEEN[$current]=1
    echo "$current"
    local dep
    for dep in $(readelf -d "$current" 2>/dev/null \
                  | awk '/NEEDED/{gsub(/[\[\]]/,"",$5); print $5}'); do
      local skip=0 s
      for s in $SYSLIBS; do [[ "$dep" == "$s" ]] && skip=1 && break; done
      [[ $skip -eq 1 ]] && continue
      local match="$POOL/$dep"
      if [[ ! -f "$match" ]]; then
        match=$(ls "$POOL/$dep".* 2>/dev/null | head -1)
      fi
      if [[ -f "$match" ]]; then
        QUEUE+=("$match")
      else
        echo "MISSING: $dep (needed by $current)" >&2
        exit 1
      fi
    done
  done
}

compute_closure "$STAGE/mosh-client.orig" > "$STAGE/closure.txt"

# -----------------------------------------------------------------------------
# 2. Build the rename map: old SONAME / basename -> new "lib<base>_mosh.so"
# -----------------------------------------------------------------------------
declare -A RENAME
declare -A ORIG_PATH

get_soname() {
  readelf -d "$1" 2>/dev/null \
    | awk '/SONAME/{gsub(/[\[\]]/,"",$5); print $5}'
}

normalize() {
  # "libcrypto.so.3"     -> "libcrypto_mosh.so"
  # "libabsl_foo.so"     -> "libabsl_foo_mosh.so"
  # "libc++_shared.so"   -> "libc++_shared_mosh.so"
  local s="$1" stripped
  stripped="${s#lib}"
  stripped="${stripped%%.so*}"
  echo "lib${stripped}_mosh.so"
}

# mosh-client is special: it becomes libmoshclient.so
RENAME["mosh-client.orig"]="libmoshclient.so"
ORIG_PATH["libmoshclient.so"]="$STAGE/mosh-client.orig"

while read -r path; do
  [[ -z "$path" ]] && continue
  base=$(basename "$path")
  [[ "$base" == "mosh-client.orig" ]] && continue
  soname=$(get_soname "$path")
  [[ -z "$soname" ]] && soname="$base"
  newname=$(normalize "$soname")
  RENAME["$soname"]="$newname"
  RENAME["$base"]="$newname"   # transitive deps may reference basename
  ORIG_PATH["$newname"]="$path"
done < "$STAGE/closure.txt"

# -----------------------------------------------------------------------------
# 3. Copy and patch
# -----------------------------------------------------------------------------
for newname in "${!ORIG_PATH[@]}"; do
  src="${ORIG_PATH[$newname]}"
  dst="$OUT/$newname"
  cp "$src" "$dst"
  chmod u+w "$dst"
done

for newname in "${!ORIG_PATH[@]}"; do
  dst="$OUT/$newname"
  if [[ "$newname" != "libmoshclient.so" ]]; then
    patchelf --set-soname "$newname" "$dst"
  fi
  for dep in $(readelf -d "$dst" 2>/dev/null \
                | awk '/NEEDED/{gsub(/[\[\]]/,"",$5); print $5}'); do
    skip=0
    for s in $SYSLIBS; do [[ "$dep" == "$s" ]] && skip=1 && break; done
    [[ $skip -eq 1 ]] && continue
    if [[ -n "${RENAME[$dep]:-}" && "${RENAME[$dep]}" != "$dep" ]]; then
      patchelf --replace-needed "$dep" "${RENAME[$dep]}" "$dst"
    fi
  done
  patchelf --remove-rpath "$dst" 2>/dev/null || true
done

# -----------------------------------------------------------------------------
# 4. Final validation
# -----------------------------------------------------------------------------
echo "=== $ABI: validating closure ==="
rc=0
for f in "$OUT"/lib*.so; do
  while read dep; do
    case "$dep" in
      libc.so|libdl.so|libm.so|liblog.so|libandroid.so|libstdc++.so) ;;
      lib*_mosh.so|libmoshclient.so) ;;
      "") ;;
      *) echo "UNRESOLVED in $(basename "$f"): $dep"; rc=1 ;;
    esac
  done < <(readelf -d "$f" 2>/dev/null \
            | awk '/NEEDED/{gsub(/[\[\]]/,"",$5); print $5}')
done
[[ $rc -ne 0 ]] && exit 1

echo "=== $ABI: $(ls "$OUT" | wc -l) files, $(du -sh "$OUT" | cut -f1) total ==="
