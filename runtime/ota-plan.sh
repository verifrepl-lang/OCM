#!/usr/bin/env bash
# Decides whether the upstream project has a release worth offering to phones, and how much to trust it.
#
# It looks at upstream's latest published release (never drafts or pre-releases, unless a tag is forced), and applies
# these gates before anything is built or signed:
#   - the release must be newer than the last one this repository offered;
#   - it must have been public for MIN_AGE_HOURS (a cooling-down period in which a bad release usually gets noticed);
#   - the tag the last build came from must still point at the same commit (a moved tag is a warning sign);
#   - a diff against the last offered build is searched for risky changes: the build chain, install-time scripts,
#     new trusted dependencies, many new packages, many new network hosts, and a changed major version.
# A risky release is still built and tested, but is published as a draft that a person has to release by hand.
#
# Outputs (to $GITHUB_OUTPUT, or stdout): build, reason, tag, version, commit, risky, last_version, last_ref.
# Writes the human-readable findings to $PLAN_SUMMARY (default plan-summary.md) and the risky findings to $PLAN_RISKS.
#
# Environment: UPSTREAM_REPO, THIS_REPO, GH_TOKEN (for gh), PINNED_MAJOR, MIN_AGE_HOURS (24), FORCE_REF, FORCE (false),
#              UPSTREAM_GIT_URL (default https://github.com/$UPSTREAM_REPO.git).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=versions.env
. "$here/versions.env"

up="$UPSTREAM_REPO"
this="${THIS_REPO:?set THIS_REPO}"
git_url="${UPSTREAM_GIT_URL:-https://github.com/$up.git}"
min_age="${MIN_AGE_HOURS:-24}"
force_ref="${FORCE_REF:-}"
force="${FORCE:-false}"
pinned_major="${PINNED_MAJOR:-}"
out="${GITHUB_OUTPUT:-/dev/stdout}"
summary="${PLAN_SUMMARY:-plan-summary.md}"
risks_file="${PLAN_RISKS:-plan-risks.md}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

: >"$summary"
: >"$risks_file"

emit() { printf '%s=%s\n' "$1" "$2" >>"$out"; }
say() { printf '%s\n' "$*" | tee -a "$summary" >&2; }
risk() { say "RISK: $*"; printf -- '- %s\n' "$*" >>"$risks_file"; risky=true; }
note() { say "note: $*"; }

risky=false
tag="" version="" commit="" last_version="" last_ref="" last_tag=""

stop() { # stop <reason>
  emit build false
  emit reason "$1"
  emit risky false
  say "Nothing to build: $1"
  exit 0
}

semver_re='^v?[0-9]+\.[0-9]+\.[0-9]+([-.][0-9A-Za-z.]+)?$'

# Which upstream release
published_at=""
if [ -n "$force_ref" ]; then
  tag="$force_ref"
  say "Building the tag requested by hand: $tag"
else
  # GitHub's "latest release" can be an older line (upstream keeps publishing 1.x next to 2.x), so the newest plain tag of
  # the line this app is built for is used instead.
  [ -n "$pinned_major" ] || stop "PINNED_MAJOR is not set"
  tag="$(git ls-remote --tags "$git_url" "refs/tags/v$pinned_major.*" 2>/dev/null | awk '{print $2}' | grep -v '\^{}$' \
    | sed 's#refs/tags/##' | grep -E "^v$pinned_major\.[0-9]+\.[0-9]+$" | sort -V | tail -1 || true)"
  [ -n "$tag" ] || stop "no v$pinned_major.x tag found in $up"
fi

if [[ "$tag" =~ ^v?[0-9]+\.[0-9]+\.[0-9]+([-.][0-9A-Za-z.]+)?$ ]] && gh api "repos/$up/releases/tags/$tag" >"$work/latest.json" 2>/dev/null; then
  [ "$(jq -r '.draft // false' "$work/latest.json")" = false ] || stop "$tag is a draft release"
  published_at="$(jq -r '.published_at // empty' "$work/latest.json")"
fi

[[ "$tag" =~ $semver_re ]] || stop "tag '$tag' is not a plain version"
version="${tag#v}"
say "Upstream release: $tag"

# The commit behind the tag
resolve() { # resolve <tag> -> commit of the tag (peeled when the tag is annotated)
  local refs peeled direct
  refs="$(git ls-remote "$git_url" "refs/tags/$1" "refs/tags/$1^{}" 2>/dev/null || true)"
  peeled="$(printf '%s\n' "$refs" | awk -v t="refs/tags/$1^{}" '$2 == t {print $1}')"
  direct="$(printf '%s\n' "$refs" | awk -v t="refs/tags/$1" '$2 == t {print $1}')"
  printf '%s\n' "${peeled:-$direct}"
}

