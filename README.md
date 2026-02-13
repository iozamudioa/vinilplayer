# VinilPlayer

Widget de escritorio para Windows que toma la sesión multimedia activa del sistema, la convierte en una UI tipo “vinilo elegante”, y además te deja controlar reproducción, seek y lyrics sincronizadas sin vivir dentro del navegador.

Sí, podría ser más simple… pero ¿dónde estaría la diversión?

---

# Descripción del Proyecto

**VinilPlayer** es una aplicación híbrida:

- **Frontend/UX:** JavaFX (widget principal + widget de lyrics).
- **Integración nativa multimedia:** dos procesos auxiliares en .NET (`media-reader` y `media-controller`) que hablan con GSMTC (Global System Media Transport Controls de Windows).
- **Lyrics:** consulta LRCLIB por HTTP y cachea resultados en SQLite local para no pedir lo mismo eternamente.

### Qué resuelve

- Detecta canción activa (artista, título, estado, posición, duración, thumbnail).
- Muestra progreso fluido e interfaz animada.
- Permite `play/pause`, `next`, `previous`, `seek` y “abrir/focus source”.
- Renderiza lyrics sincronizadas con highlight temporal y click-to-seek.
- Sobrevive a escenarios reales: procesos colgados, sesiones vacías, fallbacks, cierres limpios.

Funciona — y además es mantenible. Milagro moderno.

---

# Filosofía del Diseño

La idea central fue evitar que “un reproductor bonito” termine siendo un monolito de UI acoplada a APIs nativas con pánico al cambio.

### Principios aplicados

1. **Puertos y adaptadores (estilo hexagonal pragmático)**
  - El core no sabe si la media viene de script, .NET o magia oscura.
  - La UI usa casos de uso, no APIs nativas directas.

2. **Tolerancia a fallos y degradación controlada**
  - Si `media-controller` falla, hay fallback con simulación de teclas multimedia.
  - Si lyrics fallan o no existen, el sistema no colapsa; informa estado y continúa.

3. **Ciclo de vida explícito**
  - Startup: instancia única, tray, taskbar buttons, autoarranque, fade-in.
  - Runtime: polling/suscripción, interpolación de progreso, fetch de lyrics.
  - Shutdown: autopause opcional, unsubscribe, cierre/kill de procesos auxiliares.

4. **UX fluida sin sacrificar control**
  - Interpolación local del progreso.
  - Marquee cuando texto excede el viewport.
  - Scroll de lyrics animado, acento dinámico por servicio.

No es sobreingeniería, es prevención del sufrimiento futuro.

---

# Arquitectura

## Vista general

`JavaFX UI` → `Use Cases (application)` → `Ports` → `Adapters (infra)` → `.NET helpers / Windows APIs / LRCLIB`

## Capas y responsabilidades reales

### 1) Dominio (`model`)

- `MediaInfo` (record inmutable): normaliza valores nulos/negativos, calcula progreso y estado (`isPlaying`, `isEmpty`).
- `LyricsLine` (record inmutable): línea sincronizada con `timeSeconds` normalizado.

Dominio pequeño, con intención: modelar lo necesario y no inventar una ontología musical de 30 clases.

### 2) Aplicación (`application.service`)

- `MediaPollingService`
  - Implementa **dos puertos de entrada**: `MediaPollingUseCase` y `MediaControlUseCase`.
  - Puede trabajar en modo **suscripción** (`MediaInfoSubscriptionPort`) o **polling** (`MediaInfoProviderPort`).
  - Mantiene `latestInfo` para lógica de arranque/cierre (`attemptAutoPlayIfStopped`, `autoPauseIfPlaying`).

- `LyricsService`
  - Valida `artist/title` y delega al proveedor de lyrics.

### 3) Puertos (`application.port.in` / `application.port.out`)

- Entrada (use cases): control multimedia, polling, lyrics.
- Salida: contratos de media info, suscripción, control y provider de lyrics.

Resultado: el core depende de interfaces, no de detalles de implementación ni de una API concreta del sistema operativo.

### 4) Infraestructura (`infrastructure`)

- `ScriptMediaInfoProviderAdapter`
  - Resuelve binario `media-reader` según contexto (dev/build/jpackage).
  - Levanta proceso (`exe/dll/ps1`), consume JSON por `stdout` y monitorea `stderr`.
  - Gestiona unsubscribe y limpieza de árbol de procesos con fallback `taskkill`.

