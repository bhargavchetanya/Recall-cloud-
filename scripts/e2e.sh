#!/usr/bin/env bash
# Runs scripts/lib/e2e.mjs with Node 18+ (Git Bash, WSL, Linux, macOS). Set RECALL_SERVER to use another backend.
set -euo pipefail
cd "$(dirname "$0")/.."
exec node scripts/lib/e2e.mjs "$@"
