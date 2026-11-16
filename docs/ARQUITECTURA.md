Arquitectura objetivo: AirPlay global para Android TV

El alcance queda definido así:

* Plataformas: aplicación instalable en Android TV y, cuando sea posible, integración nativa en firmware.
* Recepción: AirPlay de audio/vídeo y duplicación de pantalla (screen mirroring).
* Dispositivos: Android TV/Google TV existentes y dispositivos con firmware propio o modificable.

La solución debe construirse como una plataforma receptora independiente del navegador VireoLumaTV. Vireo no será el propietario del servicio ni tendrá que estar abierto para que AirPlay funcione.

Hay una distinción importante: conseguir que un televisor Android TV normal reciba AirPlay desde cualquier pantalla es viable mediante un servicio receptor independiente, sujeto a las restricciones del fabricante y de Android. Conseguir que sea una función nativa, comparable a Chromecast integrado, requiere además integración con el firmware. No existe una instalación universal que pueda convertir cualquier Android TV comercial en un componente privilegiado del sistema.

1. Arquitectura por capas

Dispositivos emisores

iPhone · iPad · Mac · aplicaciones compatibles con AirPlay

Wi-Fi / Ethernet · mDNS · conexiones AirPlay

1. Capa de descubrimiento y conexión

Bonjour/mDNS · publicación del receptor · negociación de sesión · autenticación · gestión de conexiones

2. Motor receptor AirPlay

Protocolos · control remoto · recepción de audio/vídeo · mirroring · cifrado · gestión de sesiones

3A. Reproducción multimedia

Decodificación hardware · audio · vídeo · sincronización A/V · salida HDMI

3B. Duplicación de pantalla

Vídeo en tiempo real · audio sincronizado · superficie de pantalla completa

4. Integración con Android TV

Servicio global · actividad de reproducción · inicio y recuperación · controles del sistema · permisos y ajustes

Ruta A: Android TV normal

APK independiente, permisos ordinarios y compatibilidad con las restricciones del fabricante.

Ruta B: Firmware

Aplicación privilegiada, integración con SystemUI y ciclo de vida administrado por el sistema.

La separación entre el motor receptor y la integración Android es deliberada. Permite mantener un único núcleo de recepción y adaptar la integración al nivel de privilegios disponible en cada televisor.

2. Componentes técnicos que hay que desarrollar

Componente	Responsabilidad	Requisito
AirPlay Core	Implementar los protocolos de recepción y control	Núcleo independiente de Android
Discovery	Anunciar el televisor por Bonjour/mDNS	Descubrimiento fiable en LAN
Session Manager	Emparejamiento, PIN, conexiones simultáneas y desconexiones	Estado persistente y seguro
Media Receiver	Recibir audio y vídeo AirPlay	Negociación de formatos y códecs
Mirroring Receiver	Recibir la pantalla del emisor	Baja latencia y sincronización A/V
Playback Engine	Decodificar y presentar contenido	Aceleración por hardware
TV Integration	Presentar AirPlay sobre la interfaz del televisor	Actividad global y control remoto
Service Supervisor	Mantener y recuperar el servicio	Adaptación a cada versión de Android
Settings & Diagnostics	Nombre del televisor, PIN, estado, sesiones y errores	Interfaz propia de Android TV

A. Motor receptor AirPlay

No conviene implementar todos los protocolos desde cero si existe una base técnica reutilizable.

La primera base que evaluaría es UxPlay, incluyendo su adaptación Android android-airplay-server que ya examinamos. Su arquitectura utiliza código nativo y JNI para ejecutar el receptor, mientras que Android administra el servicio y la interfaz.

Pero no la adoptaría directamente como producto final sin una auditoría de:

* Compatibilidad real con las modalidades de AirPlay que necesitamos.
* Estado de mantenimiento y versiones de las dependencias.
* Seguridad de los sockets, sesiones y negociación criptográfica.
* Compatibilidad de codecs y decodificación hardware.
* Fugas de memoria, estabilidad prolongada y recuperación.
* Licencia GPL-3.0 y obligaciones de distribución.

Decisión propuesta: encapsular el motor detrás de una interfaz propia. Así podremos sustituir UxPlay o actualizarlo sin rehacer el servicio Android.

B. Descubrimiento y conectividad

El televisor debe anunciarse automáticamente en la red local y aparecer como destino AirPlay en los dispositivos Apple.

