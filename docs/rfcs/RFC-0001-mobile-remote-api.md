# RFC-0001: Mobile Remote API for VinilPlayer

- **Estado:** Proposed
- **Autor:** VinilPlayer Team
- **Fecha:** 2026-02-13
- **Target Release:** 1.1.0
- **Supersedes:** N/A

---

## 1. Resumen

Este RFC propone exponer una API local en VinilPlayer para desacoplar el estado/control de reproducción de la UI JavaFX actual y habilitar un cliente remoto (Flutter en móvil) que:

1. Lea en tiempo real lo que hoy pinta la UI.
2. Envíe comandos de control (`play/pause`, `next`, `previous`, `seek`, `focussource`).

En otras palabras: convertir VinilPlayer en una app con **núcleo + clientes**, no en “una ventana bonita que además hace cosas”.

Sí, podría seguir siendo desktop-only… pero también podríamos seguir depurando por telepatía.

---

## 2. Motivación

### Problema actual

- El estado de reproducción vive acoplado a la app desktop.
- No hay contrato externo para consumir ese estado.
- No hay control remoto móvil sin hacks (RDP, AnyDesk o sufrimiento).

### Oportunidad

- Reutilizar la lógica ya existente de media polling/control.
- Exponer un contrato estable y versionado.
- Permitir UI alternativa (Flutter) sin tocar el core multimedia.

---

## 3. Objetivos

1. Exponer endpoint de estado con payload suficiente para replicar el widget actual.
2. Exponer endpoint de comandos para control remoto.
3. Soportar actualización near real-time para móvil.
4. Mantener compatibilidad hacia atrás en API (`/api/v1`).
5. Incluir seguridad mínima para uso en red local.

---

## 4. No-Objetivos

- Streaming de audio (no somos Spotify Connect 2.0, todavía).
- Control multiusuario/multi-device concurrente avanzado.
- Cloud relay / acceso desde internet pública en 1.1.0.
- Reemplazar UI JavaFX existente.

---

## 5. Diseño de Alto Nivel

### 5.1 Componentes

1. **Playback Core** (actual): obtiene estado y ejecuta controles (media-reader/media-controller).
2. **State Snapshot Service** (nuevo): consolida estado en memoria para API.
3. **Local API Server** (nuevo): HTTP + stream en tiempo real.
4. **Auth Guard** (nuevo): validación de token para comandos (y opcionalmente para lectura).
5. **Flutter Client** (externo): consume estado y envía acciones.

### 5.2 Principio arquitectónico

- **Fuente de verdad única:** el estado que consume JavaFX y el que consume API sale de la misma estructura en memoria.
- **UI agnóstica:** JavaFX y Flutter son clientes; el core no depende de ninguno.

No es sobreingeniería, es prevención del sufrimiento futuro.

---

## 6. Contrato API (v1)

Base URL:

- `http://<host>:<port>/api/v1`

### 6.1 Health

- `GET /health`
- Respuesta:
  - `status`: `ok`
  - `version`: app version
  - `uptimeMs`

### 6.2 Estado actual

- `GET /state`
- Respuesta (shape propuesta):

- `timestamp` (ISO-8601)
- `playback`
  - `status` (`PLAYING|PAUSED|STOPPED`)
  - `positionSeconds`
  - `durationSeconds`
  - `progress` (0..1)
- `track`
  - `artist`
  - `title`
  - `thumbnailBase64` (nullable)
- `lyrics`
  - `available` (bool)
  - `activeLineIndex` (int)
  - `lines` (`[{ timeSeconds, text }]`, opcional por tamaño)
- `uiHints`
  - `accentColor` (hex)
  - `service` (`SPOTIFY|YOUTUBE|APPLE|AMAZON|UNKNOWN`)
- `capabilities`
  - `canPlayPause`
  - `canSeek`
  - `canNext`
  - `canPrevious`
  - `canFocusSource`

### 6.3 Stream de estado en tiempo real

- **Preferido 1.1.0:** `GET /state/stream` por **SSE** (`text/event-stream`).
- Evento: `state` con payload equivalente a `/state`.
- Heartbeat cada 10–15 s para mantener conexión.

> Alternativa: WebSocket. SSE se recomienda para reducir complejidad inicial en server.

### 6.4 Comandos de control

- `POST /control`
- Request:
  - `action`: `playpause|next|previous|seek|focussource`
  - `seekSeconds`: requerido cuando `action=seek`
  - `requestId`: opcional (idempotencia/tracking)

- Response:
  - `accepted` (bool)
  - `executedAt` (timestamp)
  - `state` (snapshot post-comando best-effort)

### 6.5 Errores

- `400`: payload inválido
- `401`: no autenticado
- `403`: token válido pero no autorizado (si aplica)
- `409`: conflicto de estado (ej. seek sin track válido)
- `500`: error interno

