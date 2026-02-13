@echo off
setlocal
cd /d "%~dp0"

set "RID=win-x64"
set "TFM=net8.0-windows10.0.19041.0"
set "DIST=dist\vinilplayer-portable"
set "ZIP=dist\vinilplayer-portable.zip"
set "APP_JAR=target\vinilplayer-1.0.0.jar"
set "READER_PUBLISH=media-reader\bin\Release\%TFM%\%RID%\publish"
set "CONTROLLER_PUBLISH=media-controller\bin\Release\%TFM%\%RID%\publish"

echo [1/6] Publicando media-reader...
dotnet publish media-reader\media-reader.csproj -c Release -r %RID% --self-contained false
if errorlevel 1 exit /b 1

echo [2/6] Publicando media-controller...
dotnet publish media-controller\media-controller.csproj -c Release -r %RID% --self-contained false
if errorlevel 1 exit /b 1

echo [3/6] Empaquetando Maven...
call mvn -DskipTests clean package
if errorlevel 1 exit /b 1

echo [4/6] Armando carpeta portable en %DIST% ...
powershell -NoProfile -ExecutionPolicy Bypass -Command "if (Test-Path '%DIST%') { Remove-Item '%DIST%' -Recurse -Force }; New-Item -ItemType Directory -Path '%DIST%\target\lib' -Force | Out-Null; New-Item -ItemType Directory -Path '%DIST%\media-reader\bin\Release\%TFM%\%RID%' -Force | Out-Null; New-Item -ItemType Directory -Path '%DIST%\media-controller\bin\Release\%TFM%\%RID%' -Force | Out-Null; Copy-Item 'run-vinilplayer.bat' '%DIST%\run-vinilplayer.bat' -Force; Copy-Item '%APP_JAR%' '%DIST%\target\vinilplayer-1.0.0.jar' -Force; Copy-Item 'target\lib\*' '%DIST%\target\lib' -Recurse -Force; Copy-Item '%READER_PUBLISH%\*' '%DIST%\media-reader\bin\Release\%TFM%\%RID%' -Recurse -Force; Copy-Item '%CONTROLLER_PUBLISH%\*' '%DIST%\media-controller\bin\Release\%TFM%\%RID%' -Recurse -Force"
if errorlevel 1 exit /b 1

echo [5/6] Generando ZIP...
powershell -NoProfile -ExecutionPolicy Bypass -Command "if (Test-Path '%ZIP%') { Remove-Item '%ZIP%' -Force }; Compress-Archive -Path '%DIST%\*' -DestinationPath '%ZIP%' -Force"
if errorlevel 1 exit /b 1

echo [6/6] Verificando salida...
if not exist "%DIST%\run-vinilplayer.bat" exit /b 1
if not exist "%DIST%\target\vinilplayer-1.0.0.jar" exit /b 1
if not exist "%DIST%\media-reader\bin\Release\%TFM%\%RID%\media-reader.exe" exit /b 1
if not exist "%DIST%\media-controller\bin\Release\%TFM%\%RID%\media-controller.exe" exit /b 1
if not exist "%ZIP%" exit /b 1

echo.
echo Listo:
echo  - Carpeta: %DIST%
echo  - ZIP: %ZIP%
echo.
echo Entrega recomendada: vinilplayer-portable.zip
exit /b 0
