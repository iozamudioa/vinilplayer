$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$dist = Join-Path $root 'dist/vinilplayer-portable'
$zip = Join-Path $root 'dist/vinilplayer-portable.zip'
$tfm = 'net8.0-windows10.0.19041.0'
$rid = 'win-x64'
$readerPublish = Join-Path $root "media-reader/bin/Release/$tfm/$rid/publish"
$controllerPublish = Join-Path $root "media-controller/bin/Release/$tfm/$rid/publish"

if (Test-Path $dist) {
    Remove-Item $dist -Recurse -Force
}

New-Item -ItemType Directory -Path (Join-Path $dist 'target/lib') -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $dist "media-reader/bin/Release/$tfm/$rid") -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $dist "media-controller/bin/Release/$tfm/$rid") -Force | Out-Null

Copy-Item 'run-vinilplayer.bat' (Join-Path $dist 'run-vinilplayer.bat') -Force
Copy-Item 'target/vinilplayer-1.0.0.jar' (Join-Path $dist 'target/vinilplayer-1.0.0.jar') -Force
Copy-Item 'target/lib/*' (Join-Path $dist 'target/lib') -Recurse -Force
Copy-Item (Join-Path $readerPublish '*') (Join-Path $dist "media-reader/bin/Release/$tfm/$rid") -Recurse -Force
Copy-Item (Join-Path $controllerPublish '*') (Join-Path $dist "media-controller/bin/Release/$tfm/$rid") -Recurse -Force

if (-not (Test-Path (Join-Path $dist "media-reader/bin/Release/$tfm/$rid/media-reader.exe"))) {
    throw "No se encontro media-reader.exe en portable"
}

if (-not (Test-Path (Join-Path $dist "media-controller/bin/Release/$tfm/$rid/media-controller.exe"))) {
    throw "No se encontro media-controller.exe en portable"
}

if (Test-Path $zip) {
    Remove-Item $zip -Force
}

Compress-Archive -Path (Join-Path $dist '*') -DestinationPath $zip -Force
Write-Host "Portable listo en: $dist"
Write-Host "ZIP listo en: $zip"
