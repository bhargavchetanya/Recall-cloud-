# PowerShell twin of fetch-model.sh: downloads the all-MiniLM-L6-v2 ONNX model and vocabulary into backend/models/.
$ErrorActionPreference = 'Stop'

$Revision = '1110a243fdf4706b3f48f1d95db1a4f5529b4d41'
$Base = "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/$Revision"
$Files = @(
    @{ Url = "$Base/onnx/model.onnx"; Name = 'model.onnx'; Sha256 = '6fd5d72fe4589f189f8ebc006442dbb529bb7ce38f8082112682524616046452' },
    @{ Url = "$Base/vocab.txt"; Name = 'vocab.txt'; Sha256 = '07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3' }
)

$Dir = Join-Path (Split-Path -Parent $PSScriptRoot) 'backend\models'
New-Item -ItemType Directory -Force $Dir | Out-Null

foreach ($f in $Files) {
    $path = Join-Path $Dir $f.Name
    if ((Test-Path $path) -and ((Get-FileHash $path -Algorithm SHA256).Hash.ToLower() -eq $f.Sha256)) {
        Write-Host "ok       $($f.Name) (already downloaded)"
        continue
    }
    Write-Host "download $($f.Name)"
    $part = "$path.part"
    $ProgressPreference = 'SilentlyContinue'
    Invoke-WebRequest -Uri $f.Url -OutFile $part -UseBasicParsing
    $actual = (Get-FileHash $part -Algorithm SHA256).Hash.ToLower()
    if ($actual -ne $f.Sha256) {
        Remove-Item $part -Force
        throw "checksum mismatch for $($f.Name): expected $($f.Sha256), got $actual"
    }
    Move-Item -Force $part $path
    Write-Host "ok       $($f.Name)"
}
