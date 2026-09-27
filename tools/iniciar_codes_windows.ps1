param(
    [string]$JavaPath,
    [string]$MavenBin
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}
$MavenBin = if ($MavenBin) { $MavenBin.Trim().Trim('"') } else { $null }
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

# Si CODES se cerró antes cliqueando la X de la ventana (en vez de dejar que
# el script termine solo), Windows mata la consola de golpe y el "finally"
# que libera los puertos nunca alcanza a correr. Eso deja procesos huérfanos
# de Java (8000) y/o Python/sherpa-onnx (6006) todavía corriendo, y el
# siguiente inicio los confunde con una instancia sana, saltándose por
# completo el arranque real (incluida la revisión/descarga del modelo ASR).
# Por eso, ANTES de decidir nada, nos aseguramos de partir limpios: si hay
# algo escuchando en 8000 o 6006 que no sea una instancia sana de CODES,
# lo cerramos.
function Stop-ProcesoHuerfanoEnPuerto {
    param([int]$Port, [string]$Etiqueta)
    try {
        $conexiones = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
        foreach ($conexion in $conexiones) {
            $procId = $conexion.OwningProcess
            if ($procId -and $procId -ne $PID) {
                try {
                    $proc = Get-Process -Id $procId -ErrorAction SilentlyContinue
                    $nombre = if ($proc) { $proc.ProcessName } else { "PID $procId" }
                    Write-Host "Se encontró un proceso ($nombre) de una sesión anterior de CODES todavía escuchando en el puerto $Port ($Etiqueta). Cerrándolo..." -ForegroundColor Yellow
                    taskkill.exe /PID $procId /T /F | Out-Null
                }
                catch {}
            }
        }
    }
    catch {
        # Get-NetTCPConnection puede no estar disponible en algunos Windows;
        # si falla, seguimos igual con la lógica normal de detección de puertos.
    }
}

