# Plan de validación

## Historial de 48 horas, 27 de octubre de 2026

- APK de la revisión `80967db` compilada e instalada sobre el paquete existente mediante el instalador verificado, conservando los datos. SHA-256: `0b8a5cc9c416a8ec7a16d8fc31af6b4a331414dd652454e6f6cdd2dea1cf2616`.
- 37 pruebas de integración superadas: las 20 anteriores más 17 del historial (6 de repositorio, 5 de captura, 2 de Room, 2 de imágenes y 2 de interfaz). Pasaron también las 28 del núcleo, 8 de herramientas, compilación y lint del selector; la CI del commit terminó correctamente.
- Las pruebas usan notificaciones y datos sintéticos. Comprueban las dos aplicaciones permitidas, descarte de otros paquetes/servicios/resúmenes, conservación de alertas persistentes, bitmap de Companion oficial, copia privada de imágenes, duplicados, fallos de imagen y límite temporal de 48 horas.
- Se abrió el detalle de acceso a notificaciones de Android para el nuevo servicio de historial. El teléfono seguía bloqueado y el permiso aún no estaba concedido al terminar esta comprobación: falta que el usuario lo active. No afirmar captura física de avisos oficiales ni las pruebas locales de texto/imagen antes de ese paso.
- Pendiente tras el permiso: importar avisos activos permitidos, «Enviar prueba», comprobar la copia tras descartar el aviso, ampliar la imagen, filtros por aplicación y cierre del historial sin desconectar la vivienda.

## Versiones y novedades en la APK, 27 de octubre de 2026

- Cabecera y panel de versiones instalados en el Samsung SM-A566B con el instalador verificado, conservando los registros Casa/Sol. El motor mostrado es 1.105.4 y Companion muestra su versión real `2026.10.2-SNAPSHOT-minimal`; las revisiones proceden del lock y de los datos nativos correspondientes.
- Consulta real desde el móvil completada para los tres proyectos: Companion estable 2026.10.1 se clasificó como anterior; los estables Tailscale Android y Go 1.104.2, como líneas divergentes. Los tres resultados quedaron guardados sin errores y conservaron fecha después de actualizar la APK.
- Se comprobó la apertura de las notas en Firefox, el aviso de intervalo mínimo al consultar manualmente otra vez y el regreso a la aplicación. Abrir/cerrar versiones mantuvo la VPN; Atrás desde el hogar volvió al selector con VPN detenida. Se volvió a Sol y se verificaron su servidor activo, su panel privado y la VPN. La pantalla de versiones quedó abierta sobre Sol.
- WorkManager ejecutó correctamente las consultas iniciales. El trabajo periódico está configurado a 24 horas con red; no se ha esperado un día completo ni validado todavía su ejecución tras un reinicio. Android puede retrasarlo.
- 28 pruebas del núcleo, 20 de integración (10 de caché/comparación, 3 de cliente GitHub, 2 de interfaz y 5 de acceso HA), 8 de herramientas, compilación y lint del selector superados. Se probó la forma real de respuesta GitHub sin campo `head_commit`, errores de red/límite, cancelación e invalidación al cambiar los pins.
- APK instalada de la revisión `a636a30`: SHA-256 `db7136ae01d91d9592d7536b00327248e664867fd5f1cc6a0eb2ce30a45e4e13`.

## Herramientas de mantenimiento, 27 de octubre de 2026

- Consulta real de versiones estables y del motor declarado por Tailscale Android. El informe detectó que la estable de Companion era anterior a nuestro pin y que Tailscale divergía; el ensayo normal rechazó ese cambio antes de modificar los pins.
- Siete pruebas Python superadas: selección del motor desde `go.mod`, rechazo de reemplazos locales, comprobaciones de instalación, informes antiguos/orígenes distintos, formatos de certificado Android, APK alterado, conservación de worktrees con cambios y aislamiento de las revisiones candidatas (varios escenarios comparten prueba).
- `python3 tools/maintain.py build` completó las 28 pruebas del núcleo, compilación y lint del selector, compilación de la APK y las 5 pruebas de integración. Archivó APK, hash, certificado y manifiesto. Se obtuvo el mismo SHA-256 que la APK previamente instalada, sin cambiar los upstreams.
- Ensayo real `baseline-20261027` con los mismos pins: rama y worktrees separados, fuentes verificadas e integración preparada; el árbol principal permaneció limpio.
- Sin teléfono conectado: `install --dry-run` verificó el artefacto y se detuvo al exigir un dispositivo autorizado. La instalación física con esta nueva herramienta queda pendiente; el APK anterior sí fue instalado y probado durante la sesión del día 26.

## Selector Casa/Sol, 26 de octubre de 2026

- Desde el Samsung SM-A566B, una sola conexión Tailscale devolvió HTTP 200 y la página de Home Assistant de ambos servidores privados; no se necesitaron dos VPN simultáneas.
- La APK actualizada se instaló sobre el mismo paquete sin borrar los datos. El panel de Casa siguió abriendo por su URL privada.
- Atrás desde Casa mostró ambos botones y detuvo el servicio VPN. Elegir Sol inició la misma identidad Tailscale y abrió el alta de su propio Home Assistant por URL privada. El usuario completó el inicio de sesión en el móvil y confirmó que veía su panel.
- Después del alta, Sol volvió a abrirse sin repetir el inicio de sesión. Se comprobó Sol → selector → Casa → selector → Sol: Atrás detuvo el servicio VPN antes del selector y cada selección volvió a arrancarlo; los WebView activos usaron las direcciones privadas correspondientes.
- Cinco regresiones JUnit del adaptador pasaron: OAuth incompleto, HTTP privado, vinculación fija de Casa, prohibición de elegir Sol sin URL y selección del servidor Sol entre dos registros. Los sensores de Companion usan la clave compuesta `id` + `server_id`; falta medir las actualizaciones y alertas reales de ambos servidores.
- Se detectó una reapertura que mostró Casa tras haber usado Sol. La navegación de arranque se corrigió para pasar a Companion el ID del servidor ya verificado, sin depender de su selección global. Después de instalar la corrección, cerrar el proceso y reabrir restauró el servidor activo de Sol, la VPN y su URL privada; también funcionó tras desbloquear el teléfono.
- APK instalada: `dist/hatailapk-casa-debug.apk` (nombre heredado); SHA-256 `7b380232c4b0f2678b119a34cfcfce6bc9b887fa0763fdaf38140b15d4cef726`.

Pendiente: reinicio completo del teléfono, notificaciones y efectos de batería. El panel visible no demuestra por sí solo que las alertas lleguen.

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
