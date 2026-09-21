$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$asr = Join-Path $root 'tools\asr'
$modelName = 'sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11'
$modelDir = Join-Path $asr "models\$modelName"

$exe = (Get-Command 'sherpa-onnx-online-websocket-server.exe' -ErrorAction SilentlyContinue).Source
if (-not $exe) {
    $binDir = python -c "import sysconfig; print(sysconfig.get_path('scripts'))"
    $candidate = Join-Path $binDir 'sherpa-onnx-online-websocket-server.exe'
    if (Test-Path $candidate) { $exe = $candidate }
}
if (-not $exe) {
    throw 'No se encontró sherpa-onnx-online-websocket-server.exe. Ejecuta primero install_windows.ps1.'
}

$encoder = Join-Path $modelDir 'encoder.int8.onnx'
$decoder = Join-Path $modelDir 'decoder.int8.onnx'
$joiner = Join-Path $modelDir 'joiner.int8.onnx'
$tokens = Join-Path $modelDir 'tokens.txt'

$missing = @($encoder,$decoder,$joiner,$tokens) | Where-Object { -not (Test-Path $_) }
if ($missing.Count -gt 0) {
    Write-Host '==> Faltan archivos del modelo ASR. Descargando e instalando el modelo oficial de CODES...' -ForegroundColor Yellow
    $installScript = Join-Path $asr 'install_windows.ps1'
    if (Test-Path $installScript) {
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $installScript
    }
    $missing = @($encoder,$decoder,$joiner,$tokens) | Where-Object { -not (Test-Path $_) }
    if ($missing.Count -gt 0) {
        foreach ($f in $missing) { Write-Host "Falta archivo del modelo: $f" -ForegroundColor Red }
        throw 'No se pudieron instalar los archivos del modelo ASR requerido.'
    }
}

Write-Host '==> Iniciando CODES ASR streaming en ws://localhost:6006 ...' -ForegroundColor Green
& $exe `
  "--port=6006" `
  "--num-work-threads=2" `
  "--num-io-threads=2" `
  "--tokens=$tokens" `
  "--encoder=$encoder" `
  "--decoder=$decoder" `
  "--joiner=$joiner" `
  "--log-file=$(Join-Path $asr 'asr.log')" `
  "--max-batch-size=5" `
  "--loop-interval-ms=10"
