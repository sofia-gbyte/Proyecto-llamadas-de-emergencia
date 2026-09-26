$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$asr = Join-Path $root 'tools\asr'
$modelName = 'sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11'
$modelDir = Join-Path $asr "models\$modelName"
$archive = Join-Path $asr "$modelName.tar.bz2"
$url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$modelName.tar.bz2"

# Tamaño mínimo esperado de cada archivo del modelo (bytes). Sirve para
# detectar un archivo truncado por una descarga o extracción interrumpida,
# que de otro modo "existe" pero está corrupto y nunca se vuelve a intentar
# (ese era el motivo más probable de una instalación que "queda a medias").
$requiredFiles = @{
    'encoder.int8.onnx' = 50MB
    'decoder.int8.onnx' = 1MB
    'joiner.int8.onnx'  = 100KB
    'tokens.txt'        = 1KB
}

function Test-ModeloCompleto {
    foreach ($nombre in $requiredFiles.Keys) {
        $ruta = Join-Path $modelDir $nombre
        if (-not (Test-Path $ruta)) { return $false }
        if ((Get-Item $ruta).Length -lt $requiredFiles[$nombre]) { return $false }
    }
    return $true
}

New-Item -ItemType Directory -Force -Path (Join-Path $asr 'models') | Out-Null

Write-Host '==> Instalando sherpa-onnx (CPU) ...' -ForegroundColor Cyan
try {
    python -m pip install --upgrade pip *> $null
    python -m pip install --upgrade sherpa-onnx sherpa-onnx-bin
} catch {
    Write-Host "Aviso: no se pudo instalar/actualizar sherpa-onnx via pip ($($_.Exception.Message))." -ForegroundColor Yellow
    Write-Host 'Si ya estaba instalado antes, esto no es necesariamente un problema; se continúa.' -ForegroundColor Yellow
}

if (Test-ModeloCompleto) {
    Write-Host 'El modelo ASR ya está completo; no se vuelve a descargar.' -ForegroundColor Green
} else {
    # Si había una carpeta de un intento anterior a medias, se limpia para no
    # mezclar archivos viejos truncados con la nueva extracción.
    if (Test-Path $modelDir) {
        Write-Host 'Se detectó una instalación previa incompleta del modelo ASR; se limpia antes de reintentar.' -ForegroundColor Yellow
        Remove-Item $modelDir -Recurse -Force -ErrorAction SilentlyContinue
    }
    if (Test-Path $archive) { Remove-Item $archive -Force -ErrorAction SilentlyContinue }

    $intentos = 0
    $maxIntentos = 3
    $exito = $false
    while (-not $exito -and $intentos -lt $maxIntentos) {
        $intentos++
        try {
            Write-Host "==> Descargando modelo ASR (~1 GB comprimido, intento $intentos/$maxIntentos) ..." -ForegroundColor Cyan
            $progressPreferenceAnterior = $ProgressPreference
            $ProgressPreference = 'SilentlyContinue'  # acelera Invoke-WebRequest notablemente
            Invoke-WebRequest -Uri $url -OutFile $archive -TimeoutSec 600
            $ProgressPreference = $progressPreferenceAnterior

            if (-not (Test-Path $archive) -or (Get-Item $archive).Length -lt 100MB) {
                throw "El archivo descargado es demasiado pequeño (descarga incompleta)."
            }

            Write-Host '==> Extrayendo modelo ...' -ForegroundColor Cyan
            tar -xjf $archive -C (Join-Path $asr 'models')
            if ($LASTEXITCODE -ne 0) { throw "tar terminó con código $LASTEXITCODE (archivo posiblemente corrupto)." }

            if (-not (Test-ModeloCompleto)) {
                throw "La extracción terminó pero faltan archivos del modelo o están incompletos."
            }
            $exito = $true
        } catch {
            Write-Host "Intento $intentos fallido: $($_.Exception.Message)" -ForegroundColor Red
            if (Test-Path $archive) { Remove-Item $archive -Force -ErrorAction SilentlyContinue }
            if (Test-Path $modelDir) { Remove-Item $modelDir -Recurse -Force -ErrorAction SilentlyContinue }
            if ($intentos -lt $maxIntentos) {
                Write-Host 'Reintentando en 5 segundos...' -ForegroundColor Yellow
                Start-Sleep -Seconds 5
            }
        }
    }
    if (Test-Path $archive) { Remove-Item $archive -Force -ErrorAction SilentlyContinue }

    if (-not $exito) {
        throw "No se pudo completar la descarga/instalación del modelo ASR tras $maxIntentos intentos. Revisa la conexión a internet. CODES puede seguir arrancando sin transcripción de voz (ver README, sección ASR)."
    }
}

Write-Host ''
Write-Host 'Instalación lista.' -ForegroundColor Green
Write-Host "Modelo: $modelDir"
Write-Host 'Ejecuta .\tools\asr\start_asr_windows.ps1 para iniciar el servidor.'
