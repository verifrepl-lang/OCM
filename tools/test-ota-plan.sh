#!/usr/bin/env bash
# Exercises runtime/ota-plan.sh against a throwaway upstream repository and a stand-in for the gh command, so every
# gate (newer, cooling-down, moved tag, risky diff, major change, draft already waiting, bad tags) is checked offline.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

pass=0
fail=0
ok() { pass=$((pass + 1)); }
bad() { fail=$((fail + 1)); echo "FAIL: $*"; }
expect() { # expect <name> <actual> <expected>
  if [ "$2" = "$3" ]; then ok; else bad "$1: expected '$3', got '$2'"; fi
}

export GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@t GIT_COMMITTER_NAME=t GIT_COMMITTER_EMAIL=t@t

# A fake upstream
up="$work/upstream.git"
src="$work/src"
git init -q "$src"
cd "$src"
mkdir -p packages/cli/src packages/cli/script packages/ui/src
echo '{"name":"root","trustedDependencies":["esbuild"]}' >package.json
printf '{\n  "packages": {\n    "a": ["a@1"],\n    "b": ["b@1"]\n  }\n}\n' | sed 's/^  "packages": {/  "packages": {/' >bun.lock
printf '    "a": ["a@1"],\n    "b": ["b@1"],\n' >bun.lock
echo 'const url = "https://opencode.ai/x"' >packages/cli/src/a.ts
echo 'build v1' >packages/cli/script/build-node.ts
git add -A && git commit -q -m v1.0.0 && git tag -a v1.0.0 -m v1.0.0
c100="$(git rev-parse HEAD)"

echo 'const url = "https://opencode.ai/x"; const more = 1' >packages/cli/src/a.ts
git commit -qam v1.0.1 && git tag v1.0.1
c101="$(git rev-parse HEAD)"

echo 'build v2 changed' >packages/cli/script/build-node.ts
echo '{"name":"root","trustedDependencies":["esbuild","evil-pkg"],"scripts":{"postinstall":"node x.js"}}' >package.json
printf '    "a": ["a@1"],\n    "b": ["b@1"],\n    "c": ["c@1"],\n' >bun.lock
for i in $(seq 1 10); do echo "fetch('https://host$i.example.net/x')" >>packages/cli/src/a.ts; done
git commit -qam v1.0.2 && git tag v1.0.2

echo 'major bump' >>packages/ui/src/x.ts
git add -A && git commit -qm v2.0.0 && git tag v2.0.0
git clone -q --bare "$src" "$up"
cd "$root"

