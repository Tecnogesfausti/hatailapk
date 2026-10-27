# Historial local de notificaciones

## Acceso y fuentes

Desde la cabecera, abrir «Historial 48 h» y pulsar «Activar acceso a notificaciones». Android concede un permiso general al servicio `HatailNotificationListener`, rotulado «HATailAPK · historial HA (48 h)». El código descarta las aplicaciones no permitidas antes de extraer texto o adjuntos: solo acepta el paquete propio de HATailAPK y los oficiales `io.homeassistant.companion.android` y `io.homeassistant.companion.android.minimal`.

Este servicio es independiente de `NotificationSensorManager` del upstream: activar el historial no activa el envío de notificaciones de otras aplicaciones como sensores a HA. No pulsa acciones ni cancela los avisos originales. Excluye los resúmenes de grupos, los servicios en primer plano y los avisos diagnósticos de LeakCanary. Mantiene las alertas persistentes de usuario que no sean servicios.

Al conectarse, importa únicamente notificaciones que sigan activas y pertenezcan a las últimas 48 horas. No recupera avisos descartados previamente, alertas no recibidas ni contenido que Android o el perfil del teléfono no permita entregar al lector. El historial complementa las notificaciones; no demuestra fiabilidad del transporte de alertas de Casa/Sol.

## Datos, imágenes y retención

`HatailHistoryDatabase` es una base Room separada de la de Companion para evitar colisiones con sus migraciones. Guarda fecha de publicación, aplicación, título, mensaje y el subtítulo de origen si existe. No deduce Casa o Sol a partir de la selección actual. El identificador SHA-256 combina aplicación, clave Android y fecha de publicación para evitar duplicar una notificación al reconectar el servicio. Un nuevo aviso que sustituya un tag puede conservarse como otra publicación.

El texto se persiste antes de procesar imágenes. Se aceptan las imágenes estándar `Bitmap` e `Icon` de `BigPictureStyle`; se guarda una copia JPEG optimizada, con lado máximo de 1280 píxeles y límite de 2 MiB. La copia es privada, está en `noBackupFilesDir` y no usa un proveedor de archivos exportado. No se descargan URLs ni se leen tokens de Companion. Si Android no entrega la imagen o falla la copia, permanece el texto con una indicación de imagen no disponible. No se archivan vídeos; una imagen animada solo conserva la representación estática que Android facilite.

La consulta visible excluye registros anteriores a 48 horas y se renueva cada minuto mientras el panel está abierto. Se eliminan filas y adjuntos caducados al iniciar la aplicación, recibir un aviso y ejecutar el trabajo periódico de limpieza, programado cada hora. Android puede retrasar ese trabajo; la limpieza pendiente se completa cuando la aplicación vuelve a ejecutarse. El registro diagnóstico nativo de Companion es independiente y no se modifica con esta función.

## Verificación y mantenimiento

«Enviar prueba» publica dos avisos locales, uno de texto y otro con una imagen sintética. No proceden de ninguna vivienda ni comprueban el transporte desde HA. Necesita que Android permita las notificaciones de HATailAPK.

Las pruebas cubren paquetes permitidos, avisos persistentes frente a servicios, imágenes, fallos de almacenamiento, reconexiones repetidas, separación entre aplicaciones, límite temporal en Room y lectura de adjuntos caducados. Las comprobaciones del teléfono deben verificar la concesión del permiso, las dos pruebas locales, descartar un aviso sin perder su copia, ampliar una imagen y cerrar el panel manteniendo el hogar/VPN.

La integración del manifiesto y el procesador Room se regeneran con `tools/prepare-integrated.py`; no se modifican los originales en `upstream/`. Se ejecuta la suite `io.homeassistant.companion.android.hatail.*` con el flujo de mantenimiento habitual.
