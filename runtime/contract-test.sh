#!/usr/bin/env bash
# Starts a freshly built OpenCode server the way the Android app starts it and checks every interface the app depends
# on. A build that fails any required check must never be offered to phones.
#
# Both upstream release lines must pass: the 2.x line answers on /api/info and pairs the web view through /api/pair,
# the 1.x line answers on /api/health and opens the web view through the auth_token query parameter. The checks try
# what the running build offers and fail only when neither way works.
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
token="$(printf '%s' "$auth" | base64)"

check "version flag" bash -c "'$bin' --version | grep -qF '$want'"

# The same variables the app sets (see ProotCommand.guestEnv), with a scratch HOME and no XDG overrides, so the data
# location is the one the app backs up before an update. Both password variables are set: the 1.x line reads only
# OPENCODE_SERVER_PASSWORD, the 2.x line prefers OPENCODE_PASSWORD.
env -i PATH="$PATH" HOME="$work/home" LANG=C.UTF-8 TMPDIR="$work" \
  OPENCODE_PASSWORD="$password" OPENCODE_SERVER_PASSWORD="$password" OPENCODE_CLIENT=android OPENCODE_DISABLE_AUTOUPDATE=1 \
  OPENCODE_CONFIG_CONTENT="{\"instructions\":[\"$work/guest/AGENTS.md\"]}" \
  "$bin" serve --hostname 127.0.0.1 --port "$port" >"$work/server.log" 2>&1 &
pid=$!

# The 2.x line reports on /api/info, the 1.x line on /api/health. The 1.x server also serves its web page under
# unknown /api paths, so a 200 alone is not proof: the answer must be JSON. One of the two must answer properly.
up=0
info_path=""
for _ in $(seq 1 120); do
  for path in /api/info /api/health; do
    body="$(curl -s --max-time 2 -u "$auth" "$base$path" || true)"
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 -u "$auth" "$base$path" || true)"
    if [ "$code" = "200" ] && [ "${body:0:1}" = "{" ]; then info_path="$path"; break; fi
  done
  if [ -n "$info_path" ]; then up=1; break; fi
  kill -0 "$pid" 2>/dev/null || break
  sleep 1
done

if [ "$up" != 1 ]; then
  fail "server answers on /api/info or /api/health"
  echo "---- server log ----"
  tail -n 60 "$work/server.log"
  exit 1
fi

pass "server answers on $info_path"

status() { curl -s -o /dev/null -w '%{http_code}' --max-time 20 "$@"; }

check "no credentials is refused" test "$(status "$base$info_path")" = 401
check "wrong password is refused" test "$(status -u 'opencode:nope' "$base$info_path")" = 401

# The auth_token query parameter is how the app opens the web view when the pairing route is absent.
check "auth_token query signs a request in" test "$(status "$base$info_path?auth_token=$token")" = 200

if [ "$info_path" = "/api/info" ]; then
  curl -s --max-time 5 -u "$auth" "$base/api/info" >"$work/info.json"
  quiet() { "$@" >/dev/null; }
  check "info reports the version" quiet jq -e --arg v "$want" '.version | contains($v)' "$work/info.json"
  check "info has urls and paths" quiet jq -e '(.urls | type == "array") and (.paths.tmp | type == "string")' "$work/info.json"
else
  curl -s --max-time 5 -u "$auth" "$base/api/health" >"$work/health.json"
  quiet() { "$@" >/dev/null; }
  check "health reports healthy" quiet jq -e '.data.healthy == true or .healthy == true' "$work/health.json"
fi

# Pairing: how the app signs its web view in on the 2.x line. The 1.x line has no /api/pair and is opened with the
# auth_token parameter checked above.
pair_code="$(curl -s --max-time 5 -X POST -u "$auth" "$base/api/pair" | tee "$work/pair.json" | jq -r '.code // .data.code // empty' 2>/dev/null || true)"
if [ -n "$pair_code" ]; then
  check "pairing code is issued" test -n "$pair_code"

  curl -s -D "$work/connect.headers" -o /dev/null --max-time 5 -H 'Accept: text/html' "$base/auth/connect/$pair_code"
  check "pairing link redirects" bash -c "head -1 '$work/connect.headers' | grep -q ' 302'"
  cookie="$(tr -d '\r' <"$work/connect.headers" | grep -i '^set-cookie:' | head -1 | sed 's/^[Ss]et-[Cc]ookie: *//; s/;.*//')"
  check "cookie is named for the port" test "${cookie%%=*}" = "opencode_session_$port"
  check "cookie is HttpOnly" grep -qi '^set-cookie:.*httponly' "$work/connect.headers"
  check "the cookie authenticates" test "$(status -H "Cookie: $cookie" "$base/api/info")" = 200
  check "a pairing code works once" test "$(status -H 'Accept: text/html' "$base/auth/connect/$pair_code")" = 401

  page_headers=(-H "Cookie: $cookie")
  page_url="$base/"
else
  warn "no /api/pair (the 1.x line); the web view signs in with auth_token"
  page_headers=()
  page_url="$base/?auth_token=$token"
fi

# The web interface is served from inside the executable.
curl -s -D "$work/page.headers" -o "$work/page.html" --max-time 10 "${page_headers[@]}" -H 'Accept: text/html' "$page_url"
check "web interface is served" bash -c "head -1 '$work/page.headers' | grep -q ' 200' && grep -qi '^content-type: *text/html' '$work/page.headers'"
check "web interface looks like a page" grep -qi '<html' "$work/page.html"

# Events: the app watches them to post notifications while it is in the background.
curl -s -N -D "$work/sse.headers" -o /dev/null --max-time 4 -u "$auth" "$base/api/event" || true
check "event stream opens" bash -c "head -1 '$work/sse.headers' | grep -q ' 200' && grep -qi '^content-type: *text/event-stream' '$work/sse.headers'"

# Sign-in with a Console account (device code flow) needs these routes to exist.
integration="$(status -u "$auth" "$base/api/integration/opencode" || true)"
if [ "$integration" = 404 ]; then integration="$(status -u "$auth" "$base/api/integration" || true)"; fi
if [ "$integration" = 200 ]; then pass "console integration is listed"; else fail "console integration is listed (HTTP $integration)"; fi

# The sign-in start must accept the payload the app sends (with the empty inputs record the 1.x line requires).
oauth_start="$(curl -s --max-time 20 -X POST -u "$auth" -H 'Content-Type: application/json' \
  -d '{"methodID":"device","inputs":{}}' "$base/api/integration/opencode/connect/oauth" || true)"
attempt="$(printf '%s' "$oauth_start" | jq -r '.data.attemptID // empty' 2>/dev/null || true)"
if [ -n "$attempt" ]; then
  pass "oauth device attempt starts"
  # The 1.x line reports the status under /api/integration/attempt/:id, the 2.x line under the connect route.
  code1="$(status -u "$auth" "$base/api/integration/attempt/$attempt")"
  code2="$(status -u "$auth" "$base/api/integration/opencode/connect/oauth/$attempt")"
  if [ "$code1" = "200" ] || [ "$code2" = "200" ]; then pass "oauth attempt status is reachable"; else fail "oauth attempt status is reachable ($code1/$code2)"; fi
else
  fail "oauth device attempt starts"
fi

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
