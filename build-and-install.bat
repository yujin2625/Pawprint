@echo off
rem Build Pawprint and copy the NeoForge jar into the BMC5 instance mods folder.
rem Override JAVA_HOME / PAWPRINT_MODS_DIR if your paths differ.
setlocal
cd /d "%~dp0"
if not defined JAVA_HOME set "JAVA_HOME=%USERPROFILE%\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2"
if not defined PAWPRINT_MODS_DIR set "PAWPRINT_MODS_DIR=%USERPROFILE%\curseforge\minecraft\Instances\Better MC [NEOFORGE] BMC5\mods"

call "%~dp0gradlew.bat" build || exit /b 1
del /Q "%PAWPRINT_MODS_DIR%\pawprint-neoforge-*.jar" 2>nul
for %%f in ("%~dp0neoforge\build\libs\pawprint-neoforge-*.jar") do (
    echo %%~nxf | findstr /C:"-sources" >nul || copy /Y "%%f" "%PAWPRINT_MODS_DIR%\" >nul
)
echo Installed to %PAWPRINT_MODS_DIR% - restart the game to load it.
