# HATailAPK

Una APK Android para elegir **Casa** o **Sol**, activar su conexión Tailscale y abrir el Home Assistant correspondiente sin depender de Cloudflare para el acceso al panel.

Al reabrir, continuar en el hogar seleccionado como en Companion, tras comprobar su VPN. Llevar la aplicación a segundo plano no abandona el hogar ni desconecta el túnel. El botón o gesto **Atrás** sale del hogar, desconecta Tailscale y devuelve al selector cuando el cierre esté confirmado. El selector aparece en el primer uso o después de esa salida explícita.

## Estado actual

Primera base de desarrollo, **todavía sin APK instalable ni conexión VPN integrada**:

- `connection-core/`: máquina de estados Kotlin; exige verificar perfil Tailscale, tailnet y servidor HA antes de autorizar la navegación.
- `selector-ui/`: biblioteca Android con Casa/Sol cuando no hay hogar seleccionado, progreso, errores y reintento. Textos en español e inglés; el host gestionará Atrás.
- `upstream/`: tres submódulos con las fuentes originales fijadas en `upstream.lock.json`; conservan sus historiales y licencias.
- `tools/`: preparación y comprobación de fuentes, y alternativa de pruebas sin Gradle.
- `.github/workflows/verify.yml`: pruebas, compilación de biblioteca y comprobación de versiones upstream.

La vista y el núcleo aún deben conectarse al servicio VPN y a Companion. No se simula una conexión satisfactoria. Las notificaciones simultáneas de ambas viviendas siguen pendientes de una solución y pruebas reales.

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

El script verifica origen, commit y ausencia de cambios locales. Rechaza sobrescribir trabajo existente. Los proyectos originales se compilan con sus propias herramientas; estos comandos preparan sus fuentes, no integran todavía la APK.

## Compilar y probar los módulos propios

Requisitos: JDK 21, Android SDK con plataforma 35 y acceso a Maven/Google/Gradle para la primera descarga. Configura `ANDROID_HOME` o `sdk.dir` en un `local.properties` no versionado.

```sh
./gradlew :connection-core:test
./gradlew :selector-ui:assembleDebug :selector-ui:lintDebug
```

El segundo comando produce una biblioteca `.aar`, no una APK. Las herramientas de esta raíz verifican los módulos propios; no sustituyen las versiones requeridas por cada upstream. Si Gradle no puede ejecutarse, `tools/test-core.sh` admite un compilador Kotlin y JAR de JUnit/Hamcrest ya instalados; consulta sus variables obligatorias.

## Configuración y siguientes pasos

`config/homes.example.json` describe los datos que deberá guardar el onboarding: servidor Companion, URL privada, perfil Tailscale y sufijo de tailnet. Es un ejemplo de contrato, aún sin importador. No introduzcas contraseñas ni tokens en ese archivo.

Orden de implementación:

1. Adaptar el motor Go y el único `VpnService` al proceso de Companion.
2. Añadir autenticación y asociación de perfiles Casa/Sol.
3. Conectar los efectos del núcleo y el selector al arranque de Companion.
4. Verificar reconexión, segundo plano y notificaciones en un teléfono real.

Consulta [arquitectura](docs/architecture.md), [actualizaciones upstream](docs/upstream-maintenance.md) y [plan de validación](docs/validation.md).

Código propio bajo Apache-2.0. Cada submódulo conserva su licencia; consulta [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
