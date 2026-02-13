# VinilPlayer v1.0.0

Widget/reproductor de escritorio para Windows con JavaFX + integración multimedia nativa (.NET), control global de reproducción y lyrics sincronizadas.

## Descarga (Release 1.0.0)

> Reemplaza `TU_USUARIO` por tu usuario real de GitHub cuando publiques el repo.

- Página de release: https://github.com/TU_USUARIO/vinilplayer/releases/tag/v1.0.0
- Instalador: `VinilPlayer-1.0.0.exe`
- Portable: `vinilplayer-portable.zip`

## Qué incluye

- Lectura de sesión multimedia activa (artista, título, estado, progreso, duración, portada).
- Controles globales: `play/pause`, `next`, `previous`, `seek`.
- Widget principal con barra de progreso y acento visual dinámico.
- Widget lateral de lyrics sincronizadas con click-to-seek.
- Cache local de lyrics en SQLite (consulta local primero).
- Integración con bandeja del sistema y autoarranque por usuario.

## Requisitos

- Windows 10/11 (x64)
- Para ejecutar release (portable/instalador): no requiere IDE.
- Para desarrollo desde código:
  - JDK 17+
  - Maven 3.9+
  - .NET SDK 8

## Instalación rápida

### Opción A: Instalador (recomendada)

1. Descarga `VinilPlayer-1.0.0.exe` desde la release.
2. Ejecuta el instalador y completa el wizard.
3. Abre VinilPlayer desde menú inicio o acceso directo.

### Opción B: Portable

1. Descarga `vinilplayer-portable.zip`.
2. Extrae el ZIP en cualquier carpeta.
3. Ejecuta `run-vinilplayer.bat`.

## Uso

- VinilPlayer detecta automáticamente la sesión multimedia activa de Windows.
- Desde el widget puedes pausar/reanudar, cambiar pista y mover progreso.
- El panel de lyrics sincroniza líneas por timestamp y permite seek al hacer click.

## Ejecución en desarrollo

```bat
mvn clean javafx:run
```

Opciones útiles:

- `--demo`
- `--fade-ms=3000`
- `-Dvinil.fade.ms=3000`

## Build de artefactos

### Portable

```bat
build-portable.bat
```

Salida:

- `dist/vinilplayer-portable/`
- `dist/vinilplayer-portable.zip`

### Instalador

```bat
build-installer.bat
```

Salida:

- `dist/installer/` (incluye el `.exe` generado por `jpackage`).

## Publicar release v1.0.0 en GitHub

Cuando tengas `git` instalado:

```bash
git init
git add .
git commit -m "release: v1.0.0"
git branch -M main
git remote add origin https://github.com/TU_USUARIO/vinilplayer.git
git push -u origin main
git tag v1.0.0
git push origin v1.0.0
```

Luego en GitHub:

1. Ve a **Releases** → **Draft a new release**.
2. Selecciona el tag `v1.0.0`.
3. Sube los assets:
   - `dist/vinilplayer-portable.zip`
   - `dist/installer/*.exe`
4. Publica la release.

## Estructura técnica (resumen)

- UI en JavaFX.
- Capa de aplicación por puertos/adaptadores.
- Integración multimedia nativa vía procesos auxiliares:
  - `media-reader` (.NET): lectura de sesión GSMTC.
  - `media-controller` (.NET): comandos multimedia globales.
- Provider de lyrics con cache local SQLite.

## Solución de problemas

- Si falta JAR/runtime: ejecuta `mvn clean package`.
- Si falla portable por binarios .NET: ejecuta `dotnet publish` mediante `build-portable.bat`.
- Si no detecta media activa: inicia reproducción en Spotify/YouTube y espera unos segundos.

## Licencia

Define aquí tu licencia (por ejemplo MIT) antes de publicar de forma pública.
- “La app es pequeña. El criterio para mantenerla decente no.”
- “Funciona, se apaga bien, y no deja procesos huérfanos. Básicamente ciencia ficción en desktop apps.”

Si llegaste hasta aquí: sí, el diseño fue intencional.
Y sí, también nos divertimos un poco en el proceso.