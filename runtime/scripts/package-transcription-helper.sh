#!/bin/sh
set -eu

script_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
runtime_dir=$(CDPATH='' cd -- "$script_dir/.." && pwd)
helper_dir="$runtime_dir/transcription"
source_lock="$helper_dir/source.lock.json"
artifact_lock="$helper_dir/helper.lock.json"
command -v jq >/dev/null 2>&1 || { echo "jq is required" >&2; exit 2; }
command -v python3 >/dev/null 2>&1 || { echo "python3 is required" >&2; exit 2; }

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}';
  else shasum -a 256 "$1" | awk '{print $1}'; fi
}
check_file() {
  file=$1; expected_sha=$2; expected_bytes=$3
  [ -f "$file" ] || { echo "Missing pinned input: $file" >&2; exit 1; }
  [ "$(wc -c < "$file" | tr -d ' ')" = "$expected_bytes" ] || { echo "Pinned input byte mismatch" >&2; exit 1; }
  [ "$(sha256_file "$file")" = "$expected_sha" ] || { echo "Pinned input digest mismatch" >&2; exit 1; }
}
prepare_source() {
  commit=$(jq -er .upstreamCommit "$source_lock")
  archive="$helper_dir/build/source/codex-$commit.tar.gz"
  source_dir="$helper_dir/build/source/codex"
  mkdir -p "$helper_dir/build/source"
  if [ ! -f "$archive" ]; then
    [ "${HANS_RUNTIME_OFFLINE-0}" != 1 ] || { echo "Offline helper build: pinned source archive missing" >&2; exit 1; }
    download=$(mktemp "$helper_dir/build/source/download.XXXXXX")
    trap 'rm -f -- "$download"' 0 HUP INT TERM
    curl --fail --location --proto '=https' --tlsv1.2 --retry 2 --output "$download" "$(jq -er .sourceUrl "$source_lock")"
    check_file "$download" "$(jq -er .sourceSha256 "$source_lock")" "$(jq -er .sourceBytes "$source_lock")"
    mv "$download" "$archive"
    trap - 0 HUP INT TERM
  fi
  check_file "$archive" "$(jq -er .sourceSha256 "$source_lock")" "$(jq -er .sourceBytes "$source_lock")"
  # Never trust an old developer checkout: reconstruct verified source into a
  # fresh build directory on every preparation. Archive bytes are SHA-pinned.
  temporary=$(mktemp -d "$helper_dir/build/source/extract.XXXXXX")
  trap 'rm -rf -- "$temporary"' 0 HUP INT TERM
  tar -xzf "$archive" -C "$temporary" --strip-components=1
  [ -f "$temporary/codex-rs/login/Cargo.toml" ] || { echo "Pinned source layout mismatch" >&2; exit 1; }
  if [ -d "$source_dir" ]; then
    previous=$(mktemp -d "$helper_dir/build/source/previous.XXXXXX")
    mv "$source_dir" "$previous/codex"
  fi
  mv "$temporary" "$source_dir"
  trap - 0 HUP INT TERM
}

mode=${1:---stage}
case "$mode" in
  --prepare-source) prepare_source; exit 0 ;;
  --build|--test)
    prepare_source
    cargo_bin=${HANS_TRANSCRIPTION_CARGO:-cargo}
    rust_version=$(jq -er .rustVersion "$source_lock")
    target=$(jq -er .target "$source_lock")
    # The binary is entirely static and therefore safe for the APK native
    # library extraction contract; Android's app UID remains its boundary.
    RUSTFLAGS="${RUSTFLAGS-} -C link-arg=-Wl,-z,max-page-size=16384"
    export RUSTFLAGS
    if [ "$mode" = --test ]; then
      "$cargo_bin" "+$rust_version" test --locked --manifest-path "$helper_dir/Cargo.toml" --target "$target" --target-dir "$helper_dir/build/target"
    else
      "$cargo_bin" "+$rust_version" build --locked --release --manifest-path "$helper_dir/Cargo.toml" --target "$target" --target-dir "$helper_dir/build/target"
      binary="$helper_dir/build/target/$target/release/hans-codex-transcribe"
      python3 "$runtime_dir/tests/verify-transcription-elf.py" "$binary"
      version=$(jq -er .helperVersion "$source_lock")
      mkdir -p "$helper_dir/build/artifacts/$version"
      install -m 0755 "$binary" "$helper_dir/build/artifacts/$version/libcodex_transcribe.so"
      echo "$helper_dir/build/artifacts/$version/libcodex_transcribe.so"
    fi
    exit 0 ;;
  --stage) ;;
  *) echo "Usage: $0 [--prepare-source|--build|--test|--stage] [output-directory]" >&2; exit 2 ;;
esac

output_dir=${2:-"$runtime_dir/build/generated/jniLibs/arm64-v8a"}
version=$(jq -er .helperVersion "$source_lock")
artifact=${HANS_TRANSCRIPTION_ARTIFACT:-"$helper_dir/build/artifacts/$version/libcodex_transcribe.so"}
for field in helperVersion apkLibraryName upstreamVersion upstreamCommit rustVersion target; do
  [ "$(jq -er ".$field" "$artifact_lock")" = "$(jq -er ".$field" "$source_lock")" ] || {
    echo "Helper/source provenance mismatch: $field" >&2; exit 1;
  }
done
[ "$(jq -er .sourceArchiveSha256 "$artifact_lock")" = "$(jq -er .sourceSha256 "$source_lock")" ] || { echo "Source archive digest provenance mismatch" >&2; exit 1; }
[ "$(jq -er .sourceArchiveBytes "$artifact_lock")" = "$(jq -er .sourceBytes "$source_lock")" ] || { echo "Source archive bytes provenance mismatch" >&2; exit 1; }
[ "$(sha256_file "$helper_dir/Cargo.lock")" = "$(jq -er .cargoLockSha256 "$artifact_lock")" ] || { echo "Helper Cargo.lock provenance mismatch" >&2; exit 1; }
[ "$(sha256_file "$helper_dir/Cargo.toml")" = "$(jq -er .cargoManifestSha256 "$artifact_lock")" ] || { echo "Helper Cargo.toml provenance mismatch" >&2; exit 1; }
[ "$(sha256_file "$helper_dir/src/main.rs")" = "$(jq -er .helperSourceSha256 "$artifact_lock")" ] || { echo "Helper Rust source provenance mismatch" >&2; exit 1; }
check_file "$artifact" "$(jq -er .sha256 "$artifact_lock")" "$(jq -er .bytes "$artifact_lock")"
python3 "$runtime_dir/tests/verify-transcription-elf.py" "$artifact"
mkdir -p "$output_dir"
temporary=$(mktemp "$output_dir/transcription.XXXXXX")
trap 'rm -f -- "$temporary"' 0 HUP INT TERM
install -m 0755 "$artifact" "$temporary"
mv "$temporary" "$output_dir/$(jq -er .apkLibraryName "$source_lock")"
trap - 0 HUP INT TERM
printf '%s\n' "$output_dir/$(jq -er .apkLibraryName "$source_lock")"
