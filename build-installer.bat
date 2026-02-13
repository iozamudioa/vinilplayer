@echo off
setlocal
cd /d "%~dp0"

echo [1/6] Publicando media-reader...
dotnet publish media-reader\media-reader.csproj -c Release -r win-x64 --self-contained false
if errorlevel 1 exit /b 1

echo [2/6] Publicando media-controller...
dotnet publish media-controller\media-controller.csproj -c Release -r win-x64 --self-contained false
if errorlevel 1 exit /b 1

echo [3/6] Empaquetando Java...
call mvn -DskipTests clean package
if errorlevel 1 exit /b 1

echo [4/6] Preparando input para jpackage...
powershell -NoProfile -ExecutionPolicy Bypass -Command "if (Test-Path 'installer-input') { Remove-Item 'installer-input' -Recurse -Force }; New-Item -ItemType Directory -Path 'installer-input/lib' -Force | Out-Null; New-Item -ItemType Directory -Path 'installer-input/media-reader/bin/Release/net8.0-windows10.0.19041.0/win-x64' -Force | Out-Null; New-Item -ItemType Directory -Path 'installer-input/media-controller/bin/Release/net8.0-windows10.0.19041.0/win-x64' -Force | Out-Null; Copy-Item 'target/vinilplayer-1.0.0.jar' 'installer-input/vinilplayer-1.0.0.jar' -Force; Copy-Item 'target/lib/*' 'installer-input/lib' -Recurse -Force; Copy-Item 'media-reader/bin/Release/net8.0-windows10.0.19041.0/win-x64/*' 'installer-input/media-reader/bin/Release/net8.0-windows10.0.19041.0/win-x64' -Recurse -Force; Copy-Item 'media-controller/bin/Release/net8.0-windows10.0.19041.0/win-x64/*' 'installer-input/media-controller/bin/Release/net8.0-windows10.0.19041.0/win-x64' -Recurse -Force"
if errorlevel 1 exit /b 1

echo [5/6] Generando instalador EXE (Next, Next, Finish)...
if exist "dist\installer" rmdir /s /q "dist\installer"
mkdir "dist\installer"

jpackage ^
  --type exe ^
  --dest dist\installer ^
  --name VinilPlayer ^
  --vendor "iozamudio" ^
  --app-version 1.0.0 ^
  --input installer-input ^
  --main-jar vinilplayer-1.0.0.jar ^
  --main-class net.iozamudio.Main ^
  --module-path installer-input\lib ^
  --add-modules javafx.controls,javafx.graphics,javafx.base,java.desktop,java.net.http ^
  --win-shortcut ^
  --win-menu ^
  --win-dir-chooser
if errorlevel 1 exit /b 1

echo [6/6] Listo.
echo Instalador generado en: dist\installer
dir /b dist\installer
exit /b 0
