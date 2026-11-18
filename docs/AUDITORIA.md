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

