# Muse + Ray-Ban Meta Gen 2 (Xiaomi 14): prueba real

Esta edición prueba el camino **cámara Ray-Ban -> imagen JPEG -> OpenClaw local -> Muse -> respuesta hablada por Bluetooth**. No utiliza una cámara simulada ni reemplaza silenciosamente la cámara de las gafas por la del Xiaomi.

## Antes de empezar

1. Empareja las Ray-Ban mediante la aplicación oficial Meta AI y habilita el modo desarrollador.
2. Concede al APK permiso de **Cámara** para el SDK de Meta, y al Android los permisos **Micrófono**, **Cámara** y **Bluetooth**.
3. Mantén OpenClaw Gateway activo en Termux, escuchando solo en `127.0.0.1:18789`, y autentica el APK con su token del Gateway (no con un token de GitHub).
4. Para que responda *Muse*, configura el agente de OpenClaw con un modelo que admita imágenes, por ejemplo `meta/muse-spark-1.3`. La elección del modelo es en el Gateway, **no** en las gafas. Requiere una API de Muse habilitada con crédito disponible; consulta costes y no actives planes ni suscripciones sin consentimiento.

## Prueba práctica

1. Abre VisionClaw desde el Xiaomi 14 con las gafas encendidas. En la app debería indicar **Cámara real de las gafas conectada**. Este estado es necesario pero no prueba por sí solo la llegada de fotogramas.
2. Introduce la **clave local OpenClaw** una vez. Se almacena cifrada mediante Android Keystore; no se envía a GitHub.
3. Apunta las Ray-Ban a un objeto fácil de reconocer, sin personas ni información privada.
4. Toca **Probar Muse: ¿qué ven mis gafas?**.
5. La pantalla debe mostrar una foto de ese objeto con la línea «Imagen REAL recibida: ancho×alto». Si no aparece, la cámara NO ha entregado un fotograma a la app y el mensaje no se envía.
6. Si la foto aparece pero no hay respuesta, verifica la autenticación del Gateway y que el agente utiliza Muse con entrada de imagen. Una compilación correcta no demuestra que la conexión física o la API de Muse estén funcionando.
7. Una vez probado, di «Muse, ¿qué estoy viendo?» a través del micrófono de las gafas.

## Diagnóstico sin engaños

- **No se reciben fotogramas**: comprueba que las gafas están conectadas, activas y con permiso de cámara concedido en la app Meta. No uses el modo móvil para esta prueba.
- **No detecta las Ray-Ban para llamadas**: activa el perfil «Llamadas» en el Bluetooth del Xiaomi. No se admite silenciosamente otro auricular como micrófono.
- **Llega la fotografía pero Muse no la describe**: confirma que OpenClaw está conectado a un modelo multimodal y tiene acceso a su API.
- **SDK**: esta base compila contra DAT Android 0.4.0. DAT 1.0 cambió drásticamente el API de sesiones/cámara y **no** se puede actualizar modificando solo la versión de Gradle; hay que migrar y probar con gafas reales.
- **Privacidad y costes**: cada pregunta visual envía la imagen capturada a tu Gateway y al proveedor de modelo configurado. El modo Contributor de Muse tiene condiciones de datos distintas al estándar. La app no debe publicar ni almacenar la imagen por su cuenta.

Referencia oficial: https://developers.meta.com/wearables/device-access-toolkit/ y https://docs.openclaw.ai/providers/meta
