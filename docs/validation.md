# Plan de validación

## Verificación inicial realizada

- 18 pruebas JUnit del núcleo: selección de ambas viviendas, permisos, orden de operaciones, identidad, servidor HA, cambio de perfil, resultados tardíos, desconexión, errores, reintento y pérdida de VPN.
- Ejecución local mediante Kotlin 2.2.20 y JUnit 4.13 instalados; la configuración Gradle declara Kotlin 2.1.20 y JUnit 4.13.2. Falta confirmar también esa combinación en CI.
- Gradle local bloqueado por `java.net.SocketException: Operation not permitted` al crear el socket de su daemon. No se considera compilación Android superada por este intento.
- Git/SSH local sin resolución DNS de `github.com`; no se ha validado la deploy key desde esta terminal. La conexión GitHub permite consultar y publicar archivos por API.

## Criterios para la primera APK

| Escenario | Resultado requerido |
| --- | --- |
| Primer arranque | Elegir Casa/Sol y completar permisos/autenticación |
| Casa → Sol y Sol → Casa | Cerrar panel anterior, verificar perfil y abrir solo el HA elegido |
| Cuenta equivocada o caducada | Error y recuperación; ningún panel incorrecto |
| Servidor HA inaccesible | Error con reintento; nunca «conectado» solo por tener VPN |
| Wi-Fi → móvil y pérdida de red | Invalidar estado y recuperar conexión de forma verificable |
| VPN revocada por otra aplicación | Cerrar panel e indicar desconexión |
| Reinicio o proceso eliminado | Estado real reconstruido; selector al abrir |
| Pantalla apagada y ahorro de batería | Medir recepción, retraso y consumo |
| Alertas de ambas viviendas | Verificar origen y entrega con cada perfil activo |

Probar notificaciones con payloads reales y marcas temporales, incluyendo acciones y recuperación tras desconexión. No afirmar entrega garantizada basándose en pruebas del núcleo ni en el simple acceso al panel.
