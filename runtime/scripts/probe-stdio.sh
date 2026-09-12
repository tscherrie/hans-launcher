#!/bin/sh
set -eu

artifact=${1:?"usage: probe-stdio.sh PATH_TO_CODEX_APP_SERVER"}
command -v timeout >/dev/null 2>&1 || {
  echo "GNU/toybox timeout is required for the stdio probe" >&2
  exit 2
}
test_root=$(mktemp -d "${TMPDIR:-/tmp}/hans-runtime-stdio.XXXXXX")
trap 'rm -rf -- "$test_root"' 0 HUP INT TERM

mkdir -p "$test_root/codex-home" "$test_root/tmp"
version_output=$(CODEX_HOME="$test_root/codex-home" TMPDIR="$test_root/tmp" \
  "$artifact" --version 2> "$test_root/version.stderr")
case "$version_output" in
  "codex-app-server 0.154.0") ;;
  *)
    echo "unexpected version response: $version_output" >&2
    exit 1
    ;;
esac

initialize='{"id":1,"method":"initialize","params":{"clientInfo":{"name":"hans_runtime_probe","title":"Hans Runtime Probe","version":"0.0.0"},"capabilities":{}}}'
set +e
{
  printf '%s\n' "$initialize"
  sleep 2
} | CODEX_HOME="$test_root/codex-home" TMPDIR="$test_root/tmp" \
  timeout 10 "$artifact" > "$test_root/responses.jsonl" 2> "$test_root/stdio.stderr"
probe_status=$?
set -e

case "$probe_status" in
  0|124) ;;
  *)
    echo "stdio probe failed with exit status $probe_status" >&2
    sed -n '1,80p' "$test_root/stdio.stderr" >&2
    exit "$probe_status"
    ;;
esac

jq -e 'select(.id == 1 and .result.platformFamily == "unix")' \
  "$test_root/responses.jsonl" >/dev/null

echo "stdio initialize probe passed"
