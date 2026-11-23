$ErrorActionPreference = 'Stop'

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$port = 8001
$maven = Get-Command mvn -ErrorAction SilentlyContinue
$appJar = Join-Path (Join-Path $projectRoot 'target') 'codes-1.1.5.jar'
$java = Get-Command java -ErrorAction SilentlyContinue
$launchCommand = $null
$launchArguments = @()

if ($null -ne $maven) {
    $launchCommand = $maven.Source
    $launchArguments = @(
        'spring-boot:run',
        '-Dspring-boot.run.arguments=--app.page-watchdog.timeout-ms=3600000'
    )
}
elseif ($null -ne $java -and (Test-Path -LiteralPath $appJar)) {
    Write-Warning 'Maven no esta en PATH; se iniciara el JAR compilado existente.'
    $launchCommand = $java.Source
    $launchArguments = @(
        '-jar',
        $appJar,
        '--app.page-watchdog.timeout-ms=3600000'
    )
}
else {
    throw 'No se encontro Maven ni el JAR compilado junto con Java. Instala Maven o compila el proyecto antes de iniciar la demo.'
}

$listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
if ($listener) {
    throw "El puerto $port ya esta ocupado. No se inicio otra instancia para evitar interferir con el servidor existente."
}

$dataDirectory = Join-Path (Join-Path $projectRoot 'target') ("postman-demo-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $dataDirectory -Force | Out-Null

$environment = @{
    CODES_SERVER_PORT = "$port"
    CODES_SERVER_ADDRESS = '127.0.0.1'
    CODES_DATA_DIR = $dataDirectory
    CODES_ADMIN_USER = 'postman-admin'
    CODES_ADMIN_PASSWORD = 'PostmanDemo!2026'
    CODES_ALTCHA_ENABLED = 'false'
    CODES_EXTERNAL_SERVICES_ENABLED = 'false'
}
$previousEnvironment = @{}
foreach ($name in $environment.Keys) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    [Environment]::SetEnvironmentVariable($name, $environment[$name], 'Process')
}

Write-Host "Base de datos aislada: $dataDirectory"
Write-Host "Postman baseUrl: http://127.0.0.1:$port"
Write-Host 'Credenciales de prueba: postman-admin / PostmanDemo!2026'
Write-Host 'En Postman ejecuta solo la carpeta "Demo ejecutable (datos aislados)".'
Write-Host 'Presiona Ctrl+C para detener la aplicacion. Los datos quedan en target/ y no usan data/.'

Push-Location $projectRoot
try {
    & $launchCommand @launchArguments
    if ($LASTEXITCODE -ne 0) {
        throw "La aplicacion termino con codigo $LASTEXITCODE."
    }
}
finally {
    Pop-Location
    foreach ($name in $environment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
    }
}
