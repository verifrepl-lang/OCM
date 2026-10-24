#!/usr/bin/env bash
# Builds the OpenCode server for linux-arm64 from the upstream sources, the same way upstream's own release job does it.
#
# Two upstream layouts are supported, because the project moved its build between release lines:
#   - the 1.x line compiles a Bun single binary from packages/opencode (the web UI is embedded along the way);
#   - the 2.x line builds a Node single executable from packages/cli (a Node SEA that carries the web UI inside).
#
# Usage: runtime/build-opencode.sh <upstream-ref> <out-dir>
#   <upstream-ref>  a tag such as v1.19.4 or v2.0.33, a branch or a commit
#   <out-dir>       receives `opencode` (the executable) and `build-info.json`
#
# Needs an arm64 Linux host, bun (the version in upstream's package.json) and, for the 2.x line only, Node (upstream
# pins its version; the build downloads it when the one on PATH differs). Environment: UPSTREAM_REPO,
# UPSTREAM_VERSION (default: the ref without a leading "v"), SRC_DIR (an existing checkout to build instead of
# cloning).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=versions.env
. "$here/versions.env"

ref="${1:?usage: build-opencode.sh <upstream-ref> <out-dir>}"
mkdir -p "${2:?usage: build-opencode.sh <upstream-ref> <out-dir>}"
out="$(cd "$2" && pwd)"
repo="${UPSTREAM_REPO}"
src="${SRC_DIR:-$PWD/upstream-src}"
version="${UPSTREAM_VERSION:-${ref#v}}"

if [ "$(uname -m)" != "aarch64" ]; then
  echo "This build has to run on an arm64 Linux machine (it runs the result as a smoke test). Found: $(uname -m)" >&2
  exit 1
fi

if ! [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-+.][0-9A-Za-z.+-]+)?$ ]]; then
  echo "Cannot derive a version from '$ref'; set UPSTREAM_VERSION" >&2
  exit 1
fi

if [ ! -d "$src/.git" ]; then
  git init -q "$src"
  git -C "$src" remote add origin "https://github.com/$repo.git"
  git -C "$src" fetch -q --depth 1 origin "$ref"
  git -C "$src" checkout -q --detach FETCH_HEAD
fi

commit="$(git -C "$src" rev-parse HEAD)"
echo "Building $repo $version ($commit)"

cd "$src"

python3 -m pip install --quiet --break-system-packages setuptools 2>/dev/null || true
bun install --frozen-lockfile

export OPENCODE_CHANNEL=latest
export OPENCODE_VERSION="$version"

if [ -f packages/opencode/script/build.ts ]; then
  # The 1.x line: one Bun-compiled binary for the host (arm64), web UI embedded by the same script.
  bun packages/opencode/script/build.ts --single --skip-install
  built="$src/packages/opencode/dist/opencode-linux-arm64/bin/opencode"
elif [ -f packages/cli/script/build-node.ts ]; then
  # The 2.x line: a Node SEA built the way upstream's release job builds it.
  bun packages/cli/script/build-node.ts --app-archive-only --app-archive=.cache/app-archive.bin --skip-install
  bun packages/cli/script/build-node.ts --target=linux-arm64 --skip-install --outdir=dist/node --app-archive=.cache/app-archive.bin
  built="$src/packages/cli/dist/node/cli-node-linux-arm64/bin/opencode2-node"
else
  echo "Neither the 1.x (packages/opencode/script/build.ts) nor the 2.x (packages/cli/script/build-node.ts) build script is present" >&2
  exit 1
fi

[ -f "$built" ] || { echo "Upstream did not produce $built" >&2; exit 1; }

size="$(stat -c %s "$built")"
[ "$size" -ge 20000000 ] || { echo "The executable is only $size bytes; that is not a real build" >&2; exit 1; }

if command -v readelf >/dev/null 2>&1; then
  readelf -h "$built" | grep -q 'Machine:.*AArch64' || { echo "The executable is not aarch64" >&2; exit 1; }
fi

reported="$("$built" --version)"
case "$reported" in
  *"$version"*) ;;
  *) echo "The executable reports '$reported', expected $version" >&2; exit 1 ;;
esac

install -m 0755 "$built" "$out/opencode"

sha="$(sha256sum "$out/opencode" | cut -d' ' -f1)"
node_version="$(grep -oE 'NODE_VERSION = "[0-9.]+"' packages/cli/script/build-node.ts 2>/dev/null | grep -oE '[0-9.]+' | head -1 || true)"

jq -n \
  --arg repo "$repo" --arg version "$version" --arg ref "$ref" --arg commit "$commit" \
  --arg sha "$sha" --argjson size "$size" --arg node "$node_version" \
  '{repo: $repo, version: $version, ref: $ref, commit: $commit, sha256: $sha, size: $size, node: $node}' \
  >"$out/build-info.json"

echo "Built $out/opencode ($size bytes)"
