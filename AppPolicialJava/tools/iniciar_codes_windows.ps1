$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# Maven puede vivir en la instalación persistente del usuario o en una ruta global.
$mavenCandidates = @(
    (Join-Path $HOME '.maven\maven-3.9.15\bin'),
    'C:\Program Files\Apache\Maven\apache-maven-3.9.16\bin'
)
foreach ($mavenBin in $mavenCandidates) {
    if (Test-Path (Join-Path $mavenBin 'mvn.cmd')) {
        $env:Path += ";$mavenBin"
        break
    }
}

if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    Write-Host 'No se encontró Maven en C:\Program Files\Apache\Maven\apache-maven-3.9.16.' -ForegroundColor Red
    Read-Host 'Presiona ENTER para cerrar'
    exit 1
}

# Secretos persistentes para que cada arranque conserve la misma clave de cifrado.
# Esto evita que los audios cifrados queden ilegibles al cerrar PowerShell.
$secretFile = Join-Path $root 'data\.codes-secrets.ps1'
New-Item -ItemType Directory -Force -Path (Join-Path $root 'data') | Out-Null
if (Test-Path $secretFile) {
    . $secretFile
} else {
   $jwt = [Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Maximum 256 }))
    $enc = [Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Maximum 256 }))
    @"
`$env:APP_POLICIAL_JWT_SECRET = '$jwt'
`$env:APP_POLICIAL_ENCRYPT_KEY = '$enc'
`$env:APP_POLICIAL_ADMIN_USER = 'admin'
`$env:APP_POLICIAL_ADMIN_PASSWORD = 'Admin123!'
"@ | Set-Content -Encoding UTF8 $secretFile
    . $secretFile
}

if (-not $env:APP_POLICIAL_ADMIN_USER) { $env:APP_POLICIAL_ADMIN_USER = 'admin' }
if (-not $env:APP_POLICIAL_ADMIN_PASSWORD) { $env:APP_POLICIAL_ADMIN_PASSWORD = 'Admin123!' }

Write-Host ''
Write-Host '============================================='
Write-Host ' CODES - Sistema de Despacho' -ForegroundColor Cyan
Write-Host '============================================='
Write-Host 'Spring Boot: http://localhost:8000' -ForegroundColor Green
Write-Host 'ASR:         ws://localhost:6006' -ForegroundColor Green
Write-Host ''
Write-Host 'Sherpa se inicia automáticamente con CODES.' -ForegroundColor Yellow
# Arrancar Sherpa en segundo plano y esperar a que abra el puerto WebSocket.
$asrScript = Join-Path $root 'tools\asr\start_asr_windows.ps1'
$asrInstallScript = Join-Path $root 'tools\asr\install_windows.ps1'
$asrProcess = $null
if (Test-Path $asrScript) {
    $modelName = 'sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11'
    $modelDir = Join-Path $root "tools\asr\models\$modelName"
    $requiredFiles = @(
        (Join-Path $modelDir 'encoder.int8.onnx'),
        (Join-Path $modelDir 'decoder.int8.onnx'),
        (Join-Path $modelDir 'joiner.int8.onnx'),
        (Join-Path $modelDir 'tokens.txt')
    )
    $missing = @($requiredFiles | Where-Object { -not (Test-Path $_) })
    if ($missing.Count -gt 0) {
        Write-Host 'Faltan archivos del modelo ASR; ejecutando instalación automática...' -ForegroundColor Yellow
        if (Test-Path $asrInstallScript) {
            & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $asrInstallScript
        }
        $missing = @($requiredFiles | Where-Object { -not (Test-Path $_) })
        if ($missing.Count -gt 0) {
            throw "No se pudo instalar el modelo ASR requerido. Archivos faltantes: $($missing -join ', ')"
        }
    }

    $asrProcess = Start-Process powershell.exe -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',$asrScript) -PassThru -WindowStyle Minimized
    for ($i = 0; $i -lt 30; $i++) {
        try { $c = New-Object Net.Sockets.TcpClient; $c.Connect('127.0.0.1',6006); $c.Close(); break } catch {}
        Start-Sleep -Seconds 1
    }
}
Write-Host 'Al detener Spring Boot, CODES detiene su proceso ASR.' -ForegroundColor Yellow
Write-Host ''

# Abre el navegador cuando el servidor web ya esté escuchando.
Start-Job -ScriptBlock {
    for ($i = 0; $i -lt 60; $i++) {
        try {
            $c = New-Object Net.Sockets.TcpClient
            $c.Connect('127.0.0.1', 8000)
            $c.Close()
            Start-Process 'http://localhost:8000'
            break
        } catch {}
        Start-Sleep -Seconds 1
    }
} | Out-Null

try {
    mvn spring-boot:run
}
finally {
    if ($asrProcess -and -not $asrProcess.HasExited) {
        try { taskkill.exe /PID $asrProcess.Id /T /F | Out-Null } catch {}
    }
    Write-Host ''
    Write-Host 'CODES detenido.' -ForegroundColor Yellow
}
