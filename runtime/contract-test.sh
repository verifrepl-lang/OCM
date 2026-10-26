#!/usr/bin/env bash
# Starts a freshly built OpenCode server the way the Android app starts it and checks every interface the app depends
# on. A build that fails any required check must never be offered to phones.
#
# Usage: runtime/contract-test.sh <opencode-binary> <expected-version>
set -uo pipefail

bin="$(realpath "${1:?usage: contract-test.sh <opencode-binary> <expected-version>}")"
want="${2:?usage: contract-test.sh <opencode-binary> <expected-version>}"
work="$(mktemp -d)"
pid=""
failed=0

cleanup() {
  [ -n "$pid" ] && kill "$pid" 2>/dev/null
  wait 2>/dev/null
  rm -rf "$work"
}
trap cleanup EXIT

pass() { echo "ok    $1"; }
fail() { echo "FAIL  $1"; failed=$((failed + 1)); }
warn() { echo "warn  $1"; }
check() { # check <name> <command...>
  local name="$1"
  shift
  if "$@"; then pass "$name"; else fail "$name"; fi
}

mkdir -p "$work/home" "$work/guest"
printf '# test instructions\n' >"$work/guest/AGENTS.md"

port="$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')"
password="contract-$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')"
base="http://127.0.0.1:$port"
auth="opencode:$password"

check "version flag" bash -c "'$bin' --version | grep -qF '$want'"

# The same variables the app sets (see ProotCommand.guestEnv), with a scratch HOME and no XDG overrides, so the data
# location is the one the app backs up before an update. The 1.x server reads OPENCODE_SERVER_PASSWORD, the 2.x one
# OPENCODE_PASSWORD; both are set so the test covers whichever line is being built.
env -i PATH="$PATH" HOME="$work/home" LANG=C.UTF-8 TMPDIR="$work" \
  OPENCODE_SERVER_PASSWORD="$password" OPENCODE_PASSWORD="$password" OPENCODE_CLIENT=android OPENCODE_DISABLE_AUTOUPDATE=1 \
  OPENCODE_CONFIG_CONTENT="{\"instructions\":[\"$work/guest/AGENTS.md\"]}" \
  "$bin" serve --hostname 127.0.0.1 --port "$port" >"$work/server.log" 2>&1 &
pid=$!

# The health endpoint the app can use: /api/health on the 1.x line, /api/info on the 2.x one. A catch-all UI route
# may answer 200 on any other path, so only these two names are trusted.
health_status() {
  local path="$1" without="$2"
  if [ -n "$without" ]; then
    curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$base$path"
  else
    curl -s -o /dev/null -w '%{http_code}' --max-time 5 -u "$auth" "$base$path"
  fi
}

up=0
health_path=""
for _ in $(seq 1 120); do
  for p in /api/health /api/info; do
    code="$(health_status "$p")"
    [ "$code" = "200" ] && { up=1; health_path="$p"; break; }
  done
  [ "$up" = 1 ] && break
  kill -0 "$pid" 2>/dev/null || break
  sleep 1
done

if [ "$up" != 1 ]; then
  fail "server answers on /api/health or /api/info"
  echo "---- server log ----"
  tail -n 60 "$work/server.log"
  exit 1
fi

pass "server answers on $health_path"

quiet() { "$@" >/dev/null; }

# The 2.x info endpoint reports the version and paths; the 1.x health endpoint just says healthy.
if [ "$health_path" = "/api/info" ]; then
  curl -s --max-time 5 -u "$auth" "$base/api/info" >"$work/info.json"
  check "info reports the version" quiet jq -e --arg v "$want" '.version | contains($v)' "$work/info.json"
  check "info has urls and paths" quiet jq -e '(.urls | type == "array") and (.paths.tmp | type == "string")' "$work/info.json"
else
  curl -s --max-time 5 -u "$auth" "$base/api/health" >"$work/health.json"
  check "health says healthy" quiet jq -e '.healthy == true' "$work/health.json"
fi

status() { curl -s -o /dev/null -w '%{http_code}' --max-time 20 "$@"; }

check "no credentials is refused" test "$(status "$base$health_path")" = 401
check "wrong password is refused" test "$(status -u 'opencode:nope' "$base$health_path")" = 401

# The web view is signed in with basic auth: the page loads with the password the web view answers the server's
# challenge with, and the 1.x interface also takes it from an auth_token query parameter. No pairing round-trip.
curl -s -D "$work/page.headers" -o "$work/page.html" --max-time 10 -u "$auth" -H 'Accept: text/html' "$base/"
check "web interface is served" bash -c "head -1 '$work/page.headers' | grep -q ' 200' && grep -qi '^content-type: *text/html' '$work/page.headers'"
check "web interface looks like a page" grep -qi '<html' "$work/page.html"

# Events: the app watches them to post notifications while it is in the background.
curl -s -N -D "$work/sse.headers" -o /dev/null --max-time 4 -u "$auth" "$base/api/event" || true
check "event stream opens" bash -c "head -1 '$work/sse.headers' | grep -q ' 200' && grep -qi '^content-type: *text/event-stream' '$work/sse.headers'"

# Sign-in with a Console account (device code flow) needs these routes to exist.
integration="$(status -u "$auth" "$base/api/integration/opencode" || true)"
if [ "$integration" = 404 ]; then integration="$(status -u "$auth" "$base/api/integration" || true)"; fi
if [ "$integration" = 200 ]; then pass "console integration is listed"; else fail "console integration is listed (HTTP $integration)"; fi

# Where the app looks for data to back up before an update.
check "data directory is where the app expects" test -d "$work/home/.local/share/opencode"
if ls "$work/home/.local/share/opencode"/*.db >/dev/null 2>&1; then pass "database file is created"; else warn "no .db file yet (created on first use?)"; fi

check "server is still running" kill -0 "$pid"

if [ "$failed" -ne 0 ]; then
  echo "$failed check(s) failed"
  echo "---- server log ----"
  tail -n 60 "$work/server.log"
  exit 1
fi

echo "contract test passed"
