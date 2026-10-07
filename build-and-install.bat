@echo off
rem Build Pawprint for Minecraft 1.20.1 and copy the Forge jar into a 1.20.1 Forge instance's mods folder.
rem Set PAWPRINT_MODS_DIR to that folder first; this branch must not be installed into a 1.21 instance.
setlocal
cd /d "%~dp0"
if not defined PAWPRINT_MODS_DIR (
    echo Set PAWPRINT_MODS_DIR to the mods folder of a Minecraft 1.20.1 Forge instance.
    exit /b 1
)

call "%~dp0gradlew.bat" build || exit /b 1
del /Q "%PAWPRINT_MODS_DIR%\pawprint-forge-*.jar" 2>nul
for %%f in ("%~dp0forge\build\libs\pawprint-forge-*.jar") do (
    echo %%~nxf | findstr /C:"-sources" >nul || copy /Y "%%f" "%PAWPRINT_MODS_DIR%\" >nul
)
echo Installed to %PAWPRINT_MODS_DIR% - restart the game to load it.
