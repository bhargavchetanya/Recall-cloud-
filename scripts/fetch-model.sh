#!/usr/bin/env bash
# Downloads the all-MiniLM-L6-v2 ONNX model and vocabulary into backend/models/ once.
# Pinned to one HuggingFace revision and checked by SHA-256. Safe to re-run: valid files are kept.
set -euo pipefail

REVISION=1110a243fdf4706b3f48f1d95db1a4f5529b4d41
BASE="https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/${REVISION}"
MODEL_SHA256=6fd5d72fe4589f189f8ebc006442dbb529bb7ce38f8082112682524616046452
VOCAB_SHA256=07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3

DIR="$(cd "$(dirname "$0")/.." && pwd)/backend/models"
mkdir -p "$DIR"

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

fetch() {
  local url="$1" file="$2" expected="$3"
  if [ -f "$file" ] && [ "$(sha256 "$file")" = "$expected" ]; then
    echo "ok       $(basename "$file") (already downloaded)"
    return
  fi
  echo "download $(basename "$file")"
  curl -fsSL --retry 3 -o "$file.part" "$url"
  local actual
  actual="$(sha256 "$file.part")"
  if [ "$actual" != "$expected" ]; then
    rm -f "$file.part"
    echo "checksum mismatch for $(basename "$file"): expected $expected, got $actual" >&2
    exit 1
  fi
  mv "$file.part" "$file"
  echo "ok       $(basename "$file")"
}

fetch "$BASE/onnx/model.onnx" "$DIR/model.onnx" "$MODEL_SHA256"
fetch "$BASE/vocab.txt" "$DIR/vocab.txt" "$VOCAB_SHA256"
