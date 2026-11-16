# Auditoría de la base (jqssun/android-airplay-server)

Fecha: 2026-11-16 · Commit base: `a46cca0` (v0.0.36)

## Estado verificado
- Mantenimiento activo (último commit upstream: 2026-10-19).
- Motor: UxPlay 1.73.7 como submódulo + 9 parches en `app/src/main/cpp/patches/UxPlay/`.
- Dependencias nativas: OpenSSL, libplist, FFmpeg (solo ALAC). CMake + NDK 27.
- compileSdk/targetSdk 37, minSdk 23. ABIs: arm64-v8a, armeabi-v7a, x86_64.
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
