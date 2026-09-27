$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$asr = Join-Path $root 'tools\asr'
$modelName = 'sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11'
$modelDir = Join-Path $asr "models\$modelName"

function Resolve-Python {
    # IMPORTANTE: 010322 incluia un .venv con Python 3.14, que no era el
    # interprete usado por la version funcional 175901. Primero buscamos el
    # Python global/py launcher que tenga sherpa; el venv solo queda como
    # ultimo recurso y nunca se usa si no puede importar sherpa_onnx.
    $candidates = @()
    $py = Get-Command py.exe -ErrorAction SilentlyContinue
    if ($py) {
        foreach ($ver in @('3.13','3.12','3.11','3.10','3.14')) {
            try { $p = (& $py.Source -$ver -c "import sys; print(sys.executable)" 2>$null | Select-Object -First 1); if ($p -and (Test-Path $p)) { $candidates += $p } } catch {}
        }
    }
    $python = Get-Command python.exe -ErrorAction SilentlyContinue
    if ($python) { $candidates += $python.Source }
    $venvPython = Join-Path $root '.venv\Scripts\python.exe'
    if (Test-Path $venvPython) { $candidates += $venvPython }
    foreach ($candidate in $candidates | Select-Object -Unique) {
        try {
            & $candidate -c "import sherpa_onnx" *> $null
            if ($LASTEXITCODE -eq 0) { return $candidate }
        } catch {}
    }
    return $null
}

$installScript = Join-Path $asr 'install_windows.ps1'
$encoder = Join-Path $modelDir 'encoder.int8.onnx'
$decoder = Join-Path $modelDir 'decoder.int8.onnx'
$joiner = Join-Path $modelDir 'joiner.int8.onnx'
$tokens = Join-Path $modelDir 'tokens.txt'

$python = Resolve-Python
$dependenciesOk = $false
$exe = $null
if ($python) {
    try { & $python -c "import numpy, sherpa_onnx" *> $null; $dependenciesOk = ($LASTEXITCODE -eq 0) } catch {}
    if ($dependenciesOk) {
        try {
            $scripts = & $python -c "import sysconfig; print(sysconfig.get_path('scripts'))" 2>$null | Select-Object -First 1
            if ($scripts) { $candidate = Join-Path $scripts 'sherpa-onnx-online-websocket-server.exe'; if (Test-Path $candidate) { $exe = $candidate } }
        } catch {}
    }
}

$missing = @($encoder,$decoder,$joiner,$tokens) | Where-Object { -not (Test-Path $_) }
if ($missing.Count -gt 0 -or -not $dependenciesOk -or -not $exe) {
    Write-Host '==> ASR: falta Sherpa-ONNX, el ejecutable websocket o el modelo. Instalando/reparando...' -ForegroundColor Yellow
    if (-not $python) { & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $installScript; if ($LASTEXITCODE -ne 0) { throw 'La instalacion del ASR fallo.' } } else { & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $installScript -PythonPath $python; if ($LASTEXITCODE -ne 0) { throw 'La reparacion del ASR fallo.' } }
    $python = Resolve-Python
    if (-not $python) { throw 'No se encontro un Python que pueda importar sherpa_onnx.' }
    $scripts = & $python -c "import sysconfig; print(sysconfig.get_path('scripts'))" 2>$null | Select-Object -First 1
    $exe = if ($scripts) { Join-Path $scripts 'sherpa-onnx-online-websocket-server.exe' } else { $null }
    $missing = @($encoder,$decoder,$joiner,$tokens) | Where-Object { -not (Test-Path $_) }
    if ($missing.Count -gt 0) { throw "Faltan archivos del modelo ASR: $($missing -join ', ')" }
    if (-not $exe -or -not (Test-Path $exe)) { throw 'No se encontro sherpa-onnx-online-websocket-server.exe despues de la instalacion.' }
}

Write-Host "==> ASR: usando $python" -ForegroundColor DarkGray
Write-Host "==> Iniciando sherpa-onnx-online-websocket-server.exe en ws://localhost:6006 ..." -ForegroundColor Green
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
exit $LASTEXITCODE
