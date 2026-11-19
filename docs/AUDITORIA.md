# Auditoría de la base (jqssun/android-airplay-server)

Fecha: 2026-11-16 · Commit base: `a46cca0` (v0.0.36)

## Estado verificado
- Mantenimiento activo (último commit upstream: 2026-10-19).
- Motor: UxPlay 1.73.7 como submódulo + 9 parches en `app/src/main/cpp/patches/UxPlay/`.
- Dependencias nativas: OpenSSL, libplist, FFmpeg (solo ALAC). CMake + NDK 27 (NDK 30 desde 2026-11-17).
- compileSdk/targetSdk 37, minSdk 23 (24 desde 2026-11-17). ABIs: arm64-v8a, armeabi-v7a, x86_64.
- Variante `sanitize` (HWASan + UBSan) disponible.
- Licencia GPL-3.0 (aplica a todo el APK y a cualquier distribución derivada).
- Funciones: mirroring H.264/HEVC, AAC-ELD/LC/ALAC, vídeo HLS (ExoPlayer), metadatos y carátula, DACP, PIN opcional, D-pad, PiP, overlay de diagnóstico, ajustes por ADB (`SettingsReceiver`, protegido con `DUMP`), MAC estable persistida.
- DRM (app Apple TV): no soportado.

## Correcciones al documento de arquitectura
| Documento | Código real |
|---|---|
| Construir receptor mínimo (Fase 2) | Ya existe y se publica en Play/F-Droid |
| Encapsular el motor | Ya: submódulo + parches + JNI (`native_bridge.cpp`, `RaopCallbackHandler`) |
| Android 15 bloquea autoarranque | Solo bloquea dataSync, camera, mediaPlayback, phoneCall, mediaProjection, microphone. El servicio arranca como `connectedDevice`; prerrequisito cubierto por `CHANGE_WIFI_MULTICAST_STATE` |
| Gestionar `ACCESS_LOCAL_NETWORK` | Ya declarado y comprobado (`hasLocalNetwork()`) |

## Brechas a resolver en VireoAirTV
1. `AirPlayService.onStartCommand` devuelve `START_NOT_STICKY` → sin recuperación si el sistema mata el proceso.
2. Sin `ConnectivityManager.NetworkCallback` → cambios de IP/Wi-Fi/Ethernet no re-registran mDNS ni reinician el servidor.
3. `NsdServiceManager`: sin reintento en `onRegistrationFailed`.
4. "Abrir al conectar" depende de `SYSTEM_ALERT_WINDOW`; en muchos Android TV solo se concede por ADB:
   `adb shell appops set <applicationId> SYSTEM_ALERT_WINDOW allow`
5. Sin watchdog del motor nativo (sesión colgada = reinicio manual).
6. Sin lista de dispositivos de confianza ni revocación (solo PIN global).

## Plan
- Fase 0: probar el APK upstream en hardware real (mirroring, audio, HLS, arranque en frío, cambio de red, 2 h continuas).
- Fase 1: corregir brechas 1, 2, 3 y 5 en un módulo de supervisión aislado, sin tocar el motor.
- Fase 2: script de provisionamiento ADB (permisos, batería, ajustes).
- Fase 3 (solo firmware propio): misma APK como priv-app + `privapp-permissions` XML.
- Proponer los parches upstream para no divergir.

## Estado (2026-11-17)
| Brecha | Estado |
|---|---|
| 1. `START_NOT_STICKY` | Resuelta (`a80f728`) |
| 2. Sin `NetworkCallback` | Resuelta (`b098567`) |
| 3. Sin reintento NSD | Resuelta (`7270103`) |
| 4. "Abrir al conectar" sin overlay | Notificación con acción si falta `SYSTEM_ALERT_WINDOW` y scripts `scripts/provision.*` |
| 5. Watchdog del motor nativo | Pendiente: requiere un cuelgue reproducido. Desde `c64f614` los avisos nativos llegan a logcat y desde `4dc546d` cada cambio de sesión queda registrado, lo que ayuda a detectar el cuelgue |
| 6. Dispositivos de confianza | Parcial: `TrustedClients` guarda las claves de los emisores que pasaron el PIN (no se vuelve a pedir) y Ajustes tiene "Olvidar dispositivos emparejados". Falta la revocación individual por dispositivo |

