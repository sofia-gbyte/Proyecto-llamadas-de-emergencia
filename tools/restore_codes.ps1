<#
  Restaura un respaldo local de CODES.

  Uso:
    .\tools\restore_codes.ps1 -Respaldo .\backups\codes_20260926_120000
    .\tools\restore_codes.ps1 -Respaldo D:\backups\codes_... -Forzar

  CODES debe estar detenido antes de restaurar. -Forzar permite restaurar aunque
  el puerto 8000 parezca ocupado; úsalo solo después de comprobar el proceso.
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$Respaldo,
    [string]$Destino = (Join-Path $PSScriptRoot '..'),
    [switch]$Forzar
)

$ErrorActionPreference = 'Stop'
$projectRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
$backupRoot = Resolve-Path $Respaldo
if (-not (Test-Path (Join-Path $backupRoot 'manifest.json'))) {
    throw 'La carpeta no parece un respaldo de CODES: falta manifest.json.'
}

if (-not $Forzar) {
    $connection = Get-NetTCPConnection -LocalPort 8000 -State Listen -ErrorAction SilentlyContinue
    if ($connection) {
        throw 'El puerto 8000 está en uso. Detén CODES y vuelve a ejecutar, o usa -Forzar tras verificar el proceso.'
    }
}

$targetRoot = Resolve-Path $Destino
$restored = 0
Get-ChildItem $backupRoot -Recurse -File | Where-Object { $_.Name -ne 'manifest.json' } | ForEach-Object {
    $relative = $_.FullName.Substring($backupRoot.Path.Length + 1)
    $target = Join-Path $targetRoot $relative
    New-Item -ItemType Directory -Force -Path (Split-Path $target -Parent) | Out-Null
    Copy-Item $_.FullName $target -Force
    $restored++
}

Write-Host "Restauración completada en $targetRoot" -ForegroundColor Green
Write-Host "Archivos restaurados: $restored" -ForegroundColor Green
Write-Host 'Revisa data\.codes-secrets.ps1 y ejecuta CODES.bat para comprobar el sistema.' -ForegroundColor Yellow
