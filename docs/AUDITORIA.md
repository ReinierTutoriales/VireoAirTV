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
| 5. Watchdog del motor nativo | Pendiente: requiere un cuelgue reproducido |
| 6. Dispositivos de confianza | Pendiente: decisión de producto |

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
| `8e8c006` | Omitir `Modifier.blur(48.dp)` a pantalla completa en TV en `ArtworkBackdrop` (`Motion`) | El bitmap ya se escala desde `24×24 px` con `FilterQuality.High`; evita la pasada de blur 1080p en GPU (línea base `97.9 MB` EGL, `21.60%` jank) |
| `546d4a6` | Reactivar mirroring al llegar `onVideoData` y limpiar `_videoSessionPending` al volver de video (`AirPlayService`, `MainViewModel`) | Al salir de un video hacia el escritorio del iPhone la UI quedaba retenida en estado pendiente en lugar de retomar el espejo |

Pendiente de compilación y validación física tras instalar el APK de `develop`:
- Ejecutar `./gradlew assembleDebug testDebugUnitTest lintDebug` y `./scripts/native-regress.sh` en CI o entorno con Android SDK/NDK.
- Medir de nuevo `meminfo` y `gfxinfo` en el `onn 4K` para confirmar el efecto real de `8e8c006`.
- Parche nativo pendiente en entorno con submódulo `UxPlay` + NDK: `app/src/main/cpp/patches/UxPlay/0013` (`lib/httpd.c`, `timeout_limit = 60` corta sesiones HLS a los 60 s) y revisión de `hls_get_current_video: failed to identify current_playlist` observado en logcat.


