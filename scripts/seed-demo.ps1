# PowerShell twin of seed-demo.sh. Set $env:RECALL_SERVER to use another backend.
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
node scripts/lib/seed-demo.mjs @args
exit $LASTEXITCODE