# A stand-in for gh
shim="$work/shim"
mkdir -p "$shim/bin"
cat >"$shim/bin/gh" <<'SHIM'
#!/usr/bin/env bash
s="$SHIM_DIR"
case "$1 $2" in
  "api repos/"*/releases/latest|"api repos/"*/releases/tags/*) cat "$s/latest.json" || exit 1 ;;
  "api repos/"*/commits/*) echo "${SHIM_VERIFIED:-true}" ;;
  "release list") cat "$s/releases.json" ;;
  "release download")
    tag="$3"; out=""
    while [ $# -gt 0 ]; do [ "$1" = "-O" ] && out="$2"; shift; done
    [ -f "$s/manifest-$tag.json" ] || exit 1
    cp "$s/manifest-$tag.json" "$out" ;;
  *) echo "unexpected gh call: $*" >&2; exit 1 ;;
esac
SHIM
chmod +x "$shim/bin/gh"
export SHIM_DIR="$shim" PATH="$shim/bin:$PATH"

ago() { date -u -d "$1 hours ago" +%Y-%m-%dT%H:%M:%SZ; }

latest() { # latest <tag> <hours-ago> [prerelease]
  CUR_TAG="$1"
  printf '{"tag_name":"%s","published_at":"%s","draft":false,"prerelease":%s}\n' "$1" "$(ago "$2")" "${3:-false}" >"$shim/latest.json"
}

last() { # last <version> <ref> <seq>   (a build this repository offered earlier)
  printf '[{"tagName":"ota-rt-%s-%s","isDraft":false,"createdAt":"2026-01-01T00:00:00Z"}]\n' "$1" "$3" >"$shim/releases.json"
  printf '{"seq":%s,"upstream":{"version":"%s","ref":"%s"}}\n' "$3" "$1" "$2" >"$shim/manifest-ota-rt-$1-$3.json"
}

run() { # run <env...> ; prints outputs into $work/out
  : >"$work/out"
  env UPSTREAM_REPO=o/r THIS_REPO=me/app UPSTREAM_GIT_URL="$up" GITHUB_OUTPUT="$work/out" PLAN_SUMMARY="$work/summary.md" \
    PLAN_RISKS="$work/risks.md" PINNED_MAJOR=1 MIN_AGE_HOURS=24 GH_TOKEN=x FORCE_REF="$CUR_TAG" "$@" bash "$root/runtime/ota-plan.sh" >"$work/log" 2>&1 || { bad "script failed: $(tail -3 "$work/log")"; return 0; }
}
get() { grep "^$1=" "$work/out" | tail -1 | cut -d= -f2-; }
risks() { cat "$work/risks.md"; }

# A. a calm update, old enough
latest v1.0.1 72; last 1.0.0 "$c100" 1000
run
expect "A build" "$(get build)" true
expect "A risky" "$(get risky)" false
expect "A version" "$(get version)" 1.0.1
expect "A commit" "$(get commit)" "$c101"
expect "A last" "$(get last_version)" 1.0.0

# B. too young; forcing overrides
latest v1.0.1 2; last 1.0.0 "$c100" 1000
run
expect "B build" "$(get build)" false
grep -q "waiting until" "$work/log" && ok || bad "B reason"
run FORCE=true
expect "B forced" "$(get build)" true

# C. risky diff
latest v1.0.2 72; last 1.0.1 "$c101" 1001
run
[ -z "${DEBUG_PLAN:-}" ] || cat "$work/log"
expect "C build" "$(get build)" true
expect "C risky" "$(get risky)" true
risks | grep -q "build chain" && ok || bad "C build chain not flagged"
risks | grep -q "install-time scripts" && ok || bad "C postinstall not flagged"
risks | grep -q "evil-pkg" && ok || bad "C trusted dependency not flagged"
risks | grep -q "new network hosts" && ok || bad "C hosts not flagged"

# D. major version
latest v2.0.0 72; last 1.0.2 "$(git --git-dir="$up" rev-parse v1.0.2^{commit})" 1002
run
expect "D risky" "$(get risky)" true
risks | grep -q "major version" && ok || bad "D major not flagged"

# E. already offered / older
latest v1.0.1 72; last 1.0.1 "$c101" 1003
run
expect "E same" "$(get build)" false
latest v1.0.1 72; last 1.0.2 "x" 1004
run
expect "E older" "$(get build)" false

# F. a moved tag
latest v1.0.1 72; last 1.0.0 "$(printf 'f%.0s' $(seq 40))" 1005
run
expect "F risky" "$(get risky)" true
risks | grep -q "moved the tag" && ok || bad "F moved tag not flagged"

# G. a draft for the same version is waiting
latest v1.0.1 72; last 1.0.0 "$c100" 1006
printf '[{"tagName":"ota-rt-1.0.0-1006","isDraft":false,"createdAt":"2026-01-01T00:00:00Z"},{"tagName":"ota-rt-1.0.1-1007","isDraft":true,"createdAt":"2026-01-02T00:00:00Z"}]\n' >"$shim/releases.json"
run
expect "G build" "$(get build)" false
grep -q "already waiting" "$work/log" && ok || bad "G reason"

# H. the first build ever
latest v1.0.1 72; echo '[]' >"$shim/releases.json"
run
expect "H build" "$(get build)" true
expect "H risky" "$(get risky)" false

# I. tags that are not versions, drafts, pre-releases
latest nightly 72; echo '[]' >"$shim/releases.json"
run
expect "I nightly" "$(get build)" false
run FORCE_REF=v9.9.9
expect "I unknown forced tag" "$(get build)" false

# K. an app build that bundles the same upstream version counts as already offered (the baseline is the highest seq)
latest v1.0.1 72
printf '[{"tagName":"ota-rt-1.0.0-1000","isDraft":false,"createdAt":"2026-01-01T00:00:00Z"},{"tagName":"ota-app-77","isDraft":false,"createdAt":"2026-01-03T00:00:00Z"}]\n' >"$shim/releases.json"
printf '{"seq":1000,"upstream":{"version":"1.0.0","ref":"%s"}}\n' "$c100" >"$shim/manifest-ota-rt-1.0.0-1000.json"
printf '{"seq":2000,"upstream":{"version":"1.0.1","ref":"%s"}}\n' "$c101" >"$shim/manifest-ota-app-77.json"
run
expect "K bundled version is not offered again" "$(get build)" false
expect "K baseline comes from the app build" "$(get last_version)" ""
grep -q "already offered" "$work/log" && ok || bad "K reason"

# an app build without a recorded upstream version (written as 0) is not a baseline
printf '{"seq":3000,"upstream":{"version":"0","ref":""}}\n' >"$shim/manifest-ota-app-77.json"
run
expect "K2 app build without a version is ignored" "$(get last_version)" "1.0.0"

# J. a tag chosen by hand
latest v1.0.1 72; last 1.0.0 "$c100" 1000
run FORCE_REF=v1.0.2 FORCE=true
expect "J tag" "$(get tag)" v1.0.2
expect "J commit" "$(get commit)" "$(git --git-dir="$up" rev-parse v1.0.2^{commit})"

# R. resolve-upstream.sh
resolve() { env UPSTREAM_REPO=o/r UPSTREAM_GIT_URL="$up" GITHUB_OUTPUT="$work/out" PINNED_MAJOR=1 bash "$root/runtime/resolve-upstream.sh" "$@" >"$work/log" 2>&1; }
: >"$work/out"
resolve v1.0.1 && ok || bad "R tag failed: $(tail -2 "$work/log")"
expect "R tag" "$(get tag)" v1.0.1
expect "R commit" "$(get commit)" "$c101"
expect "R version" "$(get version)" 1.0.1
: >"$work/out"
resolve v1.0.0 && ok || bad "R annotated tag failed"
expect "R annotated tag is peeled to its commit" "$(get commit)" "$c100"
resolve "../x" && bad "R accepted a bad tag" || ok
resolve v7.7.7 && bad "R accepted an unknown tag" || ok

# newest tag of the pinned line, regardless of what GitHub calls latest
latest v1.0.0 72; echo '[]' >"$shim/releases.json"
run FORCE_REF=
expect "M major 1 picks newest 1.x" "$(get tag)" v1.0.2
run FORCE_REF= PINNED_MAJOR=2
expect "M major 2 picks 2.x" "$(get tag)" v2.0.0
resolve2() { env UPSTREAM_REPO=o/r UPSTREAM_GIT_URL="$up" GITHUB_OUTPUT="$work/out" PINNED_MAJOR=1 bash "$root/runtime/resolve-upstream.sh" "$@" >"$work/log" 2>&1; }
: >"$work/out"; resolve2 && ok || bad "M resolve without tag"
expect "M resolve newest" "$(get tag)" v1.0.2

# L. the pinned line changed on purpose (the app moved from the 2.x line to 1.x): the cross-line "older" comparison
# must not refuse the build, and the cross-line diff review is skipped with a note instead of drowning in findings.
latest v1.0.2 72; last 2.0.0 "$(git --git-dir="$up" rev-parse v2.0.0^{commit})" 1008
run
expect "L line switch builds" "$(get build)" true
expect "L line switch not risky" "$(get risky)" false
grep -q "upstream line changed" "$work/summary.md" && ok || bad "L line switch not noted"
grep -q "diff review is skipped" "$work/summary.md" && ok || bad "L skipped diff not noted"
grep -qi "older than" "$work/summary.md" && bad "L refused as older" || ok

# within the same line the old gates keep working (a 1.x tag older than the offered 1.x is still refused)
latest v1.0.1 72; last 1.0.2 "$c101" 1009
run
expect "L2 older within the line still refused" "$(get build)" false

echo "ota plan: passed $pass, failed $fail"
[ "$fail" -eq 0 ]