Hay que implementar:

* Registro y renovación de servicios mDNS.
* Nombre configurable del televisor.
* Gestión de cambios de IP, Wi-Fi y Ethernet.
* Recuperación de anuncios tras pérdida de conectividad.
* Control correcto de multicast.
* Validación de conexiones entrantes y límites de recursos.
* Compatibilidad con redes que aíslan clientes Wi-Fi.

Un detalle crítico: Android 17, API 37, exige gestionar ACCESS_LOCAL_NETWORK para las aplicaciones que necesitan acceso amplio a la red local. Esto afecta directamente al descubrimiento mDNS y a las conexiones entrantes. No basta con declarar INTERNET. developer.android.com +1

C. Recepción multimedia y duplicación

Son dos rutas funcionales diferentes y deben probarse por separado.

AirPlay de audio/vídeo

* Recepción de contenido enviado desde aplicaciones compatibles.
* Decodificación por hardware cuando el dispositivo la admita.
* Sincronización de audio y vídeo.
* Control de reproducción, pausa, búsqueda y volumen cuando el protocolo lo permita.
* Selección de códecs según las capacidades reales del televisor.

AirPlay Mirroring

* Recepción continua de la pantalla del iPhone, iPad o Mac.
* Audio sincronizado.
* Presentación a pantalla completa.
* Orientación y relación de aspecto correctas.
* Latencia baja y estable.
* Respuesta adecuada al bloqueo, desconexión o cambio de red del emisor.

No debe confundirse la recepción de mirroring con capturar la pantalla del propio Android TV. Son operaciones distintas. MediaProjection es una API para capturar contenido del dispositivo Android y exige autorización del usuario; no es el motor que necesitamos para recibir la pantalla de un iPhone. developer.android.google.cn +1

Tampoco se debe prometer reproducción de todo contenido protegido: DRM, aplicaciones emisoras y restricciones de los protocolos pueden impedir determinados tipos de reproducción o duplicación.

3. Las dos rutas de integración

Ruta A — Android TV comercial

Distribución amplia

Se instala como una aplicación independiente, pero funciona como receptor de ámbito general.

* Servicio de recepción separado de cualquier aplicación de usuario.
* Interfaz propia para configurar el receptor.
* Actividad de reproducción a pantalla completa.
* Inicio y recuperación del servicio según las políticas del fabricante.
* Notificación y controles cuando el sistema lo requiera.
* Permisos de red solicitados correctamente.
* Integración con el botón de inicio y el mando en los límites permitidos por Android.

Ruta B — Firmware Android TV

Integración nativa

Se incorpora como componente del sistema operativo.

* Instalación en la partición adecuada como aplicación privilegiada.
* Firma y permisos conforme a la política del fabricante.
* Allowlist de permisos privilegiados.
* Integración con SystemUI y ajustes del sistema.
* Gestión del servicio desde el ciclo de vida del sistema.
* Inicio automático administrado por el firmware.
* Controles globales y gestión de sesiones desde la interfaz del televisor.
* Integración con las capacidades multimedia y de red del fabricante.

La ruta B no consiste simplemente en copiar un APK a /system. Android exige que las aplicaciones privilegiadas estén ubicadas en una partición del sistema y que sus permisos privilegiados estén correctamente autorizados mediante los archivos de allowlist correspondientes. source.android.com

Limitación importante de la ruta A: Android moderno restringe el inicio de determinados servicios en segundo plano. Por ejemplo, Android 15 impide iniciar un servicio foreground de tipo mediaPlayback desde BOOT_COMPLETED cuando se cumplen las condiciones de target indicadas por Google. Por tanto, no podemos diseñar la disponibilidad universal dependiendo exclusivamente de un BootReceiver. developer.android.com +1

La solución debe tener dos políticas de funcionamiento:

* En APK normal: inicio permitido, recuperación mediante mecanismos compatibles y comportamiento explícito cuando Android o el fabricante detengan el servicio.
* En firmware: servicio administrado por el sistema, con inicio y supervisión nativos.

4. Seguridad y administración global

El receptor estará expuesto a la red local. No debe tratarse como un simple reproductor multimedia.

Diseñaría estas protecciones desde el primer prototipo:

