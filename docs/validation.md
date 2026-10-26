# Plan de validación

## Selector Casa/Sol, 26 de octubre de 2026

- Desde el Samsung SM-A566B, una sola conexión Tailscale devolvió HTTP 200 y la página de Home Assistant de ambos servidores privados; no se necesitaron dos VPN simultáneas.
- La APK actualizada se instaló sobre el mismo paquete sin borrar los datos. El panel de Casa siguió abriendo por su URL privada.
- Atrás desde Casa mostró ambos botones y detuvo el servicio VPN. Elegir Sol inició la misma identidad Tailscale y abrió el alta de su propio Home Assistant por URL privada. El usuario completó el inicio de sesión en el móvil y confirmó que veía su panel.
- Después del alta, Sol volvió a abrirse sin repetir el inicio de sesión. Se comprobó Sol → selector → Casa → selector → Sol: Atrás detuvo el servicio VPN antes del selector y cada selección volvió a arrancarlo; los WebView activos usaron las direcciones privadas correspondientes.
- Cinco regresiones JUnit del adaptador pasaron: OAuth incompleto, HTTP privado, vinculación fija de Casa, prohibición de elegir Sol sin URL y selección del servidor Sol entre dos registros. Los sensores de Companion usan la clave compuesta `id` + `server_id`; falta medir las actualizaciones y alertas reales de ambos servidores.
- APK instalada: `dist/hatailapk-casa-debug.apk` (nombre heredado); SHA-256 `ba4e7f6c05fe0af9e7c83ff2926086d8c138e418db5403b2593a502459f1e075`.

Pendiente: reapertura tras matar el proceso, reinicio, notificaciones y efectos de batería. El panel visible no demuestra por sí solo que las alertas lleguen.

## APK Casa instalada el 25 de octubre de 2026

- APK ARM64 compilada sobre los tres commits fijados, con Companion y el motor Go integrados; firma verificada e instalación ADB correcta en Samsung SM-A566B (Android API 36).
- 28 pruebas JUnit del núcleo y 3 regresiones del adaptador Companion superadas. Estas últimas comprueban el alta OAuth incompleta, la configuración de HTTP privado mediante VPN y el rechazo de una reasociación silenciosa de servidor.
- El servidor privado respondió HTTP 200 desde el teléfono. Tras completar el usuario el inicio de sesión, se verificó que el WebView cargaba el panel `/lovelace/general` mediante la IP Tailscale y el puerto 8123, sin URL pública de Cloudflare.
- Inicio/segundo plano y reapertura: el servicio VPN sigue activo y el panel continúa en Casa.
- Atrás desde el frontend: aparece el selector y desaparece el servicio VPN integrado. Elegir Casa de nuevo arranca el servicio y recupera el panel privado sin repetir la autenticación. Se dejó el teléfono conectado a Casa.
- APK instalada: `dist/hatailapk-casa-debug.apk`; SHA-256 `ebe45611a117e0811c0d3a4ee9dc7daa8d7a998c17f1052c3fe5b0290a7cb94a`.

El APK Casa usa un adaptador experimental propio. Sol, las notificaciones importantes, el reinicio completo del teléfono y la entrega desde el hogar inactivo siguen pendientes de validación.

## Verificación realizada

- 24 pruebas JUnit del núcleo: selección de ambas viviendas, permisos, orden de operaciones, identidad, servidor HA, cambio de perfil, resultados tardíos, desconexión, errores, reintento, pérdida de VPN, restauración del hogar y salida con Atrás.
- Ejecución local mediante Kotlin 2.2.20 y JUnit 4.13 instalados. La misma suite pasó también en CI con las versiones declaradas: Kotlin 2.1.20 y JUnit 4.13.2.
- [GitHub Actions, ejecución 40220527618](https://github.com/Tecnogesfausti/hatailapk/actions/runs/40220527618): 24 pruebas superadas, biblioteca Android compilada, `lintDebug` superado y los tres submódulos verificados. El artefacto `selector-library-and-test-results` contiene el AAR y el informe de pruebas; no es una APK.
- La vista también compiló localmente contra Android SDK 35; se verificaron las mismas 23 claves de texto en español e inglés.
- Gradle local bloqueado por `java.net.SocketException: Operation not permitted` al crear el socket de su daemon. No se considera compilación Android superada por este intento.
- En la sesión restringida inicial, Git/SSH no tenía resolución DNS. En la sesión con acceso al equipo se verificó el acceso SSH al repositorio y se recuperó ADB tras renovar la autorización del teléfono.

## Criterios para la primera APK

| Escenario | Resultado requerido |
| --- | --- |
| Primer arranque | Confirmar túnel parado, elegir Casa/Sol y completar permisos/autenticación |
| Reabrir o volver del segundo plano | Restaurar el mismo hogar y verificar su VPN sin reiniciar un túnel correcto |
| Atrás desde el hogar | Borrar selección, cerrar panel y parar túnel antes de mostrar Casa/Sol |
| Casa → Sol y Sol → Casa | Exigir Atrás y cierre confirmado; verificar el perfil y el HA elegidos |
| Cuenta equivocada o caducada | Error y recuperación; ningún panel incorrecto |
| Servidor HA inaccesible | Error con reintento; nunca «conectado» solo por tener VPN |
| Wi-Fi → móvil y pérdida de red | Invalidar estado y recuperar conexión de forma verificable |
| VPN revocada por otra aplicación | Cerrar panel e indicar desconexión |
| Reinicio o proceso eliminado | Restaurar hogar guardado y reconstruir estado real de VPN |
| Pantalla apagada y ahorro de batería | Medir recepción, retraso y consumo |
| Alertas de ambas viviendas | Verificar origen y entrega con cada perfil activo |

Probar notificaciones con payloads reales y marcas temporales, incluyendo acciones y recuperación tras desconexión. No afirmar entrega garantizada basándose en pruebas del núcleo ni en el simple acceso al panel.
