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
echo   1. Iniciar sistema completo
echo   2. Diagnosticar entorno y servicios
echo   3. Crear respaldo local
echo   4. Gestionar limpieza / liberar espacio
echo   5. Borrar casos de prueba (base de datos)
echo   6. Salir
echo.

choice /c 123456 /n /m "Elige una opcion (1-6): "

if errorlevel 6 goto :fin
if errorlevel 5 goto :menu_casos
if errorlevel 4 goto :menu_limpiar
if errorlevel 3 goto :menu_backup
if errorlevel 2 goto :menu_diagnostico
if errorlevel 1 goto :iniciar

:: ------------------------------------------------------------------
:: Diagnostico del entorno
:: ------------------------------------------------------------------
:menu_diagnostico
cls
echo ============================================
echo   Diagnostico de CODES
echo ============================================
echo.
echo   1. Verificar Java y Maven
echo   2. Revisar estado de servicios externos
echo   3. Volver al menu principal
echo.
choice /c 123 /n /m "Elige una opcion (1-3): "
if errorlevel 3 goto :menu
if errorlevel 2 goto :diag_servicios
if errorlevel 1 goto :diag_java

:diag_java
call :buscar_java
call :buscar_maven 2>nul
echo.
echo Java: %JAVA_EXE%
echo Maven: %MAVEN_BIN%
if defined JAVA_EXE (echo Estado Java: OK) else (echo Estado Java: NO DETECTADO)
if defined MAVEN_BIN (echo Estado Maven: OK) else (echo Estado Maven: NO DETECTADO)
echo.
pause
goto :menu_diagnostico

:diag_servicios
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "Write-Host 'CODES usa Groq Whisper para transcribir y Geoapify para geocodificar.' -ForegroundColor Green; Write-Host 'Configura GROQ_API_KEY y GEOAPIFY_API_KEY en data\.codes-secrets.ps1.' -ForegroundColor Yellow"
echo.
pause
goto :menu_diagnostico

:: ------------------------------------------------------------------
:: Iniciar CODES
:: ------------------------------------------------------------------
:iniciar
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
echo   Transcripción: Groq Whisper
echo   Geocodificación: Geoapify
echo.
echo El servidor se levantara en segundo plano y quedara activo mientras CODES este abierto.
echo El navegador se abrira despues de verificar el backend.
echo Si algo falla, revisa logs\spring-boot.log.
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
echo   Limpieza de CODES (target, caches)
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
:: Borrar casos de prueba (toca la base de datos, a proposito separado
:: de la limpieza de arriba para que no se borre nada por accidente)
:: ------------------------------------------------------------------
:menu_casos
cls
echo ============================================
echo   Borrar casos de prueba (base de datos)
echo ============================================
echo.
echo Esto borra los CASOS guardados (la tabla de llamadas), no archivos
echo del proyecto. Es irreversible salvo que tengas un respaldo.
echo.
echo   1. Solo ver cuantos casos hay ahora (no borra nada)
echo   2. Borrar TODOS los casos de verdad
echo   3. Volver al menu principal
echo.

choice /c 123 /n /m "Elige una opcion (1-3): "

if errorlevel 3 goto :menu
if errorlevel 2 goto :borrar_casos
if errorlevel 1 goto :ver_casos

:ver_casos
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\tools\borrar_casos_prueba.ps1"
echo.
pause
goto :menu_casos

:borrar_casos
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\tools\borrar_casos_prueba.ps1" -Borrar
echo.
pause
goto :menu_casos


:: ------------------------------------------------------------------
:: Funciones auxiliares (no crean su propio scope: dejan las variables
:: JAVA_EXE / MAVEN_BIN puestas para quien las llamo)
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