* Emparejamiento: PIN opcional u obligatorio según la configuración, con posibilidad de cambiarlo y revocar dispositivos.
* Autenticación: validar cada sesión y no confiar en que un dispositivo esté en la misma Wi-Fi.
* Aislamiento: separar las sesiones y evitar que una conexión pueda controlar otra.
* Red: limitar conexiones, tamaños de paquetes, tiempos de espera y recursos consumidos.
* Superficie de ataque: no exportar servicios Android ni interfaces de control innecesarias.
* Privacidad: no guardar audio, vídeo ni capturas; conservar únicamente los datos de configuración y los registros técnicos necesarios.
* Recuperación: cerrar sesiones defectuosas, liberar sockets y recursos nativos y recuperar el servicio sin reiniciar el televisor.

La administración del receptor debe incluir un panel global con:

* AirPlay activado/desactivado.
* Nombre visible del televisor.
* PIN y política de emparejamiento.
* Dispositivos conectados.
* Sesión actual y tipo de contenido.
* Desconexión de dispositivos.
* Inicio automático y disponibilidad del servicio.
* Estado de red, decodificador y errores.

El panel es una interfaz de administración, no el receptor en sí. El servicio debe seguir siendo independiente.

5. Organización del proyecto

No mezclaría el código del receptor con VireoLumaTV. Propongo un repositorio propio, por ejemplo AndroidTV-AirPlay-Receiver, con esta estructura inicial:

android-tv-airplay/
├── receiver-core/
│   ├── protocol/
│   ├── pairing/
│   ├── discovery/
│   ├── sessions/
│   └── native-engine/
├── receiver-android/
│   ├── service/
│   ├── networking/
│   ├── playback/
│   ├── audio/
│   ├── video/
│   └── lifecycle/
├── tv-interface/
│   ├── settings/
│   ├── connections/
│   ├── pairing/
│   └── diagnostics/
├── platform-integration/
│   ├── privileged-app/
│   ├── permissions/
│   ├── systemui/
│   └── firmware/
├── device-compat/
│   ├── codecs/
│   ├── capabilities/
│   └── profiles/
└── tests/
    ├── protocol/
    ├── networking/
    ├── security/
    ├── playback/
    ├── mirroring/
    └── lifecycle/

La separación permite que el mismo núcleo funcione en un Android TV comercial y en un firmware personalizado. La integración privilegiada será un módulo adicional, no una bifurcación del motor.

6. Plan de trabajo técnico

El orden importa: primero hay que demostrar que el receptor es técnicamente sólido; después se construye la integración global.

* Fase 1 — Auditoría del motor y viabilidad
    * Auditar el código de UxPlay y android-airplay-server.
    * Verificar licencia, dependencias, protocolos y estado de mantenimiento.
    * Identificar qué funciones AirPlay están realmente implementadas.
    * Separar limitaciones del protocolo, del motor y del hardware.
    * Definir una interfaz estable para el motor nativo.
    Entregable: informe técnico y decisión fundamentada sobre reutilización o sustitución.
* Fase 2 — Receptor mínimo funcional
    * Servicio Android independiente.
    * Descubrimiento mDNS.
    * Emparejamiento y gestión de sesiones.
    * Recepción de audio/vídeo.
    * Recepción de mirroring.
    * Reproducción a pantalla completa.
    Entregable: APK receptor funcional sin depender de Vireo.
* Fase 3 — Integración global y ciclo de vida
    * Funcionamiento desde la pantalla de inicio y mientras se utiliza otra aplicación.
    * Gestión de foco y reproducción.
    * Recuperación de red y del motor.
    * Inicio automático compatible con cada versión de Android.
    * Controles del mando y panel de administración.
    Entregable: receptor de ámbito general para Android TV comercial, dentro de las capacidades permitidas por cada fabricante.
* Fase 4 — Optimización multimedia y seguridad
    * Decodificación hardware.
    * Control de memoria y buffers.
    * Medición de latencia de mirroring.
    * Pruebas de sesiones prolongadas y reconexiones.
    * Auditoría de autenticación, sockets, JNI y gestión de recursos.
    * Verificación de consumo en reposo y durante reproducción.
    Entregable: versión candidata con métricas y fallos documentados.
* Fase 5 — Integración de firmware
    * Construir la variante privilegiada.
    * Añadir permisos y allowlists.
    * Integrar controles en SystemUI y ajustes.
    * Gestionar arranque, supervisión y recuperación.
    * Adaptar las diferencias de cada fabricante.
    Entregable: integración nativa para las imágenes de firmware compatibles.
