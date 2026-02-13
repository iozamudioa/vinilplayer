@echo off
setlocal
cd /d "%~dp0"

rem Autoarranque por usuario actual via shell:startup (acceso directo)
set "STARTUP_DIR=%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup"
set "STARTUP_LINK=%STARTUP_DIR%\VinilPlayer.lnk"

rem Limpieza de legado: evitar autoarranque duplicado por clave HKCU\...\Run
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "Remove-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' -Name 'VinilPlayer' -ErrorAction SilentlyContinue" >nul 2>&1

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$shortcutPath = [IO.Path]::GetFullPath('%STARTUP_LINK%');" ^
  "$targetBat = [IO.Path]::GetFullPath('%~f0');" ^
  "$workDir = [IO.Path]::GetFullPath('%~dp0');" ^
  "$ws = New-Object -ComObject WScript.Shell;" ^
  "$s = $ws.CreateShortcut($shortcutPath);" ^
  "$s.TargetPath = $env:ComSpec;" ^
  "$s.Arguments = '/c ""' + $targetBat + '""';" ^
  "$s.WorkingDirectory = $workDir;" ^
  "$s.IconLocation = $targetBat + ',0';" ^
  "$s.Description = 'Inicio automatico de VinilPlayer';" ^
  "$s.Save();" >nul 2>&1

if not exist "target\vinilplayer-1.0.0.jar" (
  echo [vinilplayer] No se encontro target\vinilplayer-1.0.0.jar
  echo Ejecuta primero: mvn clean package
  pause
  exit /b 1
)

if not exist "target\lib" (
  echo [vinilplayer] No se encontro target\lib con dependencias runtime
  echo Ejecuta primero: mvn clean package
  pause
  exit /b 1
)

start "VinilPlayer" javaw --module-path "target\lib" --add-modules javafx.controls,javafx.graphics -jar "target\vinilplayer-1.0.0.jar"
exit /b 0
