# Seguimiento Traccar

El icono de ubicación de la cabecera abre la configuración. Permite editar URL privada de Traccar, identificador, intervalo solicitado, distancia mínima y precisión máxima. Guardar antes de activar; detener antes de cambiar de destino o identificador. La sugerencia de puerto 5055 debe comprobarse contra el complemento instalado.

El módulo nativo implementa el protocolo HTTP OsmAnd de Traccar: https://www.traccar.org/osmand/. No incorpora otro fork ni Firebase. Usa LocationManager y un servicio foreground independiente del panel Casa/Sol. Requiere ubicación precisa, permiso en segundo plano y notificaciones; inicialmente está desactivado.

Comprueba identidad y tailnet Tailscale y liga sockets y DNS a la red VPN. No sigue redirecciones. Configuración cifrada, coordenadas privadas en una base separada excluida de copias; no se registran posiciones ni identificadores en logs. Conserva una única posición pendiente, sustituida por la más reciente y descartada después de dos minutos. Detener borra la posición pendiente. No reproduce rutas antiguas tras una desconexión.

Android puede restringir el servicio; tras reiniciar o forzar cierre se debe abrir HATailAPK para reanudar. El intervalo solicitado no garantiza una frecuencia exacta. Revisar consumo y permisos de batería. Un envío HTTP aceptado no demuestra que las automatizaciones HA funcionen.

Antes de sustituir definitivamente el cliente anterior, comprobar en Traccar el dispositivo y la hora de posición, cambiar Casa/Sol, apagar pantalla, perder y recuperar VPN y reiniciar el móvil. Evitar dos clientes activos con el mismo identificador.
