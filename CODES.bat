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
echo   1. Iniciar CODES
echo   2. Limpiar proyecto (liberar espacio)
echo   3. Salir
echo.

choice /c 123 /n /m "Elige una opcion (1-3): "

if errorlevel 3 goto :fin
if errorlevel 2 goto :menu_limpiar
if errorlevel 1 goto :iniciar

:: ------------------------------------------------------------------
:: Iniciar CODES
:: ------------------------------------------------------------------
:iniciar
call :buscar_java
if not defined JAVA_EXE (
	echo.
	echo Java 21 no encontrado.
	echo Instala JDK 21 y vuelve a ejecutar CODES.
	pause
	goto :menu
)

call :buscar_maven
if not defined MAVEN_BIN (
	echo.
	echo Maven no encontrado.
	echo Instala Maven y vuelve a ejecutar CODES.
	pause
	goto :menu
)

set "JAVA_HOME=%JAVA_EXE:\bin\java.exe=%"
set "PATH=%JAVA_HOME%\bin;%MAVEN_BIN%;%PATH%"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\tools\iniciar_codes_windows.ps1" -JavaPath "%JAVA_EXE%" -MavenBin "%MAVEN_BIN%"
set "EXIT_CODE=%ERRORLEVEL%"
goto :fin

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
