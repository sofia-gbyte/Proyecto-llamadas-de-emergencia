<#
  Crea un respaldo local restaurable de CODES.

  Uso:
    .\tools\backup_codes.ps1
    .\tools\backup_codes.ps1 -Destino D:\backups\codes
    .\tools\backup_codes.ps1 -IncluirLogs

  El respaldo incluye la base H2, audios, caché, diccionario y secretos locales.
  Trátalo como información sensible y guárdalo en un lugar protegido.
#>
param(
    [string]$Destino = (Join-Path $PSScriptRoot '..\backups'),
    [switch]$IncluirLogs
)

$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
$connection = Get-NetTCPConnection -LocalPort 8000 -State Listen -ErrorAction SilentlyContinue
if ($connection) {
  throw 'CODES está ejecutándose en el puerto 8000. Cierra la aplicación antes de crear un backup consistente.'
}
$timestamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$base = if ([System.IO.Path]::IsPathRooted($Destino)) { $Destino } else { Join-Path $root $Destino }
New-Item -ItemType Directory -Force -Path $base | Out-Null
$backupRoot = Join-Path (Resolve-Path $base) "codes_$timestamp"

New-Item -ItemType Directory -Force -Path $backupRoot | Out-Null
$items = @(
    'data\llamadas.mv.db',
    'data\llamadas.trace.db',
    'data\geocache.json',
    'data\calles_chile.txt',
    'data\.codes-secrets.ps1',
    'data\audios_crudos',
    'data\audios_encriptados'
)
if ($IncluirLogs) { $items += 'logs' }

$copied = 0
foreach ($relativePath in $items) {
    $source = Join-Path $root $relativePath
    if (-not (Test-Path $source)) { continue }
    $target = Join-Path $backupRoot $relativePath
    $targetDir = if ((Get-Item $source).PSIsContainer) { $target } else { Split-Path $target -Parent }
    New-Item -ItemType Directory -Force -Path $targetDir | Out-Null
    Copy-Item -Path $source -Destination $target -Recurse -Force
    $copied++
}

if ($copied -eq 0) { throw 'No se encontraron datos para respaldar.' }
$manifest = [ordered]@{
    createdAt = (Get-Date).ToString('o')
    project = Split-Path $root -Leaf
    includesLogs = [bool]$IncluirLogs
    files = Get-ChildItem $backupRoot -Recurse -File | ForEach-Object { $_.FullName.Substring($backupRoot.Path.Length + 1) }
}
$manifest | ConvertTo-Json -Depth 3 | Set-Content (Join-Path $backupRoot 'manifest.json') -Encoding UTF8
Write-Host "Respaldo creado: $backupRoot" -ForegroundColor Green
Write-Host "Elementos copiados: $copied" -ForegroundColor Green
Write-Host 'Incluye secretos locales: protégelo y no lo compartas.' -ForegroundColor Yellow
