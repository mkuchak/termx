#!/usr/bin/env bash
set -euo pipefail
[ $# -eq 1 ] || { echo "usage: $0 <version>   # e.g. 0.1.0"; exit 1; }
VERSION="$1"
TAG="termxd-v${VERSION}"
cd "$(git rev-parse --show-toplevel)"
[ -z "$(git status --porcelain)" ] || { echo "working tree dirty"; exit 1; }
[ "$(git branch --show-current)" = "main" ] || { echo "must be on main"; exit 1; }
git tag -a "$TAG" -m "Release $TAG"
git push origin "$TAG"
echo "Pushed $TAG — GitHub Actions will build + release."
