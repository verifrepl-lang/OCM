#!/usr/bin/env bash
# Vendors the three libraries the bundled chat UI needs (markdown, sanitizer,
# syntax highlighting). No local server, no rootfs, no terminal — the app talks
# to opencode.ai directly through ZenClient.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
V="$ROOT/app/src/main/assets/ui/vendor"
mkdir -p "$V"
W="$(mktemp -d)"; cd "$W"

echo "== npm libraries =="
npm pack --silent marked@15.0.12 dompurify@3.2.6 @highlightjs/cdn-assets@11.11.1 >/dev/null
for f in *.tgz; do mkdir -p "x/${f%.tgz}"; tar -xzf "$f" -C "x/${f%.tgz}"; done
need() { [ -f "$1" ] || { echo "missing $1"; ls x; exit 1; }; cp "$1" "$2"; }
need x/marked-*/package/lib/marked.umd.js "$V/marked.js"
need x/dompurify-*/package/dist/purify.min.js "$V/purify.js"
need x/highlightjs-cdn-assets-*/package/highlight.min.js "$V/hljs.js"
echo "vendor: $(ls "$V")"