Write-Host 'Verificando que no queden procesos de una sesión anterior de CODES...' -ForegroundColor DarkGray
Stop-ProcesoHuerfanoEnPuerto -Port 8000 -Etiqueta 'Spring Boot'
Stop-ProcesoHuerfanoEnPuerto -Port 6006 -Etiqueta 'ASR / sherpa-onnx'
Start-Sleep -Milliseconds 500

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

    $dynamicCandidates = @()
    foreach ($javaRoot in @($env:ProgramFiles, $env:LOCALAPPDATA)) {
        foreach ($vendorRoot in @('Microsoft', 'Java', 'Eclipse Adoptium')) {
            $rootPath = Join-Path $javaRoot $vendorRoot
            if (Test-Path $rootPath) {
                $dynamicCandidates += Get-ChildItem $rootPath -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue |
                    ForEach-Object { Join-Path $_.FullName 'bin\java.exe' }
            }
        }
    }

    foreach ($javaExe in @($explicitCandidates + $dynamicCandidates) | Where-Object { $_ -and (Test-Path $_) }) {
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

function Install-MavenIfMissing {
    $version = '3.9.9'
    $installRoot = Join-Path $HOME ".maven\apache-maven-$version"
    $binDir = Join-Path $installRoot 'bin'
    $mvnCmd = Join-Path $binDir 'mvn.cmd'
    if (Test-Path $mvnCmd) { return $binDir }

    $archive = Join-Path $HOME ".maven\apache-maven-$version-bin.zip"
    $url = "https://archive.apache.org/dist/maven/maven-3/$version/binaries/apache-maven-$version-bin.zip"
    try {
        New-Item -ItemType Directory -Force -Path (Join-Path $HOME '.maven') | Out-Null
        Write-Host "Maven no encontrado. Descargando Maven $version..." -ForegroundColor Yellow
        $ProgressPreference = 'SilentlyContinue'
        Invoke-WebRequest -Uri $url -OutFile $archive -TimeoutSec 180
        if (-not (Test-Path $archive) -or (Get-Item $archive).Length -lt 5MB) {
            throw 'La descarga de Maven está incompleta.'
        }
        Expand-Archive -Path $archive -DestinationPath (Join-Path $HOME '.maven') -Force
        Remove-Item $archive -Force -ErrorAction SilentlyContinue
        if (-not (Test-Path $mvnCmd)) { throw "No se encontró mvn.cmd después de extraer Maven en $installRoot." }
        Write-Host "Maven instalado en $installRoot" -ForegroundColor Green
        return $binDir
    }
    catch {
        if (Test-Path $archive) { Remove-Item $archive -Force -ErrorAction SilentlyContinue }
        Write-Host "No se pudo instalar Maven automáticamente: $($_.Exception.Message)" -ForegroundColor Red
        return $null
    }
}

function Install-Java21IfMissing {
    try {
        $winget = Get-Command winget.exe -ErrorAction Stop
        Write-Host 'Java 21 no encontrado. Instalando Microsoft OpenJDK 21...' -ForegroundColor Yellow
        & $winget.Source install --id Microsoft.OpenJDK.21 --exact --scope user --silent --accept-package-agreements --accept-source-agreements
        if ($LASTEXITCODE -ne 0) { throw "winget terminó con código $LASTEXITCODE." }

        $javaInfoAfterInstall = Get-JavaInfo
        if (-not $javaInfoAfterInstall.Exists -or $javaInfoAfterInstall.Version -lt 21) {
            throw 'Java fue instalado, pero todavía no se encontró un JDK 21 en esta sesión.'
        }
        Write-Host "Java $($javaInfoAfterInstall.Version) instalado correctamente." -ForegroundColor Green
        return $javaInfoAfterInstall
    }
    catch {
        Write-Host "No se pudo instalar Java automáticamente: $($_.Exception.Message)" -ForegroundColor Red
        return $null
    }
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
    $javaInfo = Install-Java21IfMissing
}
if ($javaInfo.Version -lt 21) {
    Write-Host "Se encontró Java $($javaInfo.Version), pero CODES requiere Java 21 o superior. Instalando JDK 21..." -ForegroundColor Yellow
    $javaInfo = Install-Java21IfMissing
}
if (-not $javaInfo -or -not $javaInfo.Exists -or $javaInfo.Version -lt 21) {
    Write-Host 'Java 21 no está disponible. Revisa winget o instala un JDK 21 manualmente.' -ForegroundColor Red
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
$mavenBinResolved = if ($MavenBin -and (Test-Path (Join-Path $MavenBin 'mvn.cmd'))) { $MavenBin } else { $null }
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
if (-not $mavenBinResolved) {
    $mavenBinResolved = Install-MavenIfMissing
}
if ($mavenBinResolved) {
    $env:Path = "$mavenBinResolved;$env:Path"
}
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    Write-Host 'Maven no está instalado ni disponible en PATH. Revisa la conexión a Internet o instala Maven manualmente.' -ForegroundColor Red
    Read-Host 'Presiona ENTER para cerrar'
    exit 1
}

# Secretos persistentes para que cada arranque conserve la misma clave de cifrado.
$secretFile = Join-Path $root 'data\.codes-secrets.ps1'
New-Item -ItemType Directory -Force -Path (Join-Path $root 'data') | Out-Null
if (Test-Path $secretFile) {
    . $secretFile
} else {
    function New-SecureBase64([int]$bytes = 32) {
        $buffer = New-Object byte[] $bytes
        [System.Security.Cryptography.RandomNumberGenerator]::Fill($buffer)
        return [Convert]::ToBase64String($buffer)
    }

    function Get-SecureIndex([int]$max) {
        $buffer = New-Object byte[] 4
        [System.Security.Cryptography.RandomNumberGenerator]::Fill($buffer)
        return [BitConverter]::ToUInt32($buffer, 0) % $max
    }

    function New-SecureAdminPassword {
        $upper = 'ABCDEFGHJKLMNPQRSTUVWXYZ'
        $lower = 'abcdefghijkmnopqrstuvwxyz'
        $digits = '23456789'
        $symbols = '!@#$%^&*-_=+'
        $all = $upper + $lower + $digits + $symbols
        $chars = @()
        $chars += $upper[(Get-SecureIndex $upper.Length)]
        $chars += $lower[(Get-SecureIndex $lower.Length)]
        $chars += $digits[(Get-SecureIndex $digits.Length)]
        $chars += $symbols[(Get-SecureIndex $symbols.Length)]
        for ($i = $chars.Count; $i -lt 20; $i++) { $chars += $all[(Get-SecureIndex $all.Length)] }
        for ($i = $chars.Count - 1; $i -gt 0; $i--) {
            $j = Get-SecureIndex ($i + 1)
            $tmp = $chars[$i]; $chars[$i] = $chars[$j]; $chars[$j] = $tmp
        }
        return -join $chars
    }

    $jwt = New-SecureBase64 32
    $enc = New-SecureBase64 32
    $adminPassword = New-SecureAdminPassword
    @"
`$env:CODES_JWT_SECRET = '$jwt'
`$env:CODES_ENCRYPT_KEY = '$enc'
`$env:CODES_ADMIN_USER = 'admin'
`$env:CODES_ADMIN_PASSWORD = '$adminPassword'
# Claves REALES de Cloudflare Turnstile (opcional). Sin ellas se usan las claves de PRUEBA.
# `$env:CODES_TURNSTILE_SITE_KEY = 'tu-site-key'
# `$env:CODES_TURNSTILE_SECRET_KEY = 'tu-secret-key'
"@ | Set-Content -Encoding UTF8 $secretFile
    . $secretFile
    Write-Host ''
    Write-Host 'Administrador inicial creado para este entorno.' -ForegroundColor Green
    Write-Host "Usuario: $env:CODES_ADMIN_USER" -ForegroundColor Green
    Write-Host "Contraseña inicial: $env:CODES_ADMIN_PASSWORD" -ForegroundColor Yellow
    Write-Host 'Guárdala y cámbiala desde CODES después del primer inicio.' -ForegroundColor Yellow
}

if (-not $env:CODES_ADMIN_USER) { $env:CODES_ADMIN_USER = 'admin' }
if (-not $env:CODES_ADMIN_PASSWORD) {
    throw 'CODES_ADMIN_PASSWORD no está configurada. Elimina data\.codes-secrets.ps1 y vuelve a iniciar CODES para generar una contraseña segura.'
}
if ([string]::IsNullOrWhiteSpace($env:CODES_JWT_SECRET)) {
    throw 'CODES_JWT_SECRET no quedó cargada desde data\.codes-secrets.ps1. Revisa ese archivo antes de iniciar CODES.'
}

# Cloudflare Turnstile (captcha del login y del registro).
# - Claves reales: agrégalas a data\.codes-secrets.ps1 (o como variables de entorno de Windows):
#       $env:CODES_TURNSTILE_SITE_KEY   = 'tu-site-key'
#       $env:CODES_TURNSTILE_SECRET_KEY = 'tu-secret-key'
# - Si no hay claves NO se pregunta nada: se usan las claves de PRUEBA que publica Cloudflare
#   (el widget se ve y funciona normal, pero siempre aprueba). Solo para desarrollo/demo.
$turnstileModoPrueba = $false
if ([string]::IsNullOrWhiteSpace($env:CODES_TURNSTILE_SITE_KEY) -or [string]::IsNullOrWhiteSpace($env:CODES_TURNSTILE_SECRET_KEY)) {
    $env:CODES_TURNSTILE_SITE_KEY = '1x00000000000000000000AA'
    $env:CODES_TURNSTILE_SECRET_KEY = '1x0000000000000000000000000000000AA'
    $turnstileModoPrueba = $true
}

Write-Host ''
Write-Host '============================================='
Write-Host ' CODES - Sistema de Despacho' -ForegroundColor Cyan
Write-Host '============================================='
Write-Host "Java:       $($javaInfo.Version) OK" -ForegroundColor Green
Write-Host "Maven:      OK" -ForegroundColor Green
if ($turnstileModoPrueba) {
    Write-Host 'Captcha:    Turnstile en MODO PRUEBA (siempre aprueba; no usar en producción)' -ForegroundColor Yellow
} else {
    Write-Host 'Captcha:    Turnstile OK (claves reales)' -ForegroundColor Green
}
Write-Host 'Spring Boot: http://localhost:8000' -ForegroundColor Green
Write-Host 'ASR:         ws://localhost:6006' -ForegroundColor Green
Write-Host ''

if (Test-PortOpen -HostName '127.0.0.1' -Port 8000) {
    Write-Host 'AVISO: el puerto 8000 ya está en uso (¿otra instancia de CODES sigue corriendo?).' -ForegroundColor Yellow
    Write-Host 'Puedes abrir http://localhost:8000 directamente, o cerrar ese proceso antes de continuar.' -ForegroundColor Yellow
    Write-Host ''
}

# Verificacion real del backend: no basta con que el puerto 8000 este abierto.
# Consultamos /api/health, que es publico, antes de abrir el navegador.
Write-Host 'Iniciando Spring Boot y verificando /api/health...' -ForegroundColor Cyan
Write-Host 'Los errores de arranque se guardaran en logs\spring-boot.log' -ForegroundColor DarkGray
New-Item -ItemType Directory -Force -Path (Join-Path $root 'logs') | Out-Null
$springLog = Join-Path $root 'logs\spring-boot.log'
$mvnCmd = Join-Path $mavenBinResolved 'mvn.cmd'

function Test-SpringHealth {
    try {
        $response = Invoke-WebRequest -Uri 'http://127.0.0.1:8000/api/health' -UseBasicParsing -TimeoutSec 3
        if ($response.StatusCode -eq 200) {
            try { return (($response.Content | ConvertFrom-Json).status -eq 'ok') } catch { return $true }
        }
    } catch {}
    return $false
}

# Si ya hay una instancia sana, no lanzamos otra.
if (Test-SpringHealth) {
    Write-Host 'Servidor CODES ya estaba activo y responde correctamente.' -ForegroundColor Green
    $springProcess = $null
} else {
    if (Test-Path $springLog) { Remove-Item $springLog -Force -ErrorAction SilentlyContinue }
    if (-not (Test-Path $mvnCmd)) {
        Write-Host "No se encontro Maven en: $mvnCmd" -ForegroundColor Red
        exit 1
    }

    Write-Host 'Levantando Spring Boot en segundo plano...' -ForegroundColor Cyan
    $springArgs = @('/d','/c', ('call "{0}" spring-boot:run > "{1}" 2>&1' -f $mvnCmd, $springLog))
    $springProcess = Start-Process -FilePath 'cmd.exe' -ArgumentList $springArgs -WorkingDirectory $root -PassThru -WindowStyle Hidden

    $serverOk = $false
    for ($i = 0; $i -lt 120; $i++) {
        Start-Sleep -Seconds 1
        if (Test-SpringHealth) { $serverOk = $true; break }
        if ($springProcess.HasExited) { break }
        if (($i + 1) % 10 -eq 0) { Write-Host "Esperando servidor... $($i+1)s" -ForegroundColor DarkGray }
    }

    if (-not $serverOk) {
        Write-Host ''
        Write-Host 'ERROR: Spring Boot NO pudo iniciar o /api/health no responde.' -ForegroundColor Red
        Write-Host "Revisa el archivo: $springLog" -ForegroundColor Yellow
        if ($springProcess -and -not $springProcess.HasExited) {
            try { taskkill.exe /PID $springProcess.Id /T /F | Out-Null } catch {}
        }
        if (Test-Path $springLog) {
            Write-Host '--- Ultimas lineas de spring-boot.log ---' -ForegroundColor Yellow
            Get-Content $springLog -Tail 30
            Write-Host '--- Fin del log ---' -ForegroundColor Yellow
        }
        exit 1
    }

    Write-Host 'Servidor CODES: OK' -ForegroundColor Green
}

$staticIndex = Join-Path $root 'target\classes\static\index.html'
if (-not (Test-Path $staticIndex)) {
    Write-Host 'Faltan los recursos compilados de la interfaz. Regenerando recursos web...' -ForegroundColor Yellow
    & $mvnCmd process-resources
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $staticIndex)) {
        Write-Host 'ERROR: no se pudieron recuperar los recursos web de CODES.' -ForegroundColor Red
        exit 1
    }
}

