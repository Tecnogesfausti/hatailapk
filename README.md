# HATailAPK

Una APK Android para elegir **Casa** o **Sol**, activar su conexión Tailscale y abrir el Home Assistant correspondiente sin depender de Cloudflare para el acceso al panel.

Al reabrir, continuar en el hogar seleccionado como en Companion, tras comprobar su VPN. Llevar la aplicación a segundo plano no abandona el hogar ni desconecta el túnel. El botón o gesto **Atrás** sale del hogar, desconecta Tailscale y devuelve al selector cuando el cierre esté confirmado. El selector aparece en el primer uso o después de esa salida explícita.

## Estado actual

**APK experimental para Casa y Sol**, con Companion y el motor Tailscale integrado. En la prueba de octubre de 2026, los dos Home Assistant respondieron desde el mismo móvil con una sola tailnet activa. Sol requiere un alta inicial propia en Companion:

- `connection-core/`: máquina de estados Kotlin; exige verificar perfil Tailscale, tailnet y servidor HA antes de autorizar la navegación.
- `selector-ui/`: biblioteca Android con Casa/Sol cuando no hay hogar seleccionado, progreso, errores y reintento. Textos en español e inglés; el host gestionará Atrás.
- `upstream/`: tres submódulos con las fuentes originales fijadas en `upstream.lock.json`; conservan sus historiales y licencias.
- `tools/`: preparación y comprobación de fuentes, y alternativa de pruebas sin Gradle.
- `.github/workflows/verify.yml`: pruebas, compilación de biblioteca y comprobación de versiones upstream.
- `integration/`: adaptador nativo de las dos viviendas, pantalla Compose y ajustes de compilación del cliente Tailscale como biblioteca.
- `.integration/`: worktrees generados y resultados locales, excluidos de Git; las fuentes fijadas en `upstream/` permanecen intactas.

El cliente integrado tiene su propio almacén seguro. Casa conserva su registro actual; Sol pide una vez la URL privada de su HA y su autenticación, y después aparece como acceso directo en el selector. Cada vivienda conserva su identificador de servidor y registro Companion. Atrás desconecta la VPN antes de volver al selector. Las notificaciones importantes y la entrega desde ambas viviendas necesitan pruebas específicas antes de considerar esta versión de uso habitual.

## Compilar la APK

Con JDK 21 y Android SDK configurados:

```sh
python3 tools/upstreams.py bootstrap
ANDROID_HOME=/home/estudio/android/sdk ./tools/build-integrated.sh
```

El script genera `dist/hatailapk-casa-debug.apk` para ARM64 (nombre del artefacto conservado durante la transición a dos viviendas). Descarga las herramientas requeridas por los originales (incluidos Gradle, Go, SDK/NDK y CMake). La primera compilación tarda más que las siguientes. Los locks de las dependencias de la integración se regeneran en los worktrees experimentales con `--write-locks`; revisar y fijar su conjunto antes de una distribución estable.

Esta APK usa el paquete `es.tecnogesfausti.hatailapk.minimal.debug` y no sustituye las aplicaciones oficiales. La variante minimal no utiliza FCM ni credenciales Google Services. Para instalar en un teléfono autorizado:

```sh
adb -s SERIAL install --user 0 -r dist/hatailapk-casa-debug.apk
```

## Preparar las fuentes

```sh
git clone --recurse-submodules https://github.com/Tecnogesfausti/hatailapk.git
cd hatailapk
python3 tools/upstreams.py verify
```

Para una copia sin submódulos descargados:

```sh
python3 tools/upstreams.py bootstrap
```

El script verifica origen, commit y ausencia de cambios locales. Rechaza sobrescribir trabajo existente. `build-integrated.sh` prepara copias de trabajo separadas para aplicar los cambios propios.

## Compilar y probar los módulos propios

Requisitos: JDK 21, Android SDK con plataforma 35 y acceso a Maven/Google/Gradle para la primera descarga. Configura `ANDROID_HOME` o `sdk.dir` en un `local.properties` no versionado.

```sh
./gradlew :connection-core:test
./gradlew :selector-ui:assembleDebug :selector-ui:lintDebug
```

El segundo comando produce una biblioteca `.aar`, no una APK. Las herramientas de esta raíz verifican los módulos propios; no sustituyen las versiones requeridas por cada upstream. Si Gradle no puede ejecutarse, `tools/test-core.sh` admite un compilador Kotlin y JAR de JUnit/Hamcrest ya instalados; consulta sus variables obligatorias.

## Configuración y siguientes pasos

`config/homes.example.json` describe el contrato general Casa/Sol, aún sin importador. El adaptador obtiene el perfil y la tailnet del motor real y guarda sus referencias cifradas. Sol usa la misma tailnet verificada en esta instalación, pero un servidor HA y un alta Companion distintos. No introduzcas contraseñas ni tokens en los archivos del repositorio.

Orden de implementación:

1. Completar la prueba real de Sol, el cambio Casa/Sol y la reapertura en ambos hogares.
2. Unificar el adaptador experimental con todos los efectos del núcleo general.
3. Verificar segundo plano, notificaciones y entrega desde ambos hogares.

Consulta [arquitectura](docs/architecture.md), [actualizaciones upstream](docs/upstream-maintenance.md) y [plan de validación](docs/validation.md).

## Mantenimiento asistido

`python3 tools/maintain.py check` genera un informe de versiones estables sin cambiar las fuentes. `trial --name NOMBRE --build` ensaya los candidatos en una rama aislada; `build` verifica y archiva una APK de las versiones actuales. `install --dry-run` comprueba un teléfono conectado y `install` actualiza conservando los datos, después de verificar paquete, versión y certificado. El [procedimiento completo](docs/upstream-maintenance.md) incluye la revisión semanal en GitHub y las pruebas de Casa/Sol.

## Versiones dentro de la APK

La cabecera «Versiones y novedades» está disponible en el selector y dentro del hogar. Muestra las versiones integradas de Companion y Tailscale; al tocarla aparecen los tres componentes (Companion, cliente Tailscale Android y motor Go), sus revisiones, la última estable consultada, la fecha y un enlace a las notas de versión. Abrir o cerrar esta información conserva el hogar y la VPN.

La consulta pública a GitHub se programa cada 24 horas con Internet, sujeta a los retrasos de batería de Android. Hay consulta manual con un mínimo de 15 minutos y caché sin conexión. Se compara el historial de commits: distingue una actualización posterior, una estable anterior y una rama divergente, sin confundir el número de versión con compatibilidad probada. El aviso ayuda a preparar un ensayo; la recompilación sigue realizándose en el PC. Estas versiones corresponden a la APK, no a Home Assistant Core de las viviendas.

Código propio bajo Apache-2.0. Cada submódulo conserva su licencia; consulta [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