commit="$(resolve "$tag" || true)"
[[ "$commit" =~ ^[0-9a-f]{40}$ ]] || stop "could not resolve $tag to a commit"
say "Commit: $commit"

# The last build this repository offered
gh release list --repo "$this" --limit 100 --json tagName,isDraft,createdAt >"$work/releases.json" 2>/dev/null || echo '[]' >"$work/releases.json"

# A draft for this very version is already waiting for a person: do not queue another.
if jq -e --arg v "ota-rt-$version-" '.[] | select(.isDraft and (.tagName | startswith($v)))' "$work/releases.json" >/dev/null 2>&1 && [ "$force" != true ]; then
  stop "a draft for $version is already waiting for review"
fi

# The baseline is the published build with the highest seq among the newest ota-* releases. Server builds and app builds
# both count: an app build carries a server build too, and recording it here keeps the same version from being offered twice.
best_seq=-1
for tag_name in $(jq -r '[.[] | select((.isDraft | not) and (.tagName | startswith("ota-")))] | sort_by(.createdAt) | reverse | .[:8][] | .tagName' "$work/releases.json" 2>/dev/null); do
  rm -f "$work/candidate.json"
  if gh release download "$tag_name" --repo "$this" -p manifest.json -O "$work/candidate.json" --clobber >/dev/null 2>&1; then
    cand_seq="$(jq -r '.seq // -1' "$work/candidate.json")"
    cand_version="$(jq -r '.upstream.version // empty' "$work/candidate.json")"
    if [ -n "$cand_version" ] && [ "$cand_version" != "0" ] && [ "$cand_seq" -gt "$best_seq" ]; then
      best_seq="$cand_seq"
      last_version="$cand_version"
      last_ref="$(jq -r '.upstream.ref // empty' "$work/candidate.json")"
      last_tag="$tag_name"
    fi
  else
    note "could not read the manifest of $tag_name"
  fi
done

if [ -n "$last_version" ]; then
  say "Last offered: $last_tag (upstream $last_version, ${last_ref:-no commit recorded})"
else
  say "No earlier build has been offered from this repository."
fi

# Gates
# A deliberate line switch (upstream.json's pinned major edited by a person) must not be mistaken for a downgrade:
# when the last offered build came from another line, the in-line "newer than" comparison and the cross-line diff
# review do not apply. The build is still gated by everything else (age, draft, contract test, signature).
line_switched=false
if [ -n "$last_version" ] && [ "${last_version%%.*}" != "${version%%.*}" ]; then
  line_switched=true
  say "The upstream line changed: the last offered build was $last_version, this one is $version."
fi

if [ "$force" != true ] && [ -n "$last_version" ] && [ "$line_switched" != true ]; then
  if [ "$last_version" = "$version" ]; then
    stop "$version is already offered"
  fi
  newest="$(printf '%s\n%s\n' "$last_version" "$version" | sort -V | tail -1)"
  [ "$newest" = "$version" ] || stop "$version is older than the offered $last_version"
fi

if [ "$force" != true ] && [ -n "$published_at" ] && [ "$min_age" -gt 0 ]; then
  age=$(( ($(date +%s) - $(date -d "$published_at" +%s)) / 3600 ))
  if [ "$age" -lt "$min_age" ]; then
    stop "$tag was published $age h ago; waiting until it is $min_age h old"
  fi
  say "Age: $age h (limit $min_age h)"
fi

if [ -n "$pinned_major" ]; then
  major="${version%%.*}"
  [ "$major" = "$pinned_major" ] || risk "major version changed: this app is built for $pinned_major.x, upstream is now $version"
fi

