# PowerShell twin of e2e.sh. Set $env:RECALL_SERVER to use another backend.
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
node scripts/lib/e2e.mjs @args
exit $LASTEXITCODE