# ASR: se verifica después de que Spring Boot esté sano.
$asrScript = Join-Path $root 'tools\asr\start_asr_windows.ps1'
$asrProcess = $null

Write-Host ''
Write-Host 'Verificando ASR en localhost:6006...' -ForegroundColor Cyan
if (Test-PortOpen -HostName '127.0.0.1' -Port 6006) {
    Write-Host 'ASR: OK (localhost:6006)' -ForegroundColor Green
}
elseif (Test-Path $asrScript) {
    try {
        $asrStdoutLog = Join-Path $root 'logs\asr-stdout.log'
        $asrStderrLog = Join-Path $root 'logs\asr-stderr.log'
        New-Item -ItemType Directory -Force -Path (Join-Path $root 'logs') | Out-Null
        if (Test-Path $asrStdoutLog) { Remove-Item $asrStdoutLog -Force -ErrorAction SilentlyContinue }
        if (Test-Path $asrStderrLog) { Remove-Item $asrStderrLog -Force -ErrorAction SilentlyContinue }

        Write-Host 'El primer inicio puede descargar e instalar sherpa-onnx y el modelo ASR; esto puede tardar varios minutos.' -ForegroundColor Yellow
        Write-Host 'Iniciando ASR en segundo plano...' -ForegroundColor Cyan
        $asrProcess = Start-Process -FilePath 'powershell.exe' `
            -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',$asrScript) `
            -WorkingDirectory $root `
            -RedirectStandardOutput $asrStdoutLog `
            -RedirectStandardError $asrStderrLog `
            -PassThru `
            -WindowStyle Hidden

        # El ASR corre oculto y con su salida redirigida a logs\asr-*.log, así que
        # el avance de instalación (p. ej. la descarga del modelo, ~1 GB) no se ve
        # en ninguna parte por defecto. Vamos leyendo la última línea del log y
        # reflejándola aquí, en la consola que la persona sí está mirando.
        $asrOk = $false
        $ultimaLineaAsr = ''
        for ($i = 1; $i -le 300; $i++) {
            Start-Sleep -Seconds 1
            if (Test-PortOpen -HostName '127.0.0.1' -Port 6006) { $asrOk = $true; break }
            if ($asrProcess.HasExited) { break }
            if (Test-Path $asrStdoutLog) {
                $lineaActual = Get-Content $asrStdoutLog -Tail 1 -ErrorAction SilentlyContinue
                if ($lineaActual -and $lineaActual -ne $ultimaLineaAsr) {
                    Write-Host "   $lineaActual" -ForegroundColor DarkGray
                    $ultimaLineaAsr = $lineaActual
                }
            }
            if (-not $ultimaLineaAsr -and ($i % 30) -eq 0) { Write-Host "Preparando ASR... ${i}s" -ForegroundColor DarkGray }
        }
        if ($asrOk) {
            Write-Host 'ASR: OK (localhost:6006)' -ForegroundColor Green
        } else {
            Write-Host 'ASR: no respondió tras la espera. CODES continuará activo.' -ForegroundColor Yellow
            if ($asrProcess -and $asrProcess.HasExited) { Write-Host "El proceso ASR terminó con código $($asrProcess.ExitCode)." -ForegroundColor Red }
            if (Test-Path $asrStdoutLog) { Write-Host '--- ASR stdout ---' -ForegroundColor Yellow; Get-Content $asrStdoutLog -Tail 30 }
            if (Test-Path $asrStderrLog) { Write-Host '--- ASR stderr ---' -ForegroundColor Yellow; Get-Content $asrStderrLog -Tail 30 }
            Write-Host 'Revisa logs\asr-stderr.log, logs\asr-stdout.log y tools\asr\asr.log.' -ForegroundColor Yellow
        }
    } catch {
        Write-Host "ASR: no se pudo iniciar: $($_.Exception.Message)" -ForegroundColor Yellow
        Write-Host 'CODES continuará activo.' -ForegroundColor Yellow
    }
} else {
    Write-Host 'ASR: script de inicio no encontrado. CODES continuará activo.' -ForegroundColor Yellow
}

