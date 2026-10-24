#!/usr/bin/env bash
# Builds the wireproxy binary for the WARP tunnel from the pinned upstream source, with GOOS=android, and places it
# into the jniLibs directory under its Android library name, so the APK extracts it into the native library directory
# where executing it is allowed.
#
# Why build from source instead of taking the release asset: the published wireproxy binaries are built with
# GOOS=linux, and that build never survives Android's app sandbox. wireguard-go's sticky-socket support
# (conn/sticky_linux.go, device/sticky_linux.go) is compiled in only when the binary is GOOS=linux; on the first
# device Up() it opens a NETLINK_ROUTE socket and binds it with an RTMGRP subscription. Android's SELinux policy for
# untrusted apps denies that bind, the raw errno ("permission denied") propagates unwrapped to wireproxy's
# log.Fatal, and the tunnel dies at startup on every device ("Туннель завершился при запуске / permission denied").
# The GOOS=android build is exactly what the official WireGuard Android app ships: StdNetSupportsStickySockets is
# false there, the netlink route monitor is skipped entirely, and the socket controls skip IP_PKTINFO at runtime.
# Verified by symbol table: createNetlinkRouteSocket exists in the linux build and is absent from the android build.
#
# Usage: runtime/fetch-wireproxy.sh <out-dir>   (e.g. out/jniLibs/arm64-v8a)
# Needs Go 1.21+ on PATH (the CI installs it with actions/setup-go).
set -euo pipefail

dest="${1:?usage: fetch-wireproxy.sh <out-dir>}"
version="${WIREPROXY_VERSION:-1.0.7}"
repo="${WIREPROXY_REPO:-pufferffish/wireproxy}"

# v1.0.7 is the last release before wireproxy started sandboxing itself with Landlock. From v1.0.8 on, main() calls
# landlock_create_ruleset (syscall 444) before anything else - even for --version - and Android's app seccomp filter
# answers an unknown syscall with SIGSYS instead of ENOSYS, so the Go runtime dies with a register dump right at
# start. Same family as golang/go#70508. Neither a flag nor the config turns the sandbox off, so newer releases
# cannot be used here until that changes. The Landlock grep below keeps this true even if the pin moves.
# sha256 of https://github.com/${repo}/archive/refs/tags/v1.0.7.tar.gz (identical for the windtf fork's v1.0.7).
expected_sha="eeaccd699de7583276c669e06f0d37e02109adcd72be8e5f1d9265b59263bc77"

command -v go >/dev/null 2>&1 || { echo "fetch-wireproxy: go is not on PATH (need Go 1.21+ to build wireproxy)" >&2; exit 1; }

mkdir -p "$dest"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

url="https://github.com/${repo}/archive/refs/tags/v${version}.tar.gz"

echo "fetch-wireproxy: $url"
curl -fsSL --retry 3 --max-time 300 -o "$work/src.tar.gz" "$url"
echo "$expected_sha  $work/src.tar.gz" | sha256sum -c - >/dev/null || { echo "wireproxy source checksum mismatch" >&2; exit 1; }

tar -xzf "$work/src.tar.gz" -C "$work" --strip-components=1

# A build with the Landlock sandbox crashes on Android at start (see above); refuse to ship one, whatever the pin says.
if grep -qa "landlock" "$work/cmd/wireproxy"/*.go "$work"/*.go; then
  echo "wireproxy ${version} contains the Landlock sandbox and crashes on Android; use v1.0.7 or build without it" >&2
  exit 1
fi

# CGO stays off: a pure Go binary cross-compiles without the NDK and runs as a static PIE from the app's lib dir.
# GOTOOLCHAIN=local keeps the build on the installed toolchain (go.mod only needs 1.21).
GOTOOLCHAIN=local CGO_ENABLED=0 GOOS=android GOARCH=arm64 \
  go build -C "$work" -trimpath -ldflags="-s -w" -o "$work/wireproxy" ./cmd/wireproxy

# The machine check the fetch-proot.sh contract uses: has to be an aarch64 binary for Android.
file "$work/wireproxy" | grep -Eqi 'ARM aarch64|aarch64' || { echo "wireproxy is not an aarch64 binary" >&2; file "$work/wireproxy" >&2; exit 1; }

# Belt and suspenders for the release-asset failure mode: no netlink route monitor in the shipped binary.
if go tool nm "$work/wireproxy" 2>/dev/null | grep -q "createNetlinkRouteSocket"; then
  echo "wireproxy build contains the netlink route monitor; it dies on Android with permission denied" >&2
  exit 1
fi

install -m 0755 "$work/wireproxy" "$dest/libwireproxy.so"
echo "fetch-wireproxy: installed $dest/libwireproxy.so ($(wc -c < "$dest/libwireproxy.so") bytes)"