* Fase 6 — Matriz de compatibilidad y publicación
    * Matriz de Android/API y fabricantes.
    * Pruebas de emisores iOS/iPadOS/macOS.
    * Pruebas independientes de audio/vídeo y mirroring.
    * Pruebas de seguridad, estabilidad y rendimiento.
    * Documentación de instalación y de integración OEM.
    Entregable: versiones diferenciadas para APK comercial y firmware.

7. Validación: qué significa que esté terminado

No daría el proyecto por finalizado porque el iPhone detecte el televisor una vez. Los criterios de aceptación deben ser verificables.

Área	Criterio de aceptación
Descubrimiento	Aparece y desaparece correctamente al activar/desactivar el receptor
Conexión	Emparejamiento, conexión y desconexión repetibles
Audio/vídeo	Reproducción estable con los formatos realmente soportados
Mirroring	Imagen y audio sincronizados, sin bloqueos persistentes
Ámbito global	Recibe conexiones desde Home y mientras otra app del TV está abierta
Reconexión	Se recupera de cambios de red y desconexiones
Seguridad	No permite sesiones no autorizadas ni controles cruzados
Rendimiento	Memoria, CPU, temperatura y latencia medidas bajo carga
Inicio	Comportamiento de arranque documentado y validado para cada modalidad
Compatibilidad	Resultados separados por versión de Android y fabricante

No impondría pruebas físicas como única puerta de validación: se pueden automatizar protocolos, pruebas unitarias, integración, análisis de memoria y pruebas en emuladores. Pero la decodificación hardware, la latencia real y el comportamiento de firmware necesitan validación en hardware representativo antes de declarar compatibilidad con un modelo.

8. Decisiones que deben quedar fijadas

Hay cuatro cuestiones que condicionan el diseño y que no conviene dejar para el final:

1. Compatibilidad AirPlay. No se debe presentar una implementación de código abierto como equivalente certificado al receptor oficial de Apple. El objetivo inicial será compatibilidad práctica con los emisores y modalidades que podamos verificar. Los contenidos protegidos por DRM tendrán que evaluarse por separado.

2. Universalidad. La variante APK podrá cubrir una gama amplia de Android TV, pero no garantizará inicio automático permanente ni integración con SystemUI en todos los fabricantes. La variante firmware permitirá una integración más profunda, pero necesitará adaptación por plataforma y fabricante.

3. Licencia. Antes de reutilizar código GPL-3.0 dentro de un producto distribuido, hay que revisar las obligaciones de distribución y la separación entre componentes. Mantendría el motor receptor aislado hasta resolver esta decisión.

4. Hardware. La negociación de codecs debe ser dinámica. No se puede asumir que todos los Android TV soporten H.264, HEVC, AAC, ALAC y las mismas capacidades de decodificación, ni que tengan el mismo rendimiento.

9. Mi recomendación técnica

Construiría un único proyecto con dos productos:

* AirPlay Receiver APK: para Android TV y Google TV comerciales.
* AirPlay System Integration: para firmware Android TV propio o adaptado por fabricantes.

Ambos compartirían el motor receptor, los protocolos, la gestión de sesiones, la seguridad y el sistema de diagnóstico.

No desarrollaría dos receptores independientes. Duplicar el motor multiplicaría los errores y el mantenimiento.

Tampoco modificaría VireoLumaTV para alojar el servicio: el navegador debe poder instalarse, actualizarse o desinstalarse sin afectar al receptor AirPlay.

10. Siguiente trabajo: auditoría técnica antes de implementar

La primera tarea concreta debe ser auditar a fondo el motor existente y determinar qué podemos reutilizar. Estas son las referencias oficiales que deben regir la integración Android:

* AOSP — Privileged permission allowlist⁠￼.
* Android — Local network permission⁠￼.
* Android — NsdManager y multicast⁠￼.
* Android 15 — Restricciones de servicios en segundo plano⁠￼.
* Android — Foreground service types⁠￼.

Mi orden de ejecución sería: auditar UxPlay y el receptor Android existente, verificar la licencia, medir qué modalidades AirPlay funcionan realmente y después construir el núcleo independiente. Solo cuando ese núcleo esté demostrado avanzaría con la integración global y la variante de firmware.

Ese orden evita invertir trabajo en una interfaz de sistema alrededor de un motor que todavía no haya demostrado compatibilidad, estabilidad y seguridad.