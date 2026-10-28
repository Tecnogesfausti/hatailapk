# Cambio de hogar y notificaciones

## Navegación

Los selectores Casa (casa) y Sol (sol) permanecen en la cabecera. La selección indica qué panel se está abriendo; el contenido aparece únicamente tras validar la identidad de Tailscale y el servidor HA de destino. Los botones se desactivan durante la comprobación. Ante un fallo se muestra reintento y se puede elegir el otro hogar.

Se restaura la última selección y se inicia Casa cuando todavía no existe una. No se presenta la pantalla inicial de elección. Atrás usa la navegación del panel y, en la raíz, lleva la tarea a segundo plano. No borra la selección, apaga la VPN ni cierra las conexiones WebSocket. Este comportamiento sustituye al flujo anterior de salida explícita descrito en AGENTS.md.

Los enlaces de notificaciones eligen el hogar vinculado al identificador de servidor y conservan el destino del enlace. Se valida otra vez su conexión antes de mostrarlo. Identificadores desconocidos no crean asociaciones nuevas.

## Dos servidores, un túnel

Esta instalación usa **una tailnet compartida** que permite alcanzar los dos HA. Los selectores cambian el panel Companion, no la identidad de Tailscale. No se ejecutan dos clientes VPN ni dos aplicaciones Companion independientes. Ambos registros y sus credenciales permanecen separados; se mantienen las preferencias existentes de sensores.

`HatailBackgroundNotifications` habilita `WebsocketSetting.ALWAYS` para los registros completos que coincidan con sus vinculaciones y direcciones privadas. Conserva las demás preferencias. El `WebsocketManager` original mantiene un trabajo de escucha por servidor, independientemente del panel activo, y se programa después de verificar el túnel, incluso si el panel elegido no responde. La configuración ALWAYS se restablece al validar la sesión.

Las etiquetas y grupos de notificaciones recibidas se separan por servidor (`hatail:<id>:<tag>`), incluidas las órdenes de borrado. Así, una automatización con el mismo tag en ambas viviendas no sustituye el aviso de la otra. Los avisos anteriores a esta actualización conservan sus etiquetas antiguas: una orden nueva no los borrará automáticamente. El historial local sigue siendo común y retiene las últimas 48 horas.

Esta solución necesita que ambos HA sean accesibles por el mismo túnel. Si se separan en tailnets incompatibles, no garantiza recepción simultánea. Android puede restringir la ejecución en segundo plano; configurar el transporte no demuestra la entrega real.

## Comprobación antes del uso habitual

Tras instalar con `python3 tools/maintain.py install --serial SERIAL`, sin desinstalar ni borrar datos:

1. Alternar Casa → Sol → Casa desde la cabecera y reabrir la aplicación; confirmar el panel y la selección.
2. Emitir avisos desde ambos HA mientras se muestra solo uno; repetir con idéntico tag y grupo. Borrar uno mediante su automatización y comprobar que el otro permanece.
3. Tocar un aviso del hogar oculto y verificar el hogar y el destino abiertos. Probar también acciones y respuestas.
4. Repetir con pantalla apagada, después de Atrás, cambio Wi-Fi/datos y reinicio. Comprobar batería, permiso de notificaciones y ejecución en segundo plano.
5. Hacer inaccesible un HA: el otro debe seguir recibiendo avisos. Confirmar textos e imágenes en el historial.

Estas pruebas físicas quedan pendientes: esta entrega prepara el APK y no lo instala.
