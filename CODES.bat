@echo off
setlocal
cd /d "%~dp0"
set "EXIT_CODE=0"


:menu
cls
echo ============================================
echo   CODES - Centro de monitoreo de emergencias
echo ============================================
echo.
echo   1. Iniciar CODES completo (ASR y calles)
echo   2. Limpiar proyecto (liberar espacio)
echo   3. Crear respaldo local
echo   4. Salir
echo.

choice /c 1234 /n /m "Elige una opcion (1-4): "

if errorlevel 4 goto :fin
if errorlevel 3 goto :menu_backup
if errorlevel 2 goto :menu_limpiar
if errorlevel 1 goto :iniciar

:: ------------------------------------------------------------------
:: Iniciar CODES
:: ------------------------------------------------------------------
:iniciar
call :actualizar_calles
if errorlevel 1 (
    echo.
    echo ADVERTENCIA: no se pudo actualizar data\calles_chile.txt.
    echo CODES continuara usando el archivo de calles existente.
    echo.
)

call :buscar_java

call :buscar_maven 2>nul

if defined JAVA_EXE (
    set "JAVA_HOME=%JAVA_EXE:\bin\java.exe=%"
    set "PATH=%JAVA_HOME%\bin;%PATH%"
)
if defined MAVEN_BIN set "MAVEN_BIN=%MAVEN_BIN:"=%"
if defined MAVEN_BIN set "PATH=%MAVEN_BIN%;%PATH%"

echo.
echo ============================================
echo   Iniciando y verificando CODES
echo ============================================
echo   Servidor: http://localhost:8000/api/health
echo   ASR:     ws://localhost:6006
echo.
echo El servidor se levantara en segundo plano y quedara activo mientras CODES este abierto.
echo El navegador se abrira SOLO cuando /api/health responda OK.
echo Si algo falla, revisa logs\spring-boot.log y tools\asr\asr.log
echo.

powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\tools\iniciar_codes_windows.ps1" -JavaPath "%JAVA_EXE%" -MavenBin "%MAVEN_BIN%"
set "EXIT_CODE=%ERRORLEVEL%"

if not "%EXIT_CODE%"=="0" (
    echo.
    echo ============================================
    echo   CODES NO PUDO INICIAR
    echo ============================================
    echo.
    echo Codigo de salida: %EXIT_CODE%
    echo.
    if exist "logs\spring-boot.log" (
        echo Ultimas lineas de logs\spring-boot.log:
        echo --------------------------------------------
        powershell.exe -NoProfile -Command "Get-Content 'logs\spring-boot.log' -Tail 40"
        echo --------------------------------------------
    )
    echo.
    echo La ventana queda abierta para que puedas ver el error.
    pause
)

goto :fin

:: ------------------------------------------------------------------
:: Respaldo local
:: ------------------------------------------------------------------
:menu_backup
cls
echo ============================================
echo   Respaldo local de CODES
echo ============================================
echo.
echo Se respaldaran la base de datos, audios, cache, diccionario y secretos.
echo El respaldo contiene informacion sensible y debe protegerse.
echo.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\tools\backup_codes.ps1" -IncluirLogs
echo.
pause
goto :menu

:: ------------------------------------------------------------------
:: Limpieza / "desinstalar" lo redescargable
:: ------------------------------------------------------------------
:menu_limpiar
cls
echo ============================================
echo   Limpieza de CODES (modelos ASR, target, cachés)
echo ============================================
echo.
echo   1. Solo ver que se borraria (no borra nada)
echo   2. Borrar de verdad
echo   3. Borrar y dejar un .zip liviano listo para enviar
echo   4. Volver al menu principal
echo.

choice /c 1234 /n /m "Elige una opcion (1-4): "

if errorlevel 4 goto :menu
if errorlevel 3 goto :borrar_comprimir
if errorlevel 2 goto :borrar
if errorlevel 1 goto :vista

:vista
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\tools\limpiar_proyecto.ps1"
echo.
pause
goto :menu

:borrar
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\tools\limpiar_proyecto.ps1" -Borrar
echo.
pause
goto :menu

:borrar_comprimir
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\tools\limpiar_proyecto.ps1" -Borrar -Comprimir
echo.
pause
goto :menu

