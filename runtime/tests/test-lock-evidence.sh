#!/bin/sh
set -eu

script_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
runtime_dir=$(CDPATH='' cd -- "$script_dir/.." && pwd)
lock_file="$runtime_dir/runtime.lock.json"
schema="$runtime_dir/evidence/codex_app_server_protocol.v2.schemas.json"

jq -e '
  .schemaVersion == 1 and
  .upstream.tag == "rust-v0.160.1" and
  .upstream.commit == "d27764b82f7118f674371e6d6e76271d9d606edb" and
  .runtime.minimumAndroidApi == 31 and
  .runtime.abi == "arm64-v8a" and
  .runtime.version == "0.160.1" and
  .codeModeHost.version == "0.160.1" and
  .schemaGenerator.extractedBytes == 248966648 and
  .schemaGenerator.combinedV2SchemaBytes == 751818
' "$lock_file" >/dev/null

expected_schema_sha=$(jq -er '.schemaGenerator.combinedV2SchemaSha256' "$lock_file")
if command -v sha256sum >/dev/null 2>&1; then
  actual_schema_sha=$(sha256sum "$schema" | awk '{print $1}')
else
  actual_schema_sha=$(shasum -a 256 "$schema" | awk '{print $1}')
fi

[ "$expected_schema_sha" = "$actual_schema_sha" ] || {
  echo "checked-in schema does not match runtime lock" >&2
  exit 1
}

expected_schema_bytes=$(jq -er '.schemaGenerator.combinedV2SchemaBytes' "$lock_file")
actual_schema_bytes=$(wc -c < "$schema" | tr -d ' ')
[ "$expected_schema_bytes" = "$actual_schema_bytes" ] || {
  echo "checked-in schema byte count does not match runtime lock" >&2
  exit 1
}

echo "runtime lock and schema evidence test passed"
