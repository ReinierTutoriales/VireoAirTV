# Auditoría de proyectos de referencia (2026-11-18)

Fuentes leídas con git (commits, ramas y refs de PR), no con resúmenes:
- UxPlay `FDH2/UxPlay`: `master` en `48f5ce4` (2026-11-17), ramas activas y PR #624–#637.
- `jqssun/android-airplay-server`: sin commits nuevos desde `a46cca0` (2026-10-19).

## Punto de partida
Nuestro UxPlay está en `df67c21` (rama `release/1.73.7`, 2026-09-04). `master` se separó de esa rama
en junio y lleva **122 commits** que no tenemos; **72 tocan `lib/`**, la única parte que compila la app
(42 archivos, +5951/−1731). Los 9 parches de jqssun están hechos sobre el pin: con `master`, 6 no
aplican, 2 aplican con 3-way y 1 aplica limpio.

## Relevante para lo observado en TV

| Cambio upstream | Estado | Por qué importa |
|---|---|---|
| `78d73e1` raop_rtp_mirror: descarte de SPS/PPS con `&&` (PR #631) | En `master` | `sps_pps_len & (...)` solo miraba el bit bajo: un SPS/PPS viejo se anteponía a otro fotograma. Corrupción visible en mirroring |
| `e405fe1` acotar la longitud de NAL / `f0b042b` reiniciar AES-CTR al recifrar | En `master` (equivalentes en `release/1.73.7`) | Robustez del flujo de mirroring |
| `b1be2bd`, `517cbb1`, `5812f84`, `7aaad62` raop_rtp_mirror / mirror_buffer | En `master` | Fugas y búfer de mirroring |
| `4bfa594` reescritura de NTP, `4c6e017` sync_delay, `dc5ce10` client_time_received | En `master` | Sincronía A/V y reloj del emisor |
| `ec20312` paquetes de audio sin datos al búfer (conserva la secuencia) | En `master` | Cortes de audio y desorden de paquetes |
| `412c5b7` volumen coherente con el del emisor | En `master` | Volumen |
| `e60d981` timeout de 60 s para conexiones muertas, `d7a088d` sin bucle bloqueante | En `master` | Conexiones colgadas ("3 connected" sin emisores activos) |
| `35bc295` filtro de playlist HLS por códecs del host + `get_custom_profile` | En `master` | El receptor anuncia qué códecs puede decodificar y descarta variantes que no puede: calidad y fallos de HLS |
| `6ab8bf6` FairPlay v2 (Música de macOS) | En `master` | Compatibilidad |
| `28ed70a`, `ab59633`, `60e6430` emparejamiento por PIN | En `master` | Emparejamiento y fugas |
| **PR #624** ignorar `POST /stop` de una sesión de vídeo reemplazada | **Abierto** | Un emisor que abre una sesión nueva al cambiar de vídeo manda luego `/stop` con el ID antiguo y para el vídeo nuevo |
| **PR #625** User-Agent de Apple TV (AppleCoreMedia) para HLS | **Abierto**, para GStreamer | Algunos CDN rechazan el User-Agent genérico. Aplicable a nuestro ExoPlayer |
| PR #633, #636 | Abiertos | Solo `uxplay.cpp` y renderers de escritorio; no se compilan en la app |

## Cambios de API que afectan al puente JNI
- `video_report_size` + `video_set_codec` → `video_set_codec_and_size(...)`.
- Nuevo callback `get_custom_profile` (lo usa el filtro de playlists HLS).
- `airplay_video_init` y `raop_set_lang` con idiomas de subtítulos y sistema; `ntp_global_init()`.
- Callbacks retirados: `conn_teardown`.

## Plan (rama `develop`, sin publicar hasta validarlo en TV)
1. Mover UxPlay a `master` y portar los 9 parches de jqssun: descartar los que `master` ya resuelve y
   adaptar el resto, uno por commit.
2. Adaptar `android_raop_callbacks.c` y `native_bridge.cpp` a la API nueva; implementar
   `get_custom_profile` con los códecs que el televisor decodifica de verdad (`MediaCodecList`).
3. Aplicar #624 como parche propio mientras siga abierto.
4. User-Agent AppleCoreMedia en el `DataSource` HTTP de ExoPlayer (equivalente a #625).
5. Compilar las 3 ABIs, pasar lint y pruebas y la variante `sanitize`; después, validación física.
