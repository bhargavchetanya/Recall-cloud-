# PowerShell twin of load-test.sh. Set $env:RECALL_SERVER to use another backend.
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
node scripts/lib/load-test.mjs @args
exit $LASTEXITCODE
