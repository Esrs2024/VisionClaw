# MoreThanTiles Inspector (Android / Ray-Ban Meta)

Una aplicación **independiente** con identificador `com.morethantiles.inspector.vision`.
No actualiza VisionClaw ni altera sus datos.

## Objetivo
- Captura imágenes **reales** del stream de Ray-Ban Meta con Meta Wearables DAT.
- Registra instantáneas y observaciones de la visita en almacenamiento privado.
- Habla y escucha usando **solo la ruta Bluetooth de llamadas de las gafas** (sin cambiar silenciosamente al micrófono del móvil).
- Envía observaciones + fotogramas a OpenClaw local en Termux (`127.0.0.1:18789`) y extrae las partidas estructuradas.
- Genera en el teléfono un PDF de presupuesto neerlandés, marcando como CONCEPT los datos incompletos.
- Permite cargar una **firma auténtica** y solo la aplica con autorización.
- No inventa cantidades, tarifas, cliente, IVA, firma ni conexión a la cámara.

## Estado y límites
Código de integración; **no es una prueba en las gafas reales**. El dispositivo debe autorizar los permisos del SDK de Meta. El ID Meta incluido (`0`) solo sirve en modo desarrollador: fuera de ese modo exige registrar este paquete nuevo en Meta Wearables Developer Center. El reconocimiento de voz Android y el audio SCO dependen del dispositivo y su configuración. Requiere OpenClaw local operativo y un agente capaz de devolver la estructura JSON esperada. El contenido visual se envía al agente cuando se reconoce una orden; las fotos se guardan durante la inspección. No mide distancias a partir de imágenes sin referencia.

La clave de OpenClaw se cifra mediante Android Keystore local. Las imágenes permanecen en almacenamiento de la app, salvo las que se envían al OpenClaw local al hablar. El PDF queda en Documentos de la aplicación y puede compartirse con el consentimiento del usuario. **No se firma ningún documento automáticamente** sin imagen de firma auténtica y permiso.

## Compilación
GitHub Actions -> `MoreThanTiles Inspector Android`. Requiere secreto existente `META_PACKAGES_TOKEN` con `read:packages` para el SDK de Meta. APK generado para Android 12+.
El keystore copiado del SDK de muestra es **de desarrollo público y NO adecuado para publicación comercial**; antes de distribuir profesionalmente se requiere keystore privado propio, almacenado en secretos GitHub Actions.
