# Repository Guidelines

## Objetivo fundamental

Crear **HATailAPK**, una única APK Android que combine un fork de [Home Assistant Companion](https://github.com/home-assistant/android) con [Tailscale](https://github.com/tailscale/tailscale). Repositorio principal: [Tecnogesfausti/hatailapk](https://github.com/Tecnogesfausti/hatailapk.git).

La aplicación debe acceder a dos viviendas, **Casa** y **Sol**, cada una con su Home Assistant y su tailnet correspondiente. El objetivo es evitar depender de Cloudflare para el acceso, ante los bloqueos experimentados durante partidos de fútbol, y recuperar la recepción fiable de notificaciones importantes.

## Flujo de conexión obligatorio

- Al reabrir, restaurar el hogar seleccionado y verificar su VPN antes de mostrar HA.
- Mostrar Casa/Sol solo sin selección. Atrás sale del hogar, olvida la selección y desconecta Tailscale; esperar el cierre antes de permitir elegir de nuevo.
- Asociar cada perfil con su servidor HA y su identidad/tailnet Tailscale.
- Tras elegir, activar automáticamente la conexión correspondiente, una vez concedidos los permisos y completada la autenticación inicial.
- Comprobar la identidad/tailnet activa, la conectividad del túnel y la disponibilidad del HA elegido antes de mostrarlo como conectado.
- Mostrar progreso, errores y opción de reintento; impedir conexiones al servidor equivocado durante un cambio.

Diseñar inicialmente para una sola conexión Tailscale activa, con cambio controlado entre perfiles. Validar las restricciones de Android y del cliente antes de cerrar la arquitectura; no asumir dos clientes VPN simultáneos.

## Notificaciones y segundo plano

El acceso al panel no demuestra que funcionen las notificaciones. Definir y probar su transporte con pantalla apagada, restricciones de batería, reinicios y cambios de red. Resolver explícitamente cómo recibir alertas de ambas viviendas cuando solo una tailnet esté activa; no prometerlo sin una solución verificada.

## Estructura y actualizaciones upstream

`connection-core/` contiene estados y validación de direcciones; `selector-ui/`, la biblioteca inicial. `integration/` implementa la primera APK Casa con Companion y Tailscale. Sol sigue pendiente. `upstream/` referencia los originales fijados en `upstream.lock.json`; no modificarlos. Generar worktrees separados con `tools/prepare-integrated.py`.

Aislar cambios propios y documentar la sincronización. Revisar conflictos y ejecutar verificaciones antes de actualizar.

## Desarrollo, estilo y pruebas

Ejecutar `./gradlew :connection-core:test` y `./gradlew :selector-ui:assembleDebug :selector-ui:lintDebug`. Verificar fuentes con `python3 tools/upstreams.py verify`. Consultar `README.md` y `docs/` para requisitos y mantenimiento. Seguir las convenciones de cada upstream y el estilo oficial Kotlin.

Compilar la APK con `ANDROID_HOME=/ruta/sdk ./tools/build-integrated.sh`. El prototipo exige un alta Companion completa antes de verificar HA y restringe HTTP privado a la VPN; no interrumpir el registro OAuth al aparecer una fila temporal.

Probar Casa/Sol, autenticación caducada, cambio de perfil, HA inaccesible, reconexión y notificaciones. Añadir regresiones para errores corregidos.

## Contribuciones y seguridad

Usar commits imperativos y PR con objetivo, incidencias vinculadas, verificaciones y capturas para cambios visuales.

Nunca guardar tokens HA, claves Tailscale ni credenciales en Git. Proporcionar ejemplos sin secretos y proteger las credenciales locales con mecanismos seguros de Android.
