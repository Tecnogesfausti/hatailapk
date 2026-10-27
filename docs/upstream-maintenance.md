# Actualizar, compilar e instalar HATailAPK

El punto de entrada para el mantenedor y el agente es `python3 tools/maintain.py`. Los tres originales siguen fijados en `upstream.lock.json`. El motor Go se obtiene de `go.mod` del cliente Tailscale Android; no se elige su última versión por separado.

## Revisar novedades

```sh
python3 tools/maintain.py check
```

Consulta las versiones estables de Companion y Tailscale Android, resuelve sus commits y el motor correspondiente y escribe `.maintenance/upstream-report.md` y `.json`. Incluye enlaces a versiones y comparaciones de código. No cambia los pins ni el teléfono. `--ha-ref TAG_O_SHA` y `--tailscale-ref TAG_O_SHA` permiten evaluar revisiones explícitas.

GitHub Actions ejecuta esta consulta los lunes a las 07:23 UTC y mediante **Actions → Review upstream releases → Run workflow**. El informe queda en el resumen y en el artefacto `upstream-review` durante 30 días. No crea PR, mezcla ramas ni instala automáticamente.

La base actual contiene código de desarrollo. Una estable puede aparecer como `behind` o `diverged`: no equivale a una actualización directa. Revisar notas de versión, APIs, licencias y requisitos antes de decidir. GitHub limita las consultas anónimas; puede usarse `GH_TOKEN` o `GITHUB_TOKEN` del entorno, nunca guardarlo en archivos.

## Ensayar la actualización

Con los cambios propios guardados en un commit:

```sh
python3 tools/maintain.py trial --name prueba-upstream --build
```

Crea la rama `maintenance/upstream-prueba-upstream` y la copia `.maintenance/trials/prueba-upstream/`. Allí prepara el lock y los gitlinks candidatos, comprueba su coherencia, aplica la integración y compila. `main`, sus checkouts originales y el móvil conservan sus versiones. Si falla un anclaje o una compilación, el ensayo queda disponible para corregirlo; consultar el registro indicado.

El comando rechaza informes antiguos, orígenes distintos y retrocesos o divergencias. Solo después de revisar una migración intencionada se permite `--allow-non-forward`. Para preparar sin compilar, omitir `--build`. Usar un nombre nuevo por ensayo.

Los worktrees generados en `.integration/` se verifican por revisión. Si cambia el pin, la copia anterior se mueve a `.integration/archive/`, conservando sus cambios locales, y se genera otra. Los cambios funcionales mantenibles deben vivir en `integration/` o los módulos propios, no únicamente en esas copias desechables.

## Compilar una versión conocida

```sh
python3 tools/maintain.py build
```

Detecta el SDK desde `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `local.properties` o las rutas habituales del usuario. Requiere JDK 21 y las herramientas Android; las primeras descargas necesitan red y espacio libre. Ejecuta verificación de fuentes, pruebas del núcleo, compilación/lint del selector y compilación/pruebas de la APK integrada, en orden.

Cada ejecución guarda un directorio fechado en `.maintenance/builds/` con `build.log`, `hatailapk.apk` y `manifest.json`. El manifiesto registra SHA-256, revisiones propias/upstream, paquete, versión, certificado y resultado de los controles. `.maintenance/latest-build.json` apunta al último resultado correcto; un fallo no lo sustituye. Los artefactos quedan fuera de Git.

## Instalar conservando el alta de Casa y Sol

Conectar por USB el teléfono y autorizar ADB:

```sh
python3 tools/maintain.py install --dry-run
python3 tools/maintain.py install
```

Con varios móviles, añadir `--serial SERIAL`. Para el APK de un ensayo, añadir `--manifest /ruta/al/ensayo/.maintenance/builds/FECHA/manifest.json`. Al ejecutarlo desde la copia del ensayo se utiliza directamente su última compilación.

El instalador verifica el hash y los metadatos reales, exige el paquete HATailAPK, compara el certificado con la aplicación instalada y rechaza una versión inferior. Conserva el APK anterior en `.maintenance/installations/` y usa `adb install --user 0 -r`, sin desinstalar ni borrar datos. `--dry-run` hace las comprobaciones y copia el APK anterior, pero no instala. Si no hay móvil autorizado, se detiene con instrucciones.

Conservar la clave de firma usada: esta versión experimental utiliza `~/.android/debug.keystore`. Guardar una copia segura fuera de Git; cambiarla impide actualizar la instalación existente. Un APK generado en otro ordenador o en CI con una clave diferente será rechazado. El APK anterior **no es una copia de los datos**: migraciones de base de datos de un upstream pueden impedir volver a una versión antigua. No se fuerza un downgrade.

## Validar e incorporar

Probar Casa y Sol, Atrás con VPN detenida antes del selector, reapertura, cierre del proceso, red móvil/Wi-Fi y autenticación. Verificar por separado sensores y alertas de ambos HA con pantalla apagada; una compilación correcta no demuestra notificaciones fiables.

En la copia del ensayo, revisar `git diff --cached --submodule=log`, guardar las correcciones propias y el lock/gitlinks en un commit. Incorporar la rama a `main` solo tras esas pruebas; `git merge --ff-only maintenance/upstream-prueba-upstream` sirve cuando no hay cambios posteriores en `main`. Si no puede avanzar, revisar la integración entre ramas sin forzarla. Después de incorporar los pins, comprobar que los submódulos no tienen cambios locales y ejecutar `git submodule update --init --recursive` para alinear sus checkouts, seguido de `python3 tools/upstreams.py verify`. Mantener la actualización upstream separada de cambios funcionales ajenos.

Para una sesión futura basta pedir: «Revisa upstreams y prepara un ensayo; compila y, cuando conecte el móvil, instala la versión verificada». El agente debe leer este documento y seguir los comandos, conservando licencias y avisos de los tres originales.
