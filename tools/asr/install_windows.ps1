$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$asr = Join-Path $root 'tools\asr'
$modelName = 'sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11'
$modelDir = Join-Path $asr "models\$modelName"
$archive = Join-Path $asr "$modelName.tar.bz2"
$url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$modelName.tar.bz2"

New-Item -ItemType Directory -Force -Path (Join-Path $asr 'models') | Out-Null

Write-Host '==> Instalando sherpa-onnx (CPU) ...' -ForegroundColor Cyan
python -m pip install --upgrade pip
python -m pip install --upgrade sherpa-onnx sherpa-onnx-bin

if (-not (Test-Path (Join-Path $modelDir 'encoder.int8.onnx'))) {
    Write-Host '==> Descargando modelo ASR (~1 GB de archivo comprimido) ...' -ForegroundColor Cyan
    Invoke-WebRequest -Uri $url -OutFile $archive
    Write-Host '==> Extrayendo modelo ...' -ForegroundColor Cyan
    tar -xjf $archive -C (Join-Path $asr 'models')
    Remove-Item $archive -Force
}

Write-Host ''
Write-Host 'Instalación lista.' -ForegroundColor Green
Write-Host "Modelo: $modelDir"
Write-Host 'Ejecuta .\tools\asr\start_asr_windows.ps1 para iniciar el servidor.'