Error payload estándar:

- `code` (string)
- `message` (human readable)
- `details` (optional)
- `requestId` (optional)

---

## 7. Seguridad

### 7.1 Requisitos mínimos 1.1.0

1. **Token estático local** (configurable) para `/control`.
2. Bind configurable:
   - Default seguro: `127.0.0.1`
   - Modo LAN explícito: `0.0.0.0` (opt-in)
3. Si LAN habilitado, permitir **allowlist CIDR** opcional.
4. Rate limiting básico para comandos.

### 7.2 Riesgos conocidos

- Exponer control en LAN sin token = “vecino DJ”.
- Token hardcoded en cliente móvil = deuda técnica si se filtra.

### 7.3 Mitigaciones siguientes (1.2+)

- Pairing por código QR temporal.
- Rotación de token.
- TLS local opcional (mTLS overkill en 1.1.0).

---

## 8. Consistencia y Modelo de Estado

### 8.1 Fuente única

Se propone introducir un `PlaybackStateSnapshot` inmutable compartido por:

- JavaFX view model updater
- API `/state`
- Emisor SSE

### 8.2 Frecuencia de actualización

- Core ya produce updates ~500ms.
- SSE puede enviar solo cambios significativos para no saturar red móvil.

### 8.3 Estrategia de lyrics en API

- `GET /state`: puede devolver solo metadatos + línea activa para liviandad.
- Endpoint opcional: `GET /lyrics/current` para líneas completas si se desea paginar/cargar on-demand.

---

## 9. Plan de Implementación

### Fase 1 (MVP API local)

- Añadir servidor HTTP embebido.
- Implementar `GET /health`, `GET /state`, `POST /control`.
- Integrar con estado actual de `MediaPollingService`.
- Auth simple por token para control.

### Fase 2 (Tiempo real)

- Implementar `GET /state/stream` (SSE).
- Cliente Flutter con reconexión exponencial.
- Heartbeat + detección de stale state.

### Fase 3 (Hardening)

- Configuración de bind/puerto/token por archivo o UI settings.
- Rate limit y auditoría de comandos.
- Tests de contrato + tests E2E básicos entre desktop y móvil.

---

## 10. Observabilidad

Métricas sugeridas:

- `api_requests_total{route,status}`
- `control_commands_total{action,result}`
- `state_stream_clients_current`
- `state_publish_latency_ms`

Logs clave:

- Auth failures
- Command accepted/rejected
- SSE connect/disconnect

---

## 11. Compatibilidad

- API versionada bajo `/api/v1`.
- Cambios breaking solo con `/api/v2`.
- Campos nuevos en responses deben ser aditivos y opcionales.

---

## 12. Impacto en Código Actual

### Mínimos cambios esperados

- `MediaPollingService` o capa superior deberá exponer snapshot consistente thread-safe.
- `VinylPlayerView` dejará de ser la “única consumidora privilegiada” del estado.
- Se añade módulo de infraestructura para servidor API.

### Cambio cultural (el bueno)

- Pasamos de app UI-céntrica a plataforma local con múltiples clientes.

Esto existe porque el caos también necesita orden.

---

## 13. Trade-offs

### SSE vs WebSocket

- **SSE (propuesta):** simple, suficiente para stream unidireccional de estado.
- **WebSocket:** más flexible, más complejidad inicial.

### Exponer thumbnail base64 siempre

- Pro: cliente móvil fácil.
- Contra: payload grande.
- Mitigación: enviar hash y endpoint de asset o enviar solo al cambio.

---

## 14. Criterios de Aceptación (1.1.0)

1. App desktop mantiene comportamiento actual sin regresiones visibles.
2. `GET /state` refleja estado real y consistente.
3. `POST /control` ejecuta comandos base correctamente.
4. Cliente Flutter puede:
   - ver track/estado/progreso,
   - ejecutar play/pause/next/previous/seek.
5. API protegida por token para comandos.

Funciona — y además es mantenible. Milagro moderno.

---

## 15. Preguntas Abiertas

1. ¿Puerto por defecto? (`8750` sugerido)
2. ¿`/state` incluye lyrics completas o endpoint separado?
3. ¿Token en archivo local o generado en primer arranque?
4. ¿SSE suficiente para 1.1.0 o arrancamos con WebSocket?
5. ¿Activamos LAN por defecto (no recomendado) u opt-in (recomendado)?

---

## 16. Recomendación Final

Aprobar RFC con estas decisiones para cerrar alcance rápido:

1. API REST + SSE
2. Token obligatorio para comandos
3. Bind por defecto a localhost
4. LAN y allowlist como opt-in
5. Lyrics resumidas en `/state` y detalle en endpoint separado

No es sobreingeniería, es prevención del sufrimiento futuro.