- `WindowsMediaControlAdapter`
  - Ejecuta `media-controller` con comandos (`next`, `previous`, `playpause`, `seek`, `focussource`).
  - Fallback a `MediaKeySimulator` cuando no hay binario o falla invocación.

- `LrcLibLyricsProviderAdapter`
  - Consulta LRCLIB vía `HttpClient` con timeout y reintento acotado.
  - Parsea LRC sincronizado y persiste cache en SQLite.

- `SqliteLyricsCache`
  - Cache por `(artist_norm, title_norm)` con UPSERT.
  - Actualiza `last_played`, `play_count` y poda por LRU simple (`MAX_CACHE_ROWS = 400`).

---

# Flujo del Sistema

## Arranque

1. `Main.main()` intenta adquirir instancia primaria (`SingleInstanceManager`).
2. Si ya existe instancia, envía señal `SHOW` a la app viva y termina.
3. `Main.start()` hace wiring:
  - Provider real o demo,
  - adaptador de control,
  - provider de lyrics,
  - `MediaPollingService`,
  - `VinylPlayerView`.
4. Configura tray, autoarranque, taskbar media buttons y fade-in de volumen.
5. Arranca polling/suscripción y ejecuta autoplay de inicio si detecta estado detenido.

## Runtime

1. `media-reader` emite snapshots JSON cada ~500 ms.
2. `MediaPollingService` despacha updates a la UI.
3. `VinylPlayerView` actualiza:
  - metadata,
  - estado play/pause,
  - progreso interpolado,
  - thumbnail (fondo blur + etiqueta del vinilo),
  - lyrics (si cambió track).
4. Lyrics:
  - cache HIT: lectura local,
  - cache MISS: fetch LRCLIB, parseo, persistencia.
5. Usuario interactúa desde:
  - controles del widget,
  - botones taskbar,
  - widget de lyrics (click-to-seek),
  - doble click en disco para abrir fuente activa.

## Cierre

1. `onClose` desmonta botones de taskbar y cierra executor de lyrics.
2. `MediaPollingService.autoPauseIfPlaying()` intenta pausar si estaba reproduciendo.
3. `shutdown()` detiene hilo y `unsubscribe()` limpia procesos auxiliares.
4. Se elimina tray y se libera lock de instancia única.

Esto existe porque el caos también necesita orden.

---

# Componentes Clave

## `Main`

Orquestador de composición de dependencias y ciclo de vida de la app.

- Modo demo: `--demo`.
- Fade configurable: `--fade-ms` o `-Dvinil.fade.ms`.
- Integración de bandeja, startup y restauración de ventana.

## `MediaPollingService`

Núcleo operativo.

- Ejecuta como `Thread` daemon.
- Se adapta a `subscribe/unsubscribe` o `getCurrent` por polling.
- Centraliza control multimedia y estado `latestInfo` para decisiones de arranque/cierre.

## `VinylPlayerView`

UI principal (JavaFX) con lógica de interacción rica.

- Ventana transparente, arrastrable y con blur de portada.
- Disco de vinilo animado con `RotateTransition`.
- Marquee inteligente del título/artista.
- Barra de progreso con seek por click + timeline de interpolación cada 50 ms.
- Botones de servicio (YouTube/Spotify/Apple/Amazon) con detección de handlers de protocolo en Windows.
- Integración con `WindowsTaskbarMediaButtons` para controles de miniatura.

## `LyricsWidgetView`

Panel desacoplado de lyrics sincronizadas.

- Posicionamiento automático a la derecha de la ventana principal.
- Highlight activo por timestamp.
- Scroll animado con duración adaptativa.
- Click en línea => `seekToSeconds(...)`.

## `WindowsTaskbarMediaButtons`

Integración COM/JNA con `ITaskbarList3`.

- Inyecta botones `open/previous/play-pause/next` en miniatura de taskbar.
- Hook de `WndProc` para capturar `THBN_CLICKED`.
- Renderiza íconos `.ico` en runtime y los cachea en disco.

## Helpers .NET

### `media-reader`
- Obtiene sesión GSMTC (actual o primera disponible).
- Emite JSON continuo con metadata + timeline + thumbnail base64.
- Incluye mutex global para evitar instancias duplicadas.

### `media-controller`
- Ejecuta media keys globales.
- `seek` vía `TryChangePlaybackPositionAsync`.
- `focussource` intenta frontear proceso origen o abrir URI de servicio.

---

