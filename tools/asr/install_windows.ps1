param([string]$PythonPath)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$asr = Join-Path $root 'tools\asr'
$modelName = 'sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11'
$modelDir = Join-Path $asr "models\$modelName"
$archive = Join-Path $asr "$modelName.tar.bz2"
$url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$modelName.tar.bz2"

function Resolve-Python([string]$Explicit) {
    $c = @()
    if ($Explicit) { $c += $Explicit }
    $py = Get-Command py.exe -ErrorAction SilentlyContinue
    if ($py) {
        foreach ($v in @('3.13', '3.12', '3.11', '3.10', '3.14')) {
            try {
                $p = & $py.Source -$v -c "import sys; print(sys.executable)" 2>$null | Select-Object -First 1
                if ($p -and (Test-Path $p)) { $c += $p }
            } catch {}
        }
    }
    $p = Get-Command python.exe -ErrorAction SilentlyContinue
    if ($p) { $c += $p.Source }
    $v = Join-Path $root '.venv\Scripts\python.exe'
    if (Test-Path $v) { $c += $v }
    foreach ($p in $c | Select-Object -Unique) {
        try { & $p -c "import sherpa_onnx" *>$null; if ($LASTEXITCODE -eq 0) { return $p } } catch {}
    }
    return $null
}

# Descarga manual (en vez de Invoke-WebRequest) para poder mostrar avance real:
# el modelo pesa ~1 GB y, cuando este instalador corre como proceso hijo oculto
# (iniciar_codes_windows.ps1 redirige su salida a logs\asr-stdout.log), la barra
# nativa de Invoke-WebRequest usa el stream de progreso y nunca llega a ese log.
# Escribiendo el avance con Write-Host normal sí queda en el log, y
# iniciar_codes_windows.ps1 lo va mostrando en la consola principal mientras
# espera a que el ASR quede listo.
function Invoke-DescargaConProgreso {
    param(
        [Parameter(Mandatory)][string]$Uri,
        [Parameter(Mandatory)][string]$OutFile,
        [int]$TimeoutSec = 900
    )

    $request = [System.Net.HttpWebRequest]::Create($Uri)
    $request.Timeout = $TimeoutSec * 1000
    $request.ReadWriteTimeout = $TimeoutSec * 1000
    $request.UserAgent = 'CODES-ASR-Installer'

    $response = $request.GetResponse()
    try {
        $totalBytes = $response.ContentLength
        $inputStream = $response.GetResponseStream()
        $outputStream = [System.IO.File]::Create($OutFile)
        $buffer = New-Object byte[] 262144
        $totalRead = [long]0
        $ultimoPorcentaje = -1
        $ultimoAviso = Get-Date

        try {
            while ($true) {
                $read = $inputStream.Read($buffer, 0, $buffer.Length)
                if ($read -le 0) { break }
                $outputStream.Write($buffer, 0, $read)
                $totalRead += $read

                if ($totalBytes -gt 0) {
                    $porcentaje = [int](($totalRead * 100) / $totalBytes)
                    $ahora = Get-Date
                    $tocaAvisar = ($porcentaje -ne $ultimoPorcentaje) -and (($ahora - $ultimoAviso).TotalMilliseconds -ge 500 -or $porcentaje -eq 100)
                    if ($tocaAvisar) {
                        $mbHecho = [math]::Round($totalRead / 1MB, 1)
                        $mbTotal = [math]::Round($totalBytes / 1MB, 1)
                        Write-Host "==> Descargando modelo ASR: $porcentaje% ($mbHecho MB / $mbTotal MB)" -ForegroundColor Cyan
                        $ultimoPorcentaje = $porcentaje
                        $ultimoAviso = $ahora
                    }
                }
            }
        }
        finally {
            $outputStream.Close()
            $inputStream.Close()
        }
    }
    finally {
        $response.Close()
    }
}

$python = Resolve-Python $PythonPath
if (-not $python) {
    $winget = Get-Command winget.exe -ErrorAction SilentlyContinue
    if ($winget) {
        & $winget.Source install --id Python.Python.3.12 --exact --scope user --silent --accept-package-agreements --accept-source-agreements
        Start-Sleep 3
        $python = Resolve-Python
    }
}
if (-not $python) { throw 'No se encontro Python con sherpa_onnx. Instala Python 3.12/3.13 y vuelve a iniciar CODES.' }

New-Item -ItemType Directory -Force -Path (Join-Path $asr 'models') | Out-Null

Write-Host "==> Instalando/reparando sherpa-onnx con: $python" -ForegroundColor Cyan
& $python -m pip install --upgrade pip
if ($LASTEXITCODE -ne 0) { throw 'No se pudo actualizar pip.' }
& $python -m pip install --upgrade sherpa-onnx sherpa-onnx-bin
if ($LASTEXITCODE -ne 0) { throw 'No se pudo instalar sherpa-onnx.' }
& $python -c "import sherpa_onnx"
if ($LASTEXITCODE -ne 0) { throw 'sherpa_onnx no puede importarse tras la instalacion.' }

$scripts = & $python -c "import sysconfig;print(sysconfig.get_path('scripts'))" 2>$null | Select-Object -First 1
$exe = if ($scripts) { Join-Path $scripts 'sherpa-onnx-online-websocket-server.exe' } else { $null }
if (-not $exe -or -not (Test-Path $exe)) { throw 'No aparecio sherpa-onnx-online-websocket-server.exe.' }

$files = @('encoder.int8.onnx', 'decoder.int8.onnx', 'joiner.int8.onnx', 'tokens.txt')
$ok = $true
foreach ($f in $files) { if (-not (Test-Path (Join-Path $modelDir $f))) { $ok = $false } }

if (-not $ok) {
    if (Test-Path $modelDir) { Remove-Item $modelDir -Recurse -Force -ErrorAction SilentlyContinue }
    if (Test-Path $archive) { Remove-Item $archive -Force -ErrorAction SilentlyContinue }
    Write-Host '==> Descargando modelo ASR (~1 GB)...' -ForegroundColor Cyan
    Invoke-DescargaConProgreso -Uri $url -OutFile $archive -TimeoutSec 900
    if ((Get-Item $archive).Length -lt 100MB) { throw 'Descarga del modelo incompleta.' }
    Write-Host '==> Extrayendo modelo...' -ForegroundColor Cyan
    tar -xjf $archive -C (Join-Path $asr 'models')
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo extraer el modelo.' }
    Remove-Item $archive -Force -ErrorAction SilentlyContinue
}

Write-Host 'Instalacion ASR lista.' -ForegroundColor Green