:: ------------------------------------------------------------------
:: Actualizar calles de Chile (cache de 7 dias)
:: ------------------------------------------------------------------
:actualizar_calles
set "CALLES_FILE=data\calles_chile.txt"
set "CALLES_MAX_AGE_DAYS=7"

if exist "%CALLES_FILE%" (
    for /f %%A in ('powershell.exe -NoProfile -Command "if ((Get-Date) - (Get-Item '%CALLES_FILE%').LastWriteTime -lt [TimeSpan]::FromDays(%CALLES_MAX_AGE_DAYS%)) { exit 0 } else { exit 1 }"') do rem
    powershell.exe -NoProfile -Command "if ((Get-Date) - (Get-Item '%CALLES_FILE%').LastWriteTime -lt [TimeSpan]::FromDays(%CALLES_MAX_AGE_DAYS%)) { exit 0 } else { exit 1 }"
    if not errorlevel 1 (
        echo.
        echo Calles de Chile ya estan actualizadas. Se conserva el cache local.
        echo Proxima actualizacion automatica: cuando el archivo tenga mas de %CALLES_MAX_AGE_DAYS% dias.
        echo.
        exit /b 0
    )
)

echo.
echo ============================================
echo   Actualizando calles de Chile desde Overpass
echo ============================================
echo.
echo Ejecutando:
echo   python tools/streets/fetch_calles_overpass.py data\calles_chile.txt --pais
echo.

where python >nul 2>&1
if errorlevel 1 (
    echo Python no encontrado en PATH.
    exit /b 1
)

python tools/streets/fetch_calles_overpass.py "%CALLES_FILE%" --pais
set "CALLES_EXIT=%ERRORLEVEL%"

if not "%CALLES_EXIT%"=="0" (
    echo.
    echo No se actualizo el archivo de calles. Se mantiene el existente.
    echo Causa: Overpass no estuvo disponible o rechazo la consulta.
    echo.
    exit /b %CALLES_EXIT%
)

echo.
echo Calles de Chile actualizadas correctamente.
echo.
exit /b 0

:: ------------------------------------------------------------------
:: Funciones auxiliares (no crean su propio scope: dejan las variables
:: JAVA_EXE / MAVEN_BIN puestas para quien las llamó)
:: ------------------------------------------------------------------
:buscar_java
set "JAVA_EXE="
for %%J in (
	"%ProgramFiles%\Microsoft\jdk-21.0.12.101-hotspot\bin\java.exe"
	"%ProgramFiles%\Microsoft\jdk-21\bin\java.exe"
	"%ProgramFiles%\Java\jdk-21\bin\java.exe"
	"%ProgramFiles%\Java\jdk-26\bin\java.exe"
	"%ProgramFiles%\Java\latest\bin\java.exe"
	"%ProgramFiles%\Eclipse Adoptium\jdk-21\bin\java.exe"
) do if not defined JAVA_EXE if exist "%%~J" set "JAVA_EXE=%%~J"

if not defined JAVA_EXE (
	for /f "delims=" %%J in ('where java 2^>nul') do if not defined JAVA_EXE set "JAVA_EXE=%%J"
)
exit /b 0

:buscar_maven
set "MAVEN_BIN="
for %%M in (
	"%USERPROFILE%\.maven\apache-maven-3.9.9\bin\mvn.cmd"
	"%USERPROFILE%\.maven\maven-3.9.15\bin\mvn.cmd"
	"%USERPROFILE%\.maven\maven-3.9.16\bin\mvn.cmd"
	"%ProgramFiles%\Apache\Maven\apache-maven-3.9.16\bin\mvn.cmd"
	"%ProgramFiles%\Apache\Maven\apache-maven-3.9.15\bin\mvn.cmd"
) do if not defined MAVEN_BIN if exist "%%~M" set "MAVEN_BIN=%%~dpM"

if not defined MAVEN_BIN (
	for /f "delims=" %%M in ('where mvn 2^>nul') do if not defined MAVEN_BIN set "MAVEN_BIN=%%~dpM"
)
exit /b 0

:fin
endlocal
exit /b %EXIT_CODE%