Otros cambios verificados: dependencias y toolchain a la última estable, sesión multimedia migrada a Media3, `DmapParser` con límite de profundidad (entrada de red), DACP con `ServiceInfoCallback` en API 34+, lint en 0 errores con pruebas JVM en CI. Todo pendiente de validación física en TV.

## Latencia y robustez (2026-11-17, después de 0.0.1)
| Cambio | Motivo verificado en código |
|---|---|
| Hilo `VideoDrain` para la salida del decodificador | La salida solo se drenaba al llegar el siguiente paquete: cada fotograma esperaba un intervalo de entrada |
| `eglSwapInterval(0)` en el blit GL | `eglSwapBuffers` bloqueaba hasta el vsync |
| Búfer directo reutilizable para mirroring (JNI) | Un `byte[]` nuevo por fotograma presionaba al recolector de ART |
| Carátulas a 720 px como máximo | Una carátula de 3000 px ocupaba ~36 MB |
| Guardas `NewByteArray` nulo, URL DACP IPv6, orden carga/escritura de logs, scroll de logs | Defectos puntuales |

Sin medir todavía en TV: latencia extremo a extremo, FPS y caídas (overlay de diagnóstico y `benchmark_log`). Los valores por defecto de audio (`low_latency`, búfer adaptativo) y `scheduled_output_buffer_release` no se cambian sin esa medición. Se mantiene la lista de decodificadores por modelo de `DecoderSelector`, heredada de moonlight-android para evitar decodificadores HEVC defectuosos.

## Auditoría en hardware real y correcciones en `develop` (2026-11-18, sobre `0.0.7` / `32a4afe`)

Dispositivo probado por ADB: `onn 4K Streaming Device` (`wayne`, SoC Realtek, Android 14 / API 34, HDMI `1920×1080 @ 60 Hz`, `density 320`, `android.software.leanback`).

Línea base medida en `0.0.7`:
- `dumpsys meminfo`: `TOTAL PSS 195.9 MB` (`RSS 262.9 MB`), `EGL mtrack / Graphics 97.9 MB`, `Dalvik Heap 41.2 MB`, `Native Heap 19.4 MB`.
- `dumpsys gfxinfo`: `21.60%` de frames con jank (`1677 / 7764`; p50 `16 ms`, p90 `31 ms`, p95 `38 ms`, p99 `57 ms`).

