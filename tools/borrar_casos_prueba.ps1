<#
  Borra los CASOS (tabla "calls" de la base H2) para vaciar la basura que
  dejan las pruebas. Es una herramienta DISTINTA de limpiar_proyecto.ps1:
  esa nunca toca la base de datos; esta SOLO toca la base de datos y no
  toca nada mas (ni target/, ni el modelo ASR, ni el diccionario de calles).

  Por defecto NO borra nada: solo muestra cuantos casos hay ahora mismo.
  Para borrar de verdad hay que usar -Borrar Y ademas escribir la palabra
  de confirmacion cuando se pida (para que no se dispare por accidente).

  Uso:
    .\tools\borrar_casos_prueba.ps1              # solo cuenta los casos (dry-run)
    .\tools\borrar_casos_prueba.ps1 -Borrar      # borra TODOS los casos, con confirmacion

  Requiere que CODES este CERRADO (la base H2 queda bloqueada mientras la
  app esta corriendo). Si detecta el puerto 8000 abierto, se detiene sin
  tocar nada y te pide cerrar CODES primero.
#>

param(
    [switch]$Borrar
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
Set-Location $root

$dbFile = Join-Path $root 'data\llamadas.mv.db'
$jdbcUrl = "jdbc:h2:file:$($root.Path -replace '\\','/')/data/llamadas;IFEXISTS=TRUE"

Write-Host ''
Write-Host '============================================' -ForegroundColor Cyan
Write-Host '  Borrar casos de prueba (base de datos)' -ForegroundColor Cyan
Write-Host '============================================' -ForegroundColor Cyan
Write-Host ''
Write-Host 'Esto borra TODOS los registros de la tabla de casos (calls).' -ForegroundColor Yellow
Write-Host 'Es irreversible salvo que tengas un respaldo (ver: Crear respaldo local).' -ForegroundColor Yellow
Write-Host ''

if (-not (Test-Path $dbFile)) {
    Write-Host "No se encontro $dbFile. Aun no existe una base de datos que limpiar." -ForegroundColor Yellow
    exit 0
}

$conexion = Get-NetTCPConnection -LocalPort 8000 -State Listen -ErrorAction SilentlyContinue
if ($conexion) {
    Write-Host 'CODES esta ejecutandose (puerto 8000 abierto).' -ForegroundColor Red
    Write-Host 'Cierra CODES primero y vuelve a intentarlo: la base H2 queda bloqueada mientras la app esta abierta.' -ForegroundColor Red
    exit 1
}

# --- Ubicar java (mismo criterio que CODES.bat) ---
$javaExe = $null
$candidatosJava = @(
    "$env:ProgramFiles\Microsoft\jdk-21.0.12.101-hotspot\bin\java.exe",
    "$env:ProgramFiles\Microsoft\jdk-21\bin\java.exe",
    "$env:ProgramFiles\Java\jdk-21\bin\java.exe",
    "$env:ProgramFiles\Java\jdk-26\bin\java.exe",
    "$env:ProgramFiles\Java\latest\bin\java.exe",
    "$env:ProgramFiles\Eclipse Adoptium\jdk-21\bin\java.exe"
)
foreach ($c in $candidatosJava) { if (-not $javaExe -and (Test-Path $c)) { $javaExe = $c } }
if (-not $javaExe) {
    $enPath = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($enPath) { $javaExe = $enPath.Source }
}
if (-not $javaExe) {
    Write-Host 'No se encontro java. Instala/ubica un JDK 21 (el mismo que usa CODES) e intenta de nuevo.' -ForegroundColor Red
    exit 1
}

# --- Ubicar el jar de H2 (mismo driver que usa la app, via el repo local de Maven) ---
$h2Jar = Get-ChildItem -Path (Join-Path $env:USERPROFILE '.m2\repository\com\h2database\h2') -Filter 'h2-*.jar' -Recurse -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $h2Jar) {
    Write-Host 'No se encontro el driver de H2 en el repositorio local de Maven (~/.m2).' -ForegroundColor Red
    Write-Host 'Arranca CODES una vez (para que Maven descargue las dependencias) y vuelve a intentarlo.' -ForegroundColor Red
    exit 1
}

function Invoke-SqlH2 {
    param([string]$Sql)
    # La base usa contrasena vacia (ver application.properties). No se pasa
    # -password aca a proposito: un argumento vacio se pierde al invocar un
    # exe externo desde PowerShell y descuadra el resto de las opciones,
    # haciendo que -sql y el SQL mismo se lean como argumentos invalidos.
    $argList = @('-cp', $h2Jar.FullName, 'org.h2.tools.Shell', '-url', $jdbcUrl, '-user', 'sa', '-sql', $Sql)
    & $javaExe @argList
}

Write-Host 'Contando casos actuales...' -ForegroundColor Cyan
Invoke-SqlH2 -Sql 'SELECT COUNT(*) AS CASOS_ACTUALES FROM CALLS;'
Write-Host ''

if (-not $Borrar) {
    Write-Host 'Esto fue solo una vista previa (no se borro nada).' -ForegroundColor Cyan
    Write-Host 'Vuelve a ejecutar con -Borrar para borrar de verdad.' -ForegroundColor Cyan
    exit 0
}

Write-Host 'Vas a borrar TODOS los casos de la base de datos.' -ForegroundColor Red
Write-Host 'Tambien se vaciaran las carpetas de audio asociadas (data\audios_crudos y data\audios_encriptados).' -ForegroundColor Red
$confirmacion = Read-Host 'Escribe BORRAR (en mayusculas) para confirmar'
if ($confirmacion -cne 'BORRAR') {
    Write-Host 'No escribiste la confirmacion exacta. No se borro nada.' -ForegroundColor Yellow
    exit 0
}

Write-Host ''
Write-Host 'Borrando casos...' -ForegroundColor Yellow
Invoke-SqlH2 -Sql 'DELETE FROM CALLS;'

foreach ($carpetaAudio in @('data\audios_crudos', 'data\audios_encriptados')) {
    $rutaAbs = Join-Path $root $carpetaAudio
    if (Test-Path $rutaAbs) {
        Get-ChildItem -Path $rutaAbs -File -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue
    }
}

Write-Host ''
Write-Host 'Listo. Verificando que haya quedado en cero:' -ForegroundColor Green
Invoke-SqlH2 -Sql 'SELECT COUNT(*) AS CASOS_ACTUALES FROM CALLS;'
