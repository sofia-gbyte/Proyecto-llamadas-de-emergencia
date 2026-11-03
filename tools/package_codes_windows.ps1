param(
    [string]$MavenCommand,
    [string]$DestinationDirectory
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if ([string]::IsNullOrWhiteSpace($MavenCommand)) {
    $maven = Get-Command mvn.cmd, mvn -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($maven) {
        $MavenCommand = $maven.Source
    } else {
        $candidates = @(
            (Join-Path $HOME 'maven\apache-maven-3.9.9\bin\mvn.cmd'),
            (Join-Path $HOME '.maven\apache-maven-3.9.9\bin\mvn.cmd'),
            (Join-Path $env:ProgramFiles 'Apache\Maven\apache-maven-3.9.16\bin\mvn.cmd')
        )
        $MavenCommand = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
    }
}
if (-not $MavenCommand -or -not (Test-Path $MavenCommand)) {
    throw 'No se encontró Maven. Instala Maven o indica -MavenCommand con la ruta a mvn.cmd.'
}

$java = Get-Command java.exe -ErrorAction SilentlyContinue
if (-not $java) {
    throw 'No se encontró Java. Instala un JDK 21 o superior antes de empaquetar.'
}
$jpackage = Join-Path (Split-Path -Parent $java.Source) 'jpackage.exe'
if (-not (Test-Path $jpackage)) {
    throw "No se encontró jpackage junto a Java: $jpackage"
}
$versionOutput = [System.Diagnostics.FileVersionInfo]::GetVersionInfo($java.Source).ProductVersion
$versionMatch = [regex]::Match($versionOutput, '^(\d+)')
if (-not $versionMatch.Success -or [int]$versionMatch.Groups[1].Value -lt 21) {
    throw "Se requiere JDK 21 o superior. Versión detectada: $versionOutput"
}

if ([string]::IsNullOrWhiteSpace($DestinationDirectory)) {
    $DestinationDirectory = Join-Path $root 'dist'
} elseif (-not [System.IO.Path]::IsPathRooted($DestinationDirectory)) {
    $DestinationDirectory = Join-Path $root $DestinationDirectory
}
$DestinationDirectory = [System.IO.Path]::GetFullPath($DestinationDirectory)

$outputDirectory = Join-Path $DestinationDirectory 'CODES'
if (Test-Path $outputDirectory) {
    throw "El destino ya existe y no se sobrescribirá: $outputDirectory"
}

Write-Host 'Ejecutando clean verify...' -ForegroundColor Cyan
& $MavenCommand -B clean verify
if ($LASTEXITCODE -ne 0) {
    throw "Maven terminó con código $LASTEXITCODE; no se creó el paquete."
}

$jar = Join-Path $root 'target\codes-1.0.2.jar'
if (-not (Test-Path $jar)) {
    throw "No se encontró el JAR ejecutable: $jar"
}
$inputDirectory = Join-Path $root 'target\jpackage-input'
if (Test-Path $inputDirectory) {
    Remove-Item -LiteralPath $inputDirectory -Recurse -Force
}
New-Item -ItemType Directory -Path $inputDirectory | Out-Null
Copy-Item -LiteralPath $jar -Destination (Join-Path $inputDirectory 'codes-1.0.2.jar')
$iconPath = Join-Path $root 'tools\assets\CODES.ico'
if (-not (Test-Path $iconPath)) {
    throw "No se encontró el icono de Windows: $iconPath"
}
New-Item -ItemType Directory -Path $DestinationDirectory -Force | Out-Null
$runtimeModules = 'java.base,java.compiler,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql.rowset,java.xml,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.jfr,jdk.localedata,jdk.net,jdk.unsupported'

Write-Host 'Generando app-image de Windows con runtime incluido...' -ForegroundColor Cyan
& $jpackage `
    --type app-image `
    --name CODES `
    --icon $iconPath `
    --input $inputDirectory `
    --main-jar 'codes-1.0.2.jar' `
    --dest $DestinationDirectory `
    --add-modules $runtimeModules `
    --java-options '-Dcodes.packaged=true' `
    --win-console
if ($LASTEXITCODE -ne 0) {
    throw "jpackage terminó con código $LASTEXITCODE."
}

$executable = Join-Path $outputDirectory 'CODES.exe'
$runtimeModules = Join-Path $outputDirectory 'runtime\lib\modules'
if (-not (Test-Path $executable) -or -not (Test-Path $runtimeModules)) {
    throw 'El app-image no contiene CODES.exe y los módulos del runtime incluidos.'
}

$size = (Get-ChildItem -LiteralPath $outputDirectory -File -Recurse | Measure-Object -Property Length -Sum).Sum
Write-Host "App-image listo: $executable" -ForegroundColor Green
Write-Host ("Tamaño aproximado: {0:N1} MB" -f ($size / 1MB)) -ForegroundColor Green