| Commit | Cambio | Evidencia en TV / código |
|---|---|---|
| `b72b131` | Limpiar carátula al cambiar de pista e ignorar frames DMAP vacíos (`AirPlayService`, `NowPlayingTv`) | En Spotify/YouTube Music la carátula de una canción anterior quedaba fija al pasar de tema porque `onMetadata` copiaba `_trackInfo.value.coverArt` sin limpiar |
| `466d81e` | Mantener pantalla completa en transiciones de pista/video y soportar audio en `receiverOnly` (`MainScreen`) | Al pasar de canción o video con `connections == 0` momentáneo la UI volvía a mostrar las pestañas de la herramienta durante ~1 s |
| `609917e` | Presets de habitación en Ajustes (`SettingsScreen`, `strings.xml`) | Renombrado rápido del receptor en TV con D-pad (`Living Room`, `Bedroom`, `Dining Room`, `Office`) |
| `430f217` | Calidad de remuestreo `Best` en Oboe (`audio_output.h`) | Sube SRC de `Medium` a `Best` sin bajar calidad de audio |
| `5bb18be` | Fallback de `DacpController` a `resolveService` en API 34 si `ServiceInfoCallback` no responde en 1.5 s | En `onn 4K` (API 34) llegaba `DACP: ...` pero `onServiceUpdated` nunca disparaba, dejando inactivos Siguiente/Anterior/Play-Pausa en música |
| `f781788` | Conectar `PlayerButton` directo a `IconButton.onClick` (`VideoControls`) | El truco anterior con `onClick = {}` y `PressInteraction` ignoraba la tecla `OK` del mando de TV |
| `964c519` | Modo TV limpio por defecto (`TvStandbyScreen`), menú de herramienta bajo demanda y fondo oscuro sin carátula (`MainScreen`, `NowPlayingTv`) | En TV las pestañas y el botón Detener se mostraban como pantalla principal en lugar de un receptor limpio |
| `608dd35` | Desacoplar la `Surface` de ExoPlayer de forma síncrona en `surfaceDestroyed` y validar `surface.isValid` (`AirPlayVideoPlayer`) | Logcat del `onn 4K` capturó `MediaCodec$CodecException: rendering to non-initialized(obsolete) surface` en `c2.realtek.video.avc.decoder` porque `clearSurface` usaba `mainHandler.post` |
| `910320e` | Reenviar superficies activas al enlazar el servicio (`MainViewModel`) | Si `VideoSurfaceView` entregaba su `Surface` con `service == null`, se descartaba y el video decodificaba en negro hasta reconectar |
| `f86b33a` | Apagar `_audioOnly` al iniciar video y priorizar video sobre carátula (`AirPlayService`, `MainScreen`) | Al abrir un video en Safari viniendo de música, la pantalla quedaba fija en la carátula anterior hasta desconectar AirPlay |
| `ba32661` | Overlay nativo de TV: transporte central (`-10s`, Play/Pausa, `+10s`, Anterior/Siguiente si hay DACP), degradados y ocultado de botones de móvil en TV (`VideoControls`, `MainScreen`) | En TV el overlay mostraba botones de teléfono (Rotar, Bloquear táctil, Copiar URL, Descargar, PiP) y solo un botón central de Play/Pausa |
| `8e8c006` | Omitir `Modifier.blur(48.dp)` a pantalla completa en TV en `ArtworkBackdrop` (`Motion`) | El bitmap ya se escala desde `24×24 px` con `FilterQuality.High`; evita la pasada de blur 1080p en GPU (línea base `97.9 MB` EGL, `21.60%` jank). **Revertido en `b35b5f1`**: baja la calidad de imagen y no se midió la ganancia |
| `546d4a6` | Reactivar mirroring al llegar `onVideoData` y limpiar `_videoSessionPending` al volver de video (`AirPlayService`, `MainViewModel`) | Al salir de un video hacia el escritorio del iPhone la UI quedaba retenida en estado pendiente en lugar de retomar el espejo |
| `712b6c6` | Permitir redirecciones HTTP entre protocolos (`http` ↔ `https`) en `AirPlayVideoPlayer` | Sitios web en Safari fuera de YouTube suelen redirigir entre esquemas y ExoPlayer bloquea esas redirecciones por defecto |

Pendiente de compilación y validación física tras instalar el APK de `develop`:
- Ejecutar `./gradlew assembleDebug testDebugUnitTest lintDebug` y `./scripts/native-regress.sh` en CI o entorno con Android SDK/NDK.
- Medir de nuevo `meminfo` y `gfxinfo` en el `onn 4K` para confirmar el efecto real de `8e8c006`.
- Parche nativo pendiente en entorno con submódulo `UxPlay` + NDK: `app/src/main/cpp/patches/UxPlay/0013` (`lib/httpd.c`, `timeout_limit = 60` corta sesiones HLS a los 60 s) y revisión de `hls_get_current_video: failed to identify current_playlist` observado en logcat.

### Revisión de esta tanda (2026-11-18)

Compilado y probado en Linux sobre `963f8f7`: `assembleDebug`, `lintDebug`, `testDebugUnitTest` y `scripts/native-regress.sh` pasan.

- `0f37315`: con el servidor activo, OK en la pantalla de espera abría el menú con el foco en Detener, y un segundo OK apagaba el servidor. El foco va ahora a la pestaña Resumen. El test `menuDoesNotLandOnStop` falló antes del cambio.
- `b35b5f1`: se revierte `8e8c006` (ver la tabla).
- `712b6c6`: `setAllowCrossProtocolRedirects(true)` también permite bajar de `https` a `http`. Media3 lo trae desactivado por ese motivo, y el commit no cita ninguna URL que fallara. Se mantiene hasta tener un caso real; si no aparece, se revierte.
- `b72b131`: al cambiar de pista, `onMetadata` borra la carátula. Si un emisor manda la carátula de la pista nueva antes que sus metadatos, esa pista se queda sin carátula. No hay registro que muestre ese orden; hay que comprobarlo en el TV.
- `546d4a6`: `onVideoData` vuelve a marcar el mirroring como activo. UxPlay llama a `mirror_video_running(false)` al final del mismo hilo que entrega los frames (`raop_rtp_mirror.c`), así que ningún frame tardío puede reactivarlo después de cerrar.
- `hls_get_current_video: failed to identify current_playlist` aparece justo antes de `AirPlay Video stopped`. `current_video` solo vuelve a `-1` con `playlistRemove` o al cerrar la conexión, así que el error parece una consecuencia del cierre que pide el emisor, no su causa. Falta el logcat completo para ver qué petición llega antes.
- `timeout_limit = 60` (`httpd.c`, igual en UxPlay upstream) cierra cualquier conexión sin tráfico durante 60 s, incluida la de eventos (`EVENT`). Las sesiones del registro duraron 5 s y 132 s, no 60 s, así que este límite no explica esos cortes. El logcat completo diría si aparece `httpd closing dead connection`.


