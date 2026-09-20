@echo off
setlocal
cd /d "%~dp0"

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

if not defined JAVA_EXE (
	echo Java 21 no encontrado.
	echo Instala JDK 21 y vuelve a ejecutar CODES.
	pause
	exit /b 1
)

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

if not defined MAVEN_BIN (
	echo Maven no encontrado.
	echo Instala Maven y vuelve a ejecutar CODES.
	pause
	exit /b 1
)

set "JAVA_HOME=%JAVA_EXE:\bin\java.exe=%"
set "PATH=%JAVA_HOME%\bin;%MAVEN_BIN%;%PATH%"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\tools\iniciar_codes_windows.ps1" -JavaPath "%JAVA_EXE%" -MavenBin "%MAVEN_BIN%"
set "EXIT_CODE=%ERRORLEVEL%"
endlocal
exit /b %EXIT_CODE%
