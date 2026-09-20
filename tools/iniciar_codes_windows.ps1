param(
    [string]$JavaPath,
    [string]$MavenBin
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$javaInstallRoots = @(
    'C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot\bin',
    'C:\Program Files\Microsoft\jdk-21\bin',
    'C:\Program Files\Java\jdk-21\bin',
    'C:\Program Files\Java\jdk-26\bin',
    'C:\Program Files\Java\latest\bin',
    'C:\Program Files\Eclipse Adoptium\bin',
    'C:\Program Files\OpenJDK\bin',
    'C:\Program Files\Amazon Corretto\bin'
)
foreach ($javaDir in $javaInstallRoots) {
    if (Test-Path $javaDir) {
        $env:Path = "$javaDir;$env:Path"
    }
}

$mavenInstallRoots = @(
    'C:\Users\Basti\maven\apache-maven-3.9.9\bin',
    'C:\Program Files\Apache\Maven\apache-maven-3.9.16\bin',
    'C:\Program Files\Apache\Maven\apache-maven-3.9.15\bin',
    (Join-Path $HOME '.maven\apache-maven-3.9.9\bin'),
    (Join-Path $HOME '.maven\maven-3.9.15\bin')
)
foreach ($mavenDir in $mavenInstallRoots) {
    if ($mavenDir -and (Test-Path $mavenDir)) {
        $env:Path = "$mavenDir;$env:Path"
    }
}

function Test-PortOpen {
    param([string]$HostName = '127.0.0.1', [int]$Port)
    try {
        $client = New-Object Net.Sockets.TcpClient
        $client.Connect($HostName, $Port)
        $client.Close()
        return $true
    }
    catch {
        return $false
    }
}

function Get-JavaInfo {
    function Parse-JavaMajorVersion([string]$Text) {
        if ([string]::IsNullOrWhiteSpace($Text)) { return $null }

        $match = [regex]::Match($Text, 'version\s+"?(\d+)')
        if ($match.Success) {
            return [int]$match.Groups[1].Value
        }

        $fallback = [regex]::Match($Text, '(\d+)')
        if ($fallback.Success) {
            return [int]$fallback.Groups[1].Value
        }

        return $null
    }

    function Get-JavaMajorVersion([string]$JavaPath) {
        try {
            $fileVersion = [System.Diagnostics.FileVersionInfo]::GetVersionInfo($JavaPath).ProductVersion
            $majorVersion = Parse-JavaMajorVersion -Text $fileVersion
            if ($majorVersion) { return $majorVersion }
        }
        catch {}

        try {
            $versionOutput = cmd.exe /d /c "`"$JavaPath`" -version 2^>^&1"
            return Parse-JavaMajorVersion -Text ($versionOutput -join ' ')
        }
        catch {
            return $null
        }
    }

    $explicitCandidates = @(
        'C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot\bin\java.exe',
        'C:\Program Files\Microsoft\jdk-21\bin\java.exe',
        'C:\Program Files\Java\jdk-21\bin\java.exe',
        'C:\Program Files\Java\jdk-26\bin\java.exe',
        'C:\Program Files\Java\latest\bin\java.exe',
        'C:\Program Files\Eclipse Adoptium\jdk-21\bin\java.exe',
        'C:\Program Files\OpenJDK\bin\java.exe',
        'C:\Program Files\Amazon Corretto\bin\java.exe'
    )

    foreach ($javaExe in $explicitCandidates | Where-Object { $_ -and (Test-Path $_) }) {
        try {
            $majorVersion = Get-JavaMajorVersion -JavaPath $javaExe
            if ($majorVersion) {
                $javaDir = Split-Path -Parent $javaExe
                if (-not ($env:Path -split ';' | Where-Object { $_ -eq $javaDir })) {
                    $env:Path = "$javaDir;$env:Path"
                }
                return @{ Exists = $true; Version = [int]$majorVersion; Path = $javaExe }
            }
        }
        catch {}
    }

    $javaCmd = Get-Command java -ErrorAction SilentlyContinue
    if ($javaCmd) {
        try {
            $majorVersion = Get-JavaMajorVersion -JavaPath $javaCmd.Source
            if ($majorVersion) {
                $javaDir = Split-Path -Parent $javaCmd.Source
                if (-not ($env:Path -split ';' | Where-Object { $_ -eq $javaDir })) {
                    $env:Path = "$javaDir;$env:Path"
                }
                return @{ Exists = $true; Version = [int]$majorVersion; Path = $javaCmd.Source }
            }
        }
        catch {}
    }

    return @{ Exists = $false; Version = 0; Path = $null }
}

# Java: el .bat ya resuelve la ruta; la detección interna queda como respaldo.
if ($JavaPath -and (Test-Path $JavaPath)) {
    $fileVersion = [System.Diagnostics.FileVersionInfo]::GetVersionInfo($JavaPath).ProductVersion
    $javaVersionMatch = [regex]::Match($fileVersion, '(\d+)')
    $javaInfo = @{
        Exists = $javaVersionMatch.Success
        Version = if ($javaVersionMatch.Success) { [int]$javaVersionMatch.Groups[1].Value } else { 0 }
        Path = $JavaPath
    }
    $env:Path = "$(Split-Path -Parent $JavaPath);$env:Path"
} else {
    $javaInfo = Get-JavaInfo
}

if (-not $javaInfo.Exists) {
    Write-Host 'Java 21 no encontrado. Instala JDK 21 antes de arrancar CODES.' -ForegroundColor Red
    Read-Host 'Presiona ENTER para cerrar'
    exit 1
}
if ($javaInfo.Version -lt 21) {
    Write-Host "Se encontró Java $($javaInfo.Version), pero CODES requiere Java 21 o superior." -ForegroundColor Red
    Read-Host 'Presiona ENTER para cerrar'
    exit 1
}

# Maven: el .bat ya resuelve la ruta; la detección interna queda como respaldo.
$mavenCandidates = @(
    (Join-Path $HOME '.maven\apache-maven-3.9.9\bin'),
    (Join-Path $HOME '.maven\maven-3.9.15\bin'),
    (Join-Path $HOME '.maven\maven-3.9.16\bin'),
    (Join-Path $HOME '.maven\maven-3.9.16\bin'),
    'C:\Program Files\Apache\Maven\apache-maven-3.9.16\bin',
    'C:\Users\Basti\maven\apache-maven-3.9.9\bin'
)
$mavenBinResolved = $MavenBin
if (-not $mavenBinResolved) {
    foreach ($candidate in $mavenCandidates) {
        if ($candidate -and (Test-Path (Join-Path $candidate 'mvn.cmd'))) {
            $mavenBinResolved = $candidate
            break
        }
    }
}
if (-not $mavenBinResolved) {
    $mvnCommand = Get-Command mvn -ErrorAction SilentlyContinue
    if ($mvnCommand) {
        $mavenBinResolved = Split-Path -Parent $mvnCommand.Source
    }
}
if ($mavenBinResolved) {
    $env:Path += ";$mavenBinResolved"
}
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    Write-Host 'Maven no está instalado ni disponible en PATH. Instálalo y vuelve a ejecutar CODES.' -ForegroundColor Red
    Read-Host 'Presiona ENTER para cerrar'
    exit 1
}

# Secretos persistentes para que cada arranque conserve la misma clave de cifrado.
$secretFile = Join-Path $root 'data\.codes-secrets.ps1'
New-Item -ItemType Directory -Force -Path (Join-Path $root 'data') | Out-Null
if (Test-Path $secretFile) {
    . $secretFile
} else {
    $jwt = [Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Maximum 256 }))
    $enc = [Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Maximum 256 }))
    @"
`$env:CODES_JWT_SECRET = '$jwt'
`$env:CODES_ENCRYPT_KEY = '$enc'
`$env:CODES_ADMIN_USER = 'admin'
`$env:CODES_ADMIN_PASSWORD = 'Admin123!'
"@ | Set-Content -Encoding UTF8 $secretFile
    . $secretFile
}

if (-not $env:CODES_ADMIN_USER) { $env:CODES_ADMIN_USER = 'admin' }
if (-not $env:CODES_ADMIN_PASSWORD) { $env:CODES_ADMIN_PASSWORD = 'Admin123!' }

Write-Host ''
Write-Host '============================================='
Write-Host ' CODES - Sistema de Despacho' -ForegroundColor Cyan
Write-Host '============================================='
Write-Host "Java:       $($javaInfo.Version) OK" -ForegroundColor Green
Write-Host "Maven:      OK" -ForegroundColor Green
Write-Host 'Spring Boot: http://localhost:8000' -ForegroundColor Green
Write-Host 'ASR:         ws://localhost:6006' -ForegroundColor Green
Write-Host ''

# ASR: solo se instala si faltan los modelos; si ya está ejecutándose, no se reinicia.
$asrScript = Join-Path $root 'tools\asr\start_asr_windows.ps1'
$asrInstallScript = Join-Path $root 'tools\asr\install_windows.ps1'
$asrProcess = $null
if (Test-Path $asrScript) {
    if (Test-PortOpen -HostName '127.0.0.1' -Port 6006) {
        Write-Host 'ASR ya está ejecutándose en localhost:6006; no se reinicia.' -ForegroundColor Yellow
    }
    else {
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
            Write-Host 'Faltan archivos del modelo ASR. Instalando solo lo necesario...' -ForegroundColor Yellow
            if (Test-Path $asrInstallScript) {
                & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $asrInstallScript
            }
            $missing = @($requiredFiles | Where-Object { -not (Test-Path $_) })
            if ($missing.Count -gt 0) {
                throw "No se pudo instalar el modelo ASR requerido. Archivos faltantes: $($missing -join ', ')"
            }
        }

        Write-Host 'Arrancando ASR local...' -ForegroundColor Yellow
        $asrProcess = Start-Process powershell.exe -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',$asrScript) -PassThru -WindowStyle Minimized
        for ($i = 0; $i -lt 30; $i++) {
            if (Test-PortOpen -HostName '127.0.0.1' -Port 6006) { break }
            Start-Sleep -Seconds 1
        }
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

