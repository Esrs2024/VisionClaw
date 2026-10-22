# MoreThanTiles Inspector — Ray-Ban Meta y OpenClaw

Aplicación Android independiente para inspeccionar reformas **con imágenes de la cámara real de las gafas**, órdenes habladas **desde las gafas**, un agente OpenClaw local en Termux y generación de presupuestos en PDF. Paquete `com.morethantiles.inspector.vision`.

## Arquitectura V3

- **Meta Wearables DAT 1.0.1** (Maven Central), `DeviceSession` + `Camera` y reconocimiento `Speech` directamente en las gafas. Esta función Speech sigue siendo **experimental** en el SDK oficial.
- Guarda fotogramas reales (no toma imágenes del teléfono ni activa el micrófono del teléfono como sustitutos silenciosos).
- Convierte las observaciones habladas y un fotograma reciente en un mensaje local a `ws://127.0.0.1:18789` (OpenClaw Gateway).
- Recibe partidas JSON del agente y permite crear un presupuesto PDF neerlandés de MoreThanTiles, marcando los datos incompletos como borrador. No inventa mediciones, precios ni IVA.
- Firma auténtica solo con archivo seleccionado y autorización del propietario. Contraseña del Gateway protegida mediante Android Keystore.
- Respuestas de voz con TTS Android solo si detecta salida Bluetooth Ray-Ban/Meta. No asegura el resultado sin un ensayo real de audio.
- Muestra mensajes de error de permisos, conexión y fallos de arranque, en lugar de cerrar la ventana sin explicación.

## Estado verificable

- Se han actualizado dependencias, interfaz Android y protocolo de sesión/cámara al SDK publicado por Meta.
- La compilación en GitHub Actions y la presencia de la actividad de lanzamiento se comprueban mediante CI.
- **Una compilación correcta NO verifica que funcionen cámara, audio, permisos del Developer Center o OpenClaw en unas Ray-Ban físicas.**
- La prueba de apertura con emulador anterior no consiguió completar la inicialización del emulador; no demuestra ni descarta un fallo de la app.
- Se necesita un ensayo con unas Ray-Ban Meta Gen 2 reales y OpenClaw activo en Termux. El registro y el permiso MICROPHONE deben estar autorizados y pueden requerir modo desarrollador de Meta AI.

## Uso previsto

1. Abre MoreThanTiles Inspector y vincula la aplicación desde Meta AI (desarrollo: Id/Token Meta = 0).
2. Con OpenClaw ejecutándose en el mismo móvil, introduce su **Gateway token**, nunca la clave Google ni la clave de paquetes de Meta.
3. Pulsa **COMENZAR INSPECCIÓN CON GAFAS**, acepta los permisos de cámara y, si está disponible, micrófono desde Meta.
4. Comprueba que la pantalla recibe fotogramas reales; describe la estancia y sus trabajos con las gafas.
5. El agente debe hacer preguntas para las cantidades/precios que faltan. Genera PDF; si faltan datos, el archivo se identifica como borrador.

**Límite:** fotografiar no equivale a medir. Cantidades, longitudes, superficies y precios requieren referencias medibles o confirmación del profesional.

## Compilación

CI: `.github/workflows/morethantiles-inspector.yml`. El kit de Meta se obtiene de Maven Central; no hace falta `META_PACKAGES_TOKEN`. El keystore incorporado es de desarrollo público, **no válido para distribuir profesionalmente**. En producción se necesita registro de aplicación en Meta Wearables Developer Center y credenciales reales.

No instalar como versión probada para uso profesional sin una inspección de prueba con gafas reales.
