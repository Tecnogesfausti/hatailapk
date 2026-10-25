# Mantenimiento de las fuentes originales

Las tres fuentes están fijadas por commit completo en los submódulos y en `upstream.lock.json`. Tailscale Go usa la revisión declarada en el `go.mod` del cliente Android, no su última rama por separado. Esta base inicial incluye una revisión de desarrollo del motor; debe evaluarse una pareja estable antes de distribuir una APK.

## Incorporar una actualización

1. Trabajar en una rama del repositorio principal y conservar cualquier cambio local de los submódulos en ramas/commits antes de actualizar.
2. Leer notas de versión y cambios en APIs, licencias y requisitos de compilación. Hacer `git fetch origin` dentro del submódulo correspondiente.
3. Revisar la diferencia entre el commit fijado y el candidato. Seleccionar una revisión explícita con `git checkout --detach <commit>` solo con el árbol limpio.
4. Al actualizar `tailscale-android`, consultar su `go.mod`; fijar `tailscale` al commit correspondiente y actualizar también `moduleVersion`.
5. Actualizar los SHA de `upstream.lock.json` y registrar los gitlinks con `git add upstream/ upstream.lock.json` desde la raíz. No editar fuentes upstream sin preservar sus parches en el repositorio propio.
6. Ejecutar `python3 tools/upstreams.py verify`, las pruebas del núcleo, compilación/lint de la biblioteca y, cuando exista, compilación de la APK integrada. Probar la matriz de teléfono real antes de publicar.

La CI comprueba que los submódulos coinciden con el lock y que el motor corresponde al cliente Android. No incorpora actualizaciones automáticamente ni demuestra compatibilidad funcional con una APK aún pendiente.

## Cambios propios en los forks

Mantener código reutilizable en `connection-core/` y `selector-ui/`. Cuando la integración necesite modificar Companion o el cliente Tailscale, conservar los cambios como parches versionados o commits en ramas de forks propios y documentar cómo reconstruirlos sobre el commit original. Nunca apuntar un gitlink a un commit que solo exista en un ordenador local.

Preservar licencias y avisos de los originales, y separar las actualizaciones upstream de los cambios funcionales propios para facilitar su revisión.
