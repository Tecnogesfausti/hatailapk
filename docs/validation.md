# Plan de validación

## Verificación inicial realizada

La ejecución enlazada a continuación corresponde a la base anterior. La revisión del flujo de reapertura/Atrás añade seis pruebas (24 en total) y requiere su propia ejecución CI.

- 18 pruebas JUnit del núcleo: selección de ambas viviendas, permisos, orden de operaciones, identidad, servidor HA, cambio de perfil, resultados tardíos, desconexión, errores, reintento y pérdida de VPN.
- Ejecución local mediante Kotlin 2.2.20 y JUnit 4.13 instalados. La misma suite pasó también en CI con las versiones declaradas: Kotlin 2.1.20 y JUnit 4.13.2.
- [GitHub Actions, ejecución 40219917982](https://github.com/Tecnogesfausti/hatailapk/actions/runs/40219917982): 18 pruebas superadas, biblioteca Android compilada, `lintDebug` superado y los tres submódulos verificados. El artefacto `selector-library-and-test-results` contiene el AAR y el informe de pruebas; no es una APK.
- La vista también compiló localmente contra Android SDK 35; se verificaron las mismas 23 claves de texto en español e inglés.
- Gradle local bloqueado por `java.net.SocketException: Operation not permitted` al crear el socket de su daemon. No se considera compilación Android superada por este intento.
- Git/SSH local sin resolución DNS de `github.com`; no se ha validado la deploy key desde esta terminal. La conexión GitHub permite consultar y publicar archivos por API.

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