# What changed since the last build
guard() {
  local old_tag="" cand g="$work/up.git" files

  if [ "$line_switched" = true ]; then
    note "the in-line diff review is skipped: the upstream line changed from ${last_version%%.*}.x to ${version%%.*}.x"
    return 0
  fi

  if [ -n "$last_version" ]; then
    for cand in "v$last_version" "$last_version"; do
      if [ -n "$(resolve "$cand" || true)" ]; then old_tag="$cand"; break; fi
    done
  fi

  if [ -z "$old_tag" ]; then
    note "no earlier upstream tag to compare with; the diff checks were skipped"
    return 0
  fi

  local old_now
  old_now="$(resolve "$old_tag")"

  if [ -n "$last_ref" ] && [ "$old_now" != "$last_ref" ]; then
    risk "upstream moved the tag $old_tag: it was $last_ref when it was built and is $old_now now"
  fi

  git init -q --bare "$g"
  if ! git -C "$g" fetch -q --depth 1 "$git_url" "refs/tags/$old_tag:refs/tags/old" "refs/tags/$tag:refs/tags/new" 2>"$work/fetch.log"; then
    note "could not fetch both tags for a diff ($(head -1 "$work/fetch.log"))"
    return 0
  fi

  files="$(git -C "$g" diff --name-only 'old^{commit}' 'new^{commit}')"
  say "Changed files since $old_tag: $(printf '%s\n' "$files" | grep -c . || true)"

  # 1. The build chain: files that decide how the executable is produced.
  local chain
  chain="$(printf '%s\n' "$files" | grep -E '^(packages/cli/script/|packages/script/|packages/cli/vite\.node\.config\.ts|package\.json$|bunfig\.toml|patches/)' || true)"
  [ -z "$chain" ] || risk "the build chain changed: $(printf '%s\n' "$chain" | head -5 | tr '\n' ' ')"

  # 2. Scripts that run when packages are installed.
  local hooks
  hooks="$(git -C "$g" diff -U0 'old^{commit}' 'new^{commit}' -- ':(glob)**/package.json' package.json \
    | grep -E '^\+[^+]' | grep -E '"(preinstall|install|postinstall|prepare)"[[:space:]]*:' || true)"
  [ -z "$hooks" ] || risk "install-time scripts were added or changed in package.json"

  # 3. Packages allowed to run install scripts.
  local t_old t_new t_added
  t_old="$(git -C "$g" show 'old^{commit}:package.json' 2>/dev/null | jq -r '(.trustedDependencies // [])[]' 2>/dev/null | sort -u || true)"
  t_new="$(git -C "$g" show 'new^{commit}:package.json' 2>/dev/null | jq -r '(.trustedDependencies // [])[]' 2>/dev/null | sort -u || true)"
  t_added="$(comm -13 <(printf '%s\n' "$t_old") <(printf '%s\n' "$t_new") | grep . || true)"
  [ -z "$t_added" ] || risk "new trusted dependencies: $(printf '%s\n' "$t_added" | head -5 | tr '\n' ' ')"

  # 4. New packages in the lock file.
  local p_old p_new p_added count
  p_old="$(git -C "$g" show 'old^{commit}:bun.lock' 2>/dev/null | grep -oE '^    "[^"]+": \[' | sort -u || true)"
  p_new="$(git -C "$g" show 'new^{commit}:bun.lock' 2>/dev/null | grep -oE '^    "[^"]+": \[' | sort -u || true)"
  p_added="$(comm -13 <(printf '%s\n' "$p_old") <(printf '%s\n' "$p_new") | grep . || true)"
  count="$(printf '%s\n' "$p_added" | grep -c . || true)"
  say "New packages in the lock file: $count"
  if [ "$count" -gt 0 ]; then note "first new packages: $(printf '%s\n' "$p_added" | sed 's/: \[//; s/"//g; s/^ *//' | head -8 | tr '\n' ' ')"; fi
  [ "$count" -le 60 ] || risk "$count new packages entered the lock file at once"

  # 5. New network hosts mentioned in the sources.
  local h_old h_new h_added hcount
  hosts() { git -C "$g" grep -ohE 'https?://[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+' "$1" -- 'packages/*/src/*' ':(exclude)*.test.*' ':(exclude)*.spec.*' ':(exclude)*/test/*' 2>/dev/null | sed 's#https\?://##' | sort -u || true; }
  h_old="$(hosts 'old^{commit}')"
  h_new="$(hosts 'new^{commit}')"
  h_added="$(comm -13 <(printf '%s\n' "$h_old") <(printf '%s\n' "$h_new") | grep . || true)"
  hcount="$(printf '%s\n' "$h_added" | grep -c . || true)"
  say "New network hosts in the sources: $hcount"
  if [ "$hcount" -gt 0 ]; then note "new hosts: $(printf '%s\n' "$h_added" | head -12 | tr '\n' ' ')"; fi
  [ "$hcount" -le 8 ] || risk "$hcount new network hosts appear in the sources"
}

guard

# Provenance (informational)
if verified="$(gh api "repos/$up/commits/$commit" --jq '.commit.verification.verified' 2>/dev/null)"; then
  note "commit signature verified by GitHub: $verified"
fi

emit build true
emit reason "ok"
emit tag "$tag"
emit version "$version"
emit commit "$commit"
emit risky "$risky"
emit last_version "$last_version"
emit last_ref "$last_ref"

if [ "$risky" = true ]; then
  say "Result: build, but publish as a draft for review."
else
  say "Result: build and publish."
fi
