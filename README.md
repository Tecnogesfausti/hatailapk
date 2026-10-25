# HATailAPK

Una APK Android para elegir **Casa** o **Sol**, activar su conexión Tailscale y abrir el Home Assistant correspondiente sin depender de Cloudflare para el acceso al panel.

Al reabrir, continuar en el hogar seleccionado como en Companion, tras comprobar su VPN. Llevar la aplicación a segundo plano no abandona el hogar ni desconecta el túnel. El botón o gesto **Atrás** sale del hogar, desconecta Tailscale y devuelve al selector cuando el cierre esté confirmado. El selector aparece en el primer uso o después de esa salida explícita.

## Estado actual

Primera **APK experimental para Casa**, con Companion y el motor Tailscale integrado. Sol permanece deshabilitado en esta prueba:

- `connection-core/`: máquina de estados Kotlin; exige verificar perfil Tailscale, tailnet y servidor HA antes de autorizar la navegación.
- `selector-ui/`: biblioteca Android con Casa/Sol cuando no hay hogar seleccionado, progreso, errores y reintento. Textos en español e inglés; el host gestionará Atrás.
- `upstream/`: tres submódulos con las fuentes originales fijadas en `upstream.lock.json`; conservan sus historiales y licencias.
- `tools/`: preparación y comprobación de fuentes, y alternativa de pruebas sin Gradle.
- `.github/workflows/verify.yml`: pruebas, compilación de biblioteca y comprobación de versiones upstream.
- `integration/`: adaptador nativo Casa, pantalla Compose y ajustes de compilación del cliente Tailscale como biblioteca.
- `.integration/`: worktrees generados y resultados locales, excluidos de Git; las fuentes fijadas en `upstream/` permanecen intactas.

El cliente integrado tiene su propio almacén seguro: requiere iniciar sesión en Tailscale y asociar su cuenta con Casa. Companion se registra con la URL privada del HA. Las notificaciones importantes y la entrega desde ambas viviendas necesitan pruebas específicas antes de considerar esta versión de uso habitual.

## Compilar la APK Casa

Con JDK 21 y Android SDK configurados:

```sh
python3 tools/upstreams.py bootstrap
ANDROID_HOME=/home/estudio/android/sdk ./tools/build-integrated.sh
```

El script genera `dist/hatailapk-casa-debug.apk` para ARM64. Descarga las herramientas requeridas por los originales (incluidos Gradle, Go, SDK/NDK y CMake). La primera compilación tarda más que las siguientes. Los locks de las dependencias de la integración se regeneran en los worktrees experimentales con `--write-locks`; revisar y fijar su conjunto antes de una distribución estable.

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

`config/homes.example.json` describe el contrato general Casa/Sol, aún sin importador. El prototipo Casa obtiene el perfil y la tailnet del motor real y guarda sus referencias cifradas. No introduzcas contraseñas ni tokens en los archivos del repositorio.

Orden de implementación:

1. Completar las pruebas reales de Casa, reapertura, Atrás y reconexión.
2. Unificar el adaptador experimental con todos los efectos del núcleo general y habilitar Sol con sus propios datos.
3. Verificar segundo plano, notificaciones y entrega desde el hogar inactivo.

Consulta [arquitectura](docs/architecture.md), [actualizaciones upstream](docs/upstream-maintenance.md) y [plan de validación](docs/validation.md).

Código propio bajo Apache-2.0. Cada submódulo conserva su licencia; consulta [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