### Registros del onn 4K sobre `fefe132` (2026-11-18, 20:52–20:59)

- `661220d` es correcto: el encabezado `DACP-ID` llega sin ceros a la izquierda, pero el servicio `_dacp._tcp` lleva los 16 dígitos (notas de la versión 3.3.7 de shairport-sync). En el registro, DACP resuelve en 5–600 ms en las dos sesiones.
- `8367f80` conserva la carátula si llegó como mucho 1 s antes de los metadatos. Pasa los 28 tests; falta verlo en la TV.
- Mirroring vertical (498×1080) y horizontal (1920×884): cada giro recrea el decodificador en unos 150 ms, sin errores. Una sola vez, al pasar de música a mirroring (20:56:50.39), `VideoPipeline` intentó dibujar en una `Surface` ya abandonada y registró `GL error 0x505`; se recuperó solo.
- En cada sesión de mirroring falla primero `c2.android.inproc.aac.decoder` y después arranca el AAC por defecto. Funciona, pero deja dos líneas `E` en cada sesión.
- Las líneas `avc: denied { getattr } dmabuf` son del sistema (SELinux del fabricante), no de la app.
- **Corte de YouTube a los 60 s**: los registros adjuntos (20:52–20:59) no cubren las 20:43–20:44. Además, hasta ahora la app solo mandaba a logcat los errores nativos (`LOGGER_ERR`). Por eso el aviso `httpd closing dead connection ... timeout_limit = 60` nunca podía aparecer: no prueba que el corte exista ni que no exista. La duración de 60,095 s apunta a ese límite, pero falta ver qué conexión se cierra. Si es la de `/play` (`video_play_conn`), `conn_destroy` llama a `video_reset(RESET_TYPE_HLS_CONN_CLOSED)`. Este commit sube el nivel nativo a `LOGGER_WARNING`. Siguiente paso: reproducir un vídeo de YouTube de más de 70 s con este código y buscar `httpd closing dead connection` y `video_reset` en el logcat.
- **Causa del corte a los 60 s, identificada en el código (`0063`)**: el receptor solo usa el canal EVENT (HTTP inverso) para las peticiones FCUP de las listas al empezar; no envía eventos de estado. Después el canal queda en silencio por diseño, y `timeout_limit = 60` lo cerraba. YouTube en modo FCUP se cortaba a los 60,095 s. El parche `0063` excluye las conexiones EVENT del límite; se eliminan al cerrar la sesión HLS (`raop_remove_hls_connections`) o cuando las cierra el emisor. Pendiente de validación física, incluido el caso Safari con el iPhone bloqueado + controles de la TV.


### Lógica de sesión y calidad (2026-11-19, `develop`)
- `4dc546d`: estado de sesión único (`Session`), que solo cambian los eventos del protocolo; cada cambio queda en logcat como `Session: A -> B (evento)`. Al parar el mirroring ya no aparece un "Reproduciendo ahora" vacío.
- `62da2ff`: en la TV la pantalla sigue a la sesión, sin banderas de pantalla completa ni la espera de 1,5 s.
- `0df13bc`: una conexión del emisor que se cierra solo termina el vídeo si estaba en pausa de verdad (`playWhenReady`), no mientras carga tras un salto.
- `0a44426`: el vídeo AirPlay pide a la pantalla su frecuencia (`CHANGE_FRAME_RATE_ALWAYS`) para quitar los tirones 3:2 de 24 fps a 60 Hz. Depende del ajuste del sistema "Ajustar frecuencia de contenido".
- Decodificador AAC en proceso: se intenta una sola vez por proceso.
