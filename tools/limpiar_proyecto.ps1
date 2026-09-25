<#
  Limpia del proyecto CODES todo lo que es pesado pero se puede volver a
  generar/descargar automáticamente, para que el proyecto quede liviano
  y fácil de comprimir/enviar.

  Por defecto NO borra nada: solo lista qué encontró y cuánto espacio
  ocupa. Usa -Borrar para ejecutar la limpieza de verdad.

  Uso:
    .\tools\limpiar_proyecto.ps1                    # solo lista (dry-run)
    .\tools\limpiar_proyecto.ps1 -Borrar             # limpia de verdad
    .\tools\limpiar_proyecto.ps1 -Borrar -Comprimir  # limpia y deja un .zip listo para enviar

  Para volver a tener todo funcionando después de limpiar:
    - Modelo ASR:        .\tools\asr\install_windows.ps1
    - Diccionario calles: python tools\streets\fetch_calles_overpass.py data\calles_chile.txt
    - Compilación (target/): se regenera solo al ejecutar INICIAR_CODES.bat

  NUNCA toca: la base de datos (data\llamadas.mv.db), el archivo de
  secretos (data\.codes-secrets.ps1), los audios de casos
  (data\audios_crudos, data\audios_encriptados) ni la carpeta .git.
#>

param(
    [switch]$Borrar,
    [switch]$Comprimir,
    [switch]$IncluirDiccionarioCalles,
    [switch]$IncluirLogs
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Get-TamanoMB {
    param([string]$Ruta)
    if (-not (Test-Path $Ruta)) { return 0 }
    $item = Get-Item $Ruta -Force
    if ($item.PSIsContainer) {
        $bytes = (Get-ChildItem $Ruta -Recurse -Force -File -ErrorAction SilentlyContinue |
                   Measure-Object -Property Length -Sum).Sum
        if (-not $bytes) { $bytes = 0 }
    } else {
        $bytes = $item.Length
    }
    return [math]::Round($bytes / 1MB, 1)
}

# Cada elemento: ruta relativa + explicación de por qué es seguro borrarlo.
$candidatos = @(
    @{ Ruta = 'target';                    Motivo = 'Compilado de Maven (se regenera al arrancar la app)' },
    @{ Ruta = 'tools\asr\models';          Motivo = 'Modelo ASR descargado (se reinstala con install_windows.ps1)' },
    @{ Ruta = 'tools\asr\asr.log';         Motivo = 'Log del motor de transcripción' },
    @{ Ruta = 'data\geocache.json';        Motivo = 'Caché de direcciones ya geocodificadas (se reconstruye sola)' },
    @{ Ruta = '.idea';                     Motivo = 'Configuración local de IntelliJ' },
    @{ Ruta = '.vscode';                   Motivo = 'Configuración local de VS Code' }
)

if ($IncluirLogs) {
    $candidatos += @{ Ruta = 'logs'; Motivo = 'Logs de auditoría (opt-in: puede ser útil conservarlos)' }
}
if ($IncluirDiccionarioCalles) {
    $candidatos += @{ Ruta = 'data\calles_chile.txt'; Motivo = 'Diccionario de calles (opt-in: se regenera con fetch_calles_overpass.py)' }
}

# Patrones sueltos: archivos .tar.bz2 en tools\asr y cualquier .osm.pbf que haya quedado tirado.
$patronesSueltos = @(
    @{ Carpeta = 'tools\asr'; Filtro = '*.tar.bz2'; Motivo = 'Instalador ASR descargado' },
    @{ Carpeta = '.';         Filtro = '*.osm.pbf'; Motivo = 'Extracto de OpenStreetMap usado para el diccionario de calles' }
)
foreach ($p in $patronesSueltos) {
    if (Test-Path $p.Carpeta) {
        Get-ChildItem -Path $p.Carpeta -Filter $p.Filtro -File -ErrorAction SilentlyContinue | ForEach-Object {
            $candidatos += @{ Ruta = $_.FullName.Substring($root.Length + 1); Motivo = $p.Motivo }
        }
    }
}
# __pycache__ en cualquier profundidad (scripts de tools\streets).
Get-ChildItem -Path $root -Recurse -Directory -Filter '__pycache__' -ErrorAction SilentlyContinue | ForEach-Object {
    $candidatos += @{ Ruta = $_.FullName.Substring($root.Length + 1); Motivo = 'Caché de bytecode de Python' }
}

Write-Host ''
Write-Host '=== Elementos pesados/redescargables encontrados ===' -ForegroundColor Cyan
$totalMB = 0
$existentes = @()
foreach ($c in $candidatos) {
    $rutaAbs = Join-Path $root $c.Ruta
    if (Test-Path $rutaAbs) {
        $mb = Get-TamanoMB $rutaAbs
        $totalMB += $mb
        $existentes += $c
        Write-Host ("  [{0,7:N1} MB]  {1}  -  {2}" -f $mb, $c.Ruta, $c.Motivo)
    }
}

if ($existentes.Count -eq 0) {
    Write-Host '  (nada que limpiar; el proyecto ya está liviano)' -ForegroundColor Green
} else {
    Write-Host ''
    Write-Host ("Total a liberar: {0:N1} MB" -f $totalMB) -ForegroundColor Yellow
}

Write-Host ''
Write-Host 'NUNCA se tocan: data\llamadas.mv.db, data\.codes-secrets.ps1,' -ForegroundColor DarkGray
Write-Host 'data\audios_crudos, data\audios_encriptados, ni .git' -ForegroundColor DarkGray
Write-Host ''

if (-not $Borrar) {
    Write-Host 'Esto fue solo una vista previa (no se borró nada).' -ForegroundColor Cyan
    Write-Host 'Vuelve a ejecutar con -Borrar para limpiar de verdad.' -ForegroundColor Cyan
    Write-Host 'Opciones extra: -IncluirLogs, -IncluirDiccionarioCalles, -Comprimir' -ForegroundColor DarkGray
    return
}

if ($existentes.Count -gt 0) {
    foreach ($c in $existentes) {
        $rutaAbs = Join-Path $root $c.Ruta
        Remove-Item -Path $rutaAbs -Recurse -Force -ErrorAction SilentlyContinue
        Write-Host "Borrado: $($c.Ruta)" -ForegroundColor Green
    }
    Write-Host ''
    Write-Host ("Listo. Se liberaron aprox. {0:N1} MB." -f $totalMB) -ForegroundColor Green
}

if ($Comprimir) {
    Write-Host ''
    Write-Host 'Comprimiendo proyecto...' -ForegroundColor Cyan

    $nombreProyecto = Split-Path -Leaf $root
    $temp = Join-Path $env:TEMP "codes_comprimir_$(Get-Date -Format 'yyyyMMdd_HHmmss')"
    $tempProyecto = Join-Path $temp $nombreProyecto
    New-Item -ItemType Directory -Path $tempProyecto -Force | Out-Null

    # robocopy preserva la estructura de carpetas y permite excluir con
    # rutas exactas, a diferencia de Compress-Archive con listas de archivos.
    $excluirDirs = @(
        (Join-Path $root '.git'),
        (Join-Path $root 'data\audios_crudos'),
        (Join-Path $root 'data\audios_encriptados')
    ) | Where-Object { Test-Path $_ }
    $excluirArchivos = @('llamadas.mv.db', 'llamadas.trace.db', '.codes-secrets.ps1')

    $robocopyArgs = @($root, $tempProyecto, '/E', '/NFL', '/NDL', '/NJH', '/NJS')
    if ($excluirDirs.Count -gt 0) { $robocopyArgs += '/XD'; $robocopyArgs += $excluirDirs }
    $robocopyArgs += '/XF'; $robocopyArgs += $excluirArchivos
    robocopy @robocopyArgs | Out-Null

    $fecha = Get-Date -Format 'yyyyMMdd_HHmmss'
    $destino = Join-Path (Split-Path -Parent $root) "CODES_liviano_$fecha.zip"
    if (Test-Path $destino) { Remove-Item $destino -Force }

    Compress-Archive -Path $tempProyecto -DestinationPath $destino -CompressionLevel Optimal
    Remove-Item -Path $temp -Recurse -Force -ErrorAction SilentlyContinue

    Write-Host "Listo: $destino" -ForegroundColor Green
    Write-Host '(No incluye la base de datos, los secretos ni los audios de casos.)' -ForegroundColor DarkGray
}