# Decisiones Técnicas

1. **Java + .NET en lugar de “todo en uno”**
  - JavaFX ofrece control UI sólido.
  - WinRT/GSMTC es más directo y estable desde .NET.
  - Se separan responsabilidades por proceso.

2. **Proveedor de media por proceso externo**
  - Aísla crashs y comportamientos erráticos del acceso nativo.
  - Facilita diagnosticar con logs `stdout/stderr`.

3. **Cache SQLite para lyrics**
  - Reduce latencia y tráfico repetido.
  - Persistencia simple, local y sin infraestructura adicional.

4. **Fallbacks explícitos**
  - Control primario por `media-controller`.
  - Fallback a simulación de teclas multimedia cuando toca sobrevivir.

5. **Interfaz por puertos**
  - Bajo acoplamiento entre core y adaptadores.
  - Cambios de infraestructura sin reventar la lógica de aplicación.

---

# Buenas Prácticas

- Modelos inmutables (`record`) para estado compartido.
- Validación defensiva de datos externos (`null`, rangos, parseos).
- Timeouts en red y operaciones nativas para evitar esperas infinitas.
- Hilos daemon y `Platform.runLater` para seguridad de hilo en UI.
- Limpieza explícita de procesos hijos y recursos en cierre.
- Contratos de entrada/salida claros (puertos) para mantener testabilidad conceptual.

No, no es “extra”. Es lo mínimo para que producción no sea un deporte extremo.

---

# Consideraciones de Rendimiento

- Snapshot de media cada **500 ms** desde helper nativo.
- Interpolación visual del progreso cada **50 ms** en UI para sensación de fluidez.
- Lyrics se solicitan **solo cuando cambia track**.
- Cache local evita fetch repetitivo y reduce latencia percibida.
- Reintentos de red controlados (`MAX_ATTEMPTS = 2`) para no bloquear UX.

---

# Posibles Mejoras

1. **Observabilidad real**
  - Métricas: latencia reader/controller, tasa de fallos, cache hit ratio.

2. **Selección de sesión avanzada**
  - Política configurable cuando hay múltiples sesiones simultáneas.

3. **Tests de integración entre puertos y adaptadores**
  - Especialmente contratos de JSON del `media-reader` y comandos del `media-controller`.

4. **Configuración externa de runtime**
  - Intervalos, retries, startup behavior, temas/acentos por usuario.

5. **Empaquetado CI/CD de instalador**
  - Pipeline para generar y adjuntar assets de release automáticamente.

---

# Cómo Ejecutarlo

## Requisitos

- Windows 10/11
- JDK 17+
- Maven
- .NET SDK 8+

## Desarrollo

```bash
mvn clean javafx:run
```

Opciones:

- `--demo`
- `--fade-ms=3000`
- `-Dvinil.fade.ms=3000`

## Build portable

```bat
build-portable.bat
```

Salida esperada:

- `dist/vinilplayer-portable/`
- `dist/vinilplayer-portable.zip`

## Build installer

```bat
build-installer.bat
```

Salida esperada:

- `dist/installer/` con el `.exe` generado por `jpackage`.

## Ejecutar release local

```bat
run-vinilplayer.bat
```

Este launcher:

- crea/actualiza acceso directo en `shell:startup`;
- limpia configuración legacy en `HKCU\...\Run`;
- ejecuta `javaw` con módulo JavaFX y JAR principal.

---

# Estructura del Repositorio

```text
src/main/java/net/iozamudio/
  application/
   port/in|out
   service/
  infrastructure/
   media/
   lyrics/
  model/
  ui/
  util/

media-reader/        # Helper .NET: lectura GSMTC
media-controller/    # Helper .NET: control multimedia + focus source
scripts/             # utilidades de diagnóstico y build
```

---

# Notas del Arquitecto

- “Sí, podría ser más simple… pero ¿dónde estaría la diversión?”
- “Esto existe porque el caos también necesita orden.”
- “No es sobreingeniería, es prevención del sufrimiento futuro.”
- “Funciona — y además es mantenible. Milagro moderno.”

Si algo falla en producción, no será por falta de intención arquitectónica.
- “La app es pequeña. El criterio para mantenerla decente no.”
- “Funciona, se apaga bien, y no deja procesos huérfanos. Básicamente ciencia ficción en desktop apps.”

Si llegaste hasta aquí: sí, el diseño fue intencional.
Y sí, también nos divertimos un poco en el proceso.