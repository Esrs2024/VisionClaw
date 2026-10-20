# Gemini directo para Xiaomi 14

Esta variante restaura el cliente Android anterior a la migración a LiveKit
(commit fuente 675a0bf2595773d09e4a14c8d8a03dba94945044), conservando el resto
del repositorio. No usa el servidor privado api.visionagents.app.

1. Instala el APK Gemini Direct generado en Actions.
2. Abre la rueda de ajustes, pega una API key obtenida en
   https://aistudio.google.com/apikey y pulsa Guardar.
3. Prueba Start on Phone y el botón AI con permisos de cámara y micrófono.
4. Para las gafas, activa el modo desarrollador de Meta AI, registra las
   gafas y usa Start Streaming y AI.

La API key se introduce en el dispositivo, no en GitHub, y se cifra mediante
Android Keystore. No se incluye en el APK. Se bloquean capturas de pantalla
en Ajustes para evitar mostrar credenciales. Los modelos y cuotas
dependen del proyecto de Google; el modelo Live puede cambiarse en Ajustes.
Modelo inicial: gemini-3.8-live, según la documentación consultada el 20/10/2026.

OpenClaw sigue siendo opcional y requiere su propio gateway; esta variante
permite voz y visión con Gemini sin configurar ese gateway. No proporciona
acciones externas ni acceso al calendario por sí sola.

La compilación no demuestra conexión con Gemini ni compatibilidad física
con las gafas. Eso se verifica en el Xiaomi con una clave válida.
