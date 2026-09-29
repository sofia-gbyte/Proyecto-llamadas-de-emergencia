$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$mapsEnvFile = Join-Path $projectRoot '.env.maps'
$mapsEnvExample = Join-Path $projectRoot '.env.maps.example'

if (-not (Test-Path $mapsEnvFile) -and (Test-Path $mapsEnvExample)) {
    Copy-Item $mapsEnvExample $mapsEnvFile
    Write-Host 'Se creo .env.maps desde la plantilla. Cambia MAPS_NOMINATIM_PASSWORD antes de importar datos.' -ForegroundColor Yellow
}

function Find-DockerCli {
    $command = Get-Command docker.exe -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }

    $candidates = @(
        (Join-Path $env:LOCALAPPDATA 'Programs\DockerDesktop\resources\bin\docker.exe'),
        (Join-Path $env:ProgramFiles 'Docker\Docker\resources\bin\docker.exe')
    )
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path $candidate)) { return $candidate }
    }
    return $null
}

function Test-DockerEngine([string]$DockerCli) {
    if (-not $DockerCli) { return $false }
    & $DockerCli info *> $null
    return $LASTEXITCODE -eq 0
}

$dockerCli = Find-DockerCli
if (-not $dockerCli) {
    Write-Host 'Docker Desktop no esta instalado. Se recomienda instalarlo en modo por usuario.' -ForegroundColor Yellow
    Write-Host 'Docker Desktop puede ser gratuito para uso personal/educativo individual y negocios con menos de 250 empleados Y menos de USD 10M de ingresos anuales.' -ForegroundColor Yellow
    Write-Host 'Uso gubernamental u organizaciones fuera de esos limites requieren suscripcion. Confirma elegibilidad: https://www.docker.com/pricing/' -ForegroundColor Yellow
    Write-Host 'La instalacion no acepta automaticamente el acuerdo. Docker mostrara sus terminos al iniciar.' -ForegroundColor Yellow
    $confirmacion = Read-Host 'Instalar Docker Desktop con winget ahora? Escribe S para continuar'
    if ($confirmacion -notmatch '^(s|S)$') {
        Write-Host 'Instalacion cancelada. CODES seguira funcionando sin los servicios de mapa locales.' -ForegroundColor DarkYellow
        exit 0
    }

    $winget = Get-Command winget.exe -ErrorAction SilentlyContinue
    if (-not $winget) {
        Write-Host 'No se encontro winget. Instala Docker Desktop manualmente desde https://docs.docker.com/desktop/setup/install/windows-install/.' -ForegroundColor Red
        exit 1
    }

    & $winget.Source install --id Docker.DockerDesktop --exact --scope user
    if ($LASTEXITCODE -ne 0) {
        Write-Host 'winget no pudo completar la instalacion. Prueba la instalacion manual por usuario.' -ForegroundColor Red
        exit 1
    }

    $dockerCli = Find-DockerCli
}

if (-not $dockerCli) {
    Write-Host 'Docker Desktop se instalo, pero no se encontro docker.exe. Cierra y vuelve a abrir la terminal y reintenta.' -ForegroundColor Red
    exit 1
}

if (-not (Test-DockerEngine $dockerCli)) {
    $desktopCandidates = @(
        (Join-Path $env:LOCALAPPDATA 'Programs\DockerDesktop\Docker Desktop.exe'),
        (Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe')
    )
    $desktopExe = $desktopCandidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
    if ($desktopExe) {
        Start-Process -FilePath $desktopExe
    } else {
        Write-Host 'Inicia Docker Desktop desde el menu Inicio.' -ForegroundColor Yellow
    }

    Read-Host 'Acepta los terminos en Docker Desktop y espera a que indique que el motor esta listo. Luego pulsa ENTER'
    if (-not (Test-DockerEngine $dockerCli)) {
        Write-Host 'Docker Engine aun no responde. Abre Docker Desktop, espera a que termine de iniciar y vuelve a ejecutar esta opcion.' -ForegroundColor Red
        exit 1
    }
}

& $dockerCli compose version
if ($LASTEXITCODE -ne 0) {
    Write-Host 'Docker Compose no esta disponible. Actualiza Docker Desktop y vuelve a intentar.' -ForegroundColor Red
    exit 1
}

Write-Host ''
Write-Host 'Docker Desktop y Docker Compose estan listos.' -ForegroundColor Green
Write-Host "Configuracion local: $mapsEnvFile" -ForegroundColor DarkGray
Write-Host 'Siguiente paso: sigue la seccion "Mapa y servicios cartograficos" en OPERACION_LOCAL.md para importar el PBF y levantar los mapas.' -ForegroundColor Cyan