Write-Host 'Abriendo CODES en el navegador...' -ForegroundColor Cyan
Start-Process 'http://localhost:8000'

Write-Host ' Servidor: http://localhost:8000/api/health' -ForegroundColor Green
if (Test-SpringHealth) { Write-Host ' Servidor: OK' -ForegroundColor Green } else { Write-Host ' Servidor: ERROR' -ForegroundColor Red }
if (Test-PortOpen -HostName '127.0.0.1' -Port 6006) { Write-Host ' ASR: OK (6006)' -ForegroundColor Green } else { Write-Host ' ASR: SIN CONEXION (6006)' -ForegroundColor Yellow }
Write-Host '=============================================' -ForegroundColor Cyan
Write-Host 'Cierra esta ventana para detener CODES.' -ForegroundColor DarkGray

# Este watchdog es independiente del proceso de PowerShell que muestra esta
# ventana. Si la usuaria pulsa X y Windows termina esta consola abruptamente,
# el watchdog detecta que este PID desapareció y mata Spring + ASR igualmente.
$watchdog = Join-Path $root 'tools\codes_watchdog.ps1'
if (Test-Path $watchdog) {
    try {
        Start-Process -FilePath 'powershell.exe' `
            -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',$watchdog,'-ParentPid',$PID) `
            -WorkingDirectory $root `
            -WindowStyle Hidden | Out-Null
    } catch {}
}

try {
    while (Test-SpringHealth) {
        Start-Sleep -Seconds 2
        if ($springProcess -and $springProcess.HasExited) { break }
    }
}
finally {
    if ($asrProcess -and -not $asrProcess.HasExited) { try { taskkill.exe /PID $asrProcess.Id /T /F | Out-Null } catch {} }
    if ($springProcess -and -not $springProcess.HasExited) { try { taskkill.exe /PID $springProcess.Id /T /F | Out-Null } catch {} }
    Start-Sleep -Milliseconds 500
    foreach ($port in @(6006,8000)) {
        try {
            $connections = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
            foreach ($c in $connections) { if ($c.OwningProcess -and $c.OwningProcess -ne $PID) { taskkill.exe /PID $c.OwningProcess /T /F | Out-Null } }
        } catch {}
    }
    Write-Host ''
    Write-Host 'CODES detenido. ASR y puertos liberados.' -ForegroundColor Yellow
}
