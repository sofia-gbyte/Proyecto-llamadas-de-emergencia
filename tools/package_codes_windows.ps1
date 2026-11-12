param(
    [string]$MavenCommand,
    [string]$DestinationDirectory,
    [switch]$ReplaceExisting
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
$replaceExistingPackage = $false
if (Test-Path $outputDirectory) {
    $existingManifest = Join-Path $outputDirectory 'package-manifest.json'
    if (-not $ReplaceExisting) {
        throw "El destino ya existe y no se sobrescribirá: $outputDirectory. Usa -ReplaceExisting solo para reemplazar un app-image generado por este script."
    }
    if (-not (Test-Path $existingManifest)) {
        throw "No se reemplazará un directorio sin manifiesto de paquete verificable: $outputDirectory"
    }
    try {
        $existingPackage = Get-Content -LiteralPath $existingManifest -Raw | ConvertFrom-Json
    } catch {
        throw "El manifiesto del destino no es válido; no se reemplazará: $existingManifest"
    }
    if ($existingPackage.application -ne 'CODES' -or
        $existingPackage.packageType -ne 'Windows app-image' -or
        $existingPackage.entryPoint -ne 'CODES.exe') {
        throw "El manifiesto no identifica un app-image de CODES; no se reemplazará: $existingManifest"
    }
    $replaceExistingPackage = $true
}

Write-Host 'Ejecutando clean verify...' -ForegroundColor Cyan
& $MavenCommand -B clean verify
if ($LASTEXITCODE -ne 0) {
    throw "Maven terminó con código $LASTEXITCODE; no se creó el paquete."
}

$jar = Join-Path $root 'target\codes-1.1.1.jar'
if (-not (Test-Path $jar)) {
    throw "No se encontró el JAR ejecutable: $jar"
}
$inputDirectory = Join-Path $root 'target\jpackage-input'
if (Test-Path $inputDirectory) {
    Remove-Item -LiteralPath $inputDirectory -Recurse -Force
}
New-Item -ItemType Directory -Path $inputDirectory | Out-Null
Copy-Item -LiteralPath $jar -Destination (Join-Path $inputDirectory 'codes-1.1.1.jar')
$iconPath = Join-Path $root 'tools\assets\CODES.ico'
if (-not (Test-Path $iconPath)) {
    throw "No se encontró el icono de Windows: $iconPath"
}
New-Item -ItemType Directory -Path $DestinationDirectory -Force | Out-Null
$stagingDirectory = Join-Path $DestinationDirectory ('.CODES-staging-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stagingDirectory | Out-Null
$stagedOutputDirectory = Join-Path $stagingDirectory 'CODES'
$runtimeModules = 'java.base,java.compiler,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql.rowset,java.xml,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.jfr,jdk.localedata,jdk.net,jdk.unsupported'

Write-Host 'Generando app-image de Windows con runtime incluido...' -ForegroundColor Cyan
& $jpackage `
    --type app-image `
    --name CODES `
    --app-version '1.1.1' `
    --icon $iconPath `
    --input $inputDirectory `
    --main-jar 'codes-1.1.1.jar' `
    --dest $stagingDirectory `
    --add-modules $runtimeModules `
    --java-options '-Dcodes.packaged=true'
if ($LASTEXITCODE -ne 0) {
    throw "jpackage terminó con código $LASTEXITCODE."
}

$executable = Join-Path $stagedOutputDirectory 'CODES.exe'
$runtimeModules = Join-Path $stagedOutputDirectory 'runtime\lib\modules'
if (-not (Test-Path $executable) -or -not (Test-Path $runtimeModules)) {
    throw 'El app-image no contiene CODES.exe y los módulos del runtime incluidos.'
}

$forbiddenExtensions = @('.bat', '.cmd', '.ps1', '.psm1', '.sh')
$forbiddenNames = @('.codes-secrets.ps1', '.codes-secrets.properties', 'llamadas.mv.db', 'llamadas.trace.db')
$packagedFiles = Get-ChildItem -LiteralPath $stagedOutputDirectory -File -Recurse
$forbiddenFiles = $packagedFiles | Where-Object {
    $forbiddenExtensions -contains $_.Extension.ToLowerInvariant() -or
    $forbiddenNames -contains $_.Name.ToLowerInvariant() -or
    $_.Extension.ToLowerInvariant() -in @('.log', '.mv.db', '.wav', '.mp3') -or
    $_.FullName -match '[\\/](data|logs|backups?|audios_crudos|audios_encriptados)[\\/]'
}
if ($forbiddenFiles) {
    throw "El app-image contiene archivos no permitidos: $($forbiddenFiles.FullName -join ', ')"
}

$manifestEntries = @(
    $packagedFiles |
        Sort-Object { $_.FullName.Substring($stagedOutputDirectory.Length + 1).Replace('\', '/') } |
        ForEach-Object {
            [ordered]@{
                path = $_.FullName.Substring($stagedOutputDirectory.Length + 1).Replace('\', '/')
                size = $_.Length
                sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
            }
        }
)
$manifest = [ordered]@{
    application = 'CODES'
    version = '1.1.1'
    packageType = 'Windows app-image'
    entryPoint = 'CODES.exe'
    runtimeIncluded = $true
    generatedAtUtc = [DateTime]::UtcNow.ToString('o')
    javaVersion = $versionOutput
    files = $manifestEntries
}
$manifestPath = Join-Path $stagedOutputDirectory 'package-manifest.json'
$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
$checksumLines = $manifestEntries | ForEach-Object { "$($_.sha256)  $($_.path)" }
$manifestHash = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash.ToLowerInvariant()
$checksumLines += "$manifestHash  package-manifest.json"
Set-Content -LiteralPath (Join-Path $stagedOutputDirectory 'SHA256SUMS.txt') -Value $checksumLines -Encoding UTF8

if (Test-Path $outputDirectory) {
    if (-not $replaceExistingPackage) {
        throw "El destino apareció durante el empaquetado y no se sobrescribirá: $outputDirectory"
    }
    Remove-Item -LiteralPath $outputDirectory -Recurse -Force
}
Move-Item -LiteralPath $stagedOutputDirectory -Destination $outputDirectory
Remove-Item -LiteralPath $stagingDirectory -Recurse -Force

$executable = Join-Path $outputDirectory 'CODES.exe'
$size = (Get-ChildItem -LiteralPath $outputDirectory -File -Recurse | Measure-Object -Property Length -Sum).Sum
Write-Host "App-image listo: $executable" -ForegroundColor Green
Write-Host ("Tamaño aproximado: {0:N1} MB" -f ($size / 1MB)) -ForegroundColor Green
Write-Host "Manifiesto y SHA-256: $(Join-Path $outputDirectory 'package-manifest.json')" -ForegroundColor Green
