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

## Estrategia
No se mueve el pin a `master`: se portan sus correcciones sobre nuestra base (`df67c21` + parches
0001–0009), una por parche. Los parches 0010–0029 de `app/src/main/cpp/patches/UxPlay/` se generan
desde esa base y se aplican en orden con `git apply --unidiff-zero`.

## Portado (0010–0029)
| Parche | Origen | Nota |
|---|---|---|
| 0010 | `ba2bd19` plist_free | |
| 0011 | `69ed4af` callbacks de llhttp | |
| 0012, 0013 | `d7a088d`, `e60d981` httpd sin bucle bloqueante, timeout 60 s | 0021 (`5812f84`) mueve la comprobación antes de `FD_SET` |
| 0014 | `4e95a9e` keepAliveLowPower booleano | |
| 0015 | `3fda790` parseo de EVENT (HTTP inverso) | |
| 0016 | `517cbb1` raop_rtp_mirror sin malloc, fuga | |
| 0017 | `bced293` fugas de `-pw` | |
| 0018 | `ab59633` nonce de 32 bytes | |
| 0019 | `28ed70a` cero inicial en SRP6a | |
| 0020 | `e52f553` UBSan en byteutils | |
| 0021 | `5812f84` correcciones de @boyan01 | Sin el bloque de `rtp_session_dsock` (no existe en nuestra base) |
| 0022 | `7aaad62` tipo de NAL h265 | |
| 0023 | `b1be2bd` mirror_buffer (#615) | |
| 0024 | `78d73e1` (PR #631) `&&` en el descarte de SPS/PPS y `free(payload)` | Portado a mano |
| 0025 | `ec20312` paquetes de audio sin datos | Conflicto resuelto en `raop_buffer.c` |
| 0026 | PR #624 ignorar `POST /stop` de una sesión reemplazada | Portado a mano; sigue abierto upstream |
| 0027 | `234b407` `UL` → `ULL` | Incluye `raop_ntp.c`, que el commit no cubría limpio |
| 0028 | `60e6430` fugas de SRP6a | Corrige dos fallos del original: `"%llu", 16` y `"(&d,%d)"` con punteros liberados; libera los plist en el 400 |
| 0029 | Propio | `b1be2bd` solo descifraba el primer byte cuando la trama es más corta que el keystream sobrante |

## No portado
- `b29411f` (fuga de `rtp_session_dsock`): ese campo no existe en nuestra base.
- `87e004c` (fuga de `default_lang`): el selector de idiomas de `master_playlist_slicer` no está en la base.
- `412c5b7` (volumen): la parte de `lib/` ya está cubierta por el parche 0006; el resto es GStreamer.
- Reescritura de NTP, FairPlay v2, filtro de códecs HLS: dependen del cambio de API de `master`.

## Segunda pasada: seguridad y dependencias (2026-11-18)
| Hallazgo | Clase | Acción |
|---|---|---|
| libplist: un bplist de 1 MB con 2000 referencias a un dato de 1 MB pide ~2 GB (reproducido en host) | Defecto probado, alcanzable desde la LAN sin emparejar | `patches/libplist/0001`: tope de 4× la entrada + 1 MiB |
| UxPlay `http_request.c`: URL, cabeceras y cuerpo crecen sin límite | Defecto probado | Parche 0030: 64 KiB de cabecera, 32 MiB de cuerpo |
| `NewStringUTF` con DACP-ID, Active-Remote y URL de `/play` del emisor: bytes no Modified UTF-8 abortan el proceso | Exposición (depende de bytes no ASCII del emisor) | Codificación `%XX` en `android_raop_callbacks.c` |
| OpenSSL se descargaba de `mirror.viaduck.org` sin hash | Cadena de suministro | `OPENSSL_BUILD_HASH` con el SHA256 oficial de 4.1.0 |
| FFmpeg `release/9.0`: correcciones del decodificador AAC tras 9.0.2 (`4cf298e66a`, `c89d6ee1ca`, `2a571b6068`) | Correcciones upstream | Pin a `d838f9f9ae` |
| OpenSSL, aviso del 10-11-2026 (QUIC, AES-GCM-SIV, CMS) | No aplica: solo usamos AES-CBC/CTR/GCM, SHA y MD5 | Pasar a 4.1.1 cuando salga (programada para el 1-12-2026) |
| CVE-2025-60458 (UxPlay 1.72, doble free en TEARDOWN) | Corregido en 1.73.3; el pin es 1.73.7 | Ninguna |
| UxPlay PR #625 (User-Agent AppleCoreMedia) | Mejora | Aplicado al `DataSource` HTTP de ExoPlayer; la descarga (`VideoDownloader`) no lo usa todavía |
| Dependencias Gradle | Todas en la última estable (Maven, 2026-11-18) | Ninguna |

Riesgo abierto: el timeout de 60 s (parche 0013) también cierra el canal EVENT inactivo. Si el vídeo HLS
falla tras una pausa larga, es el primer sospechoso.

## Calidad de imagen del mirroring (referencia: moonlight-android `Game.java`)
- El blit GL dibujaba en un buffer del tamaño de la vista (resolución de la UI) y el compositor lo
  reescalaba al panel: dos remuestreos. Ahora `setFixedSize` al tamaño del stream: copia 1:1 y un único
  escalado por hardware, como cuando MediaCodec dibuja directo en un `SurfaceView`.
- `Surface.setFrameRate(fps, FIXED_SOURCE, CHANGE_FRAME_RATE_ALWAYS)` como Moonlight en TV; se borra al
  terminar el mirroring. Un cambio de modo no instantáneo puede dejar la pantalla en negro un momento.
- Media3 solo ofrece `OFF` y `ONLY_IF_SEAMLESS` para el cambio de frecuencia en HLS: no se toca.

## Verificado
`assembleDebug` (arm64-v8a, armeabi-v7a, x86_64), `testDebugUnitTest`, `lintDebug`, `assembleSanitize` y
`scripts/native-regress.sh` pasan. Comportamiento en TV: pendiente de validación física.

## Pendiente
- OpenSSL 4.1.1 (1-12-2026).
- User-Agent en `VideoDownloader`.
