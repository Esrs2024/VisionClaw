# VisionClaw para gafas reales — 1.2

La aplicación utiliza Meta DAT 1.0.1 para registrar gafas reales, abrir una DeviceSession,
añadir la cámara y enviar sus imágenes a Gemini Live. No incluye MockDeviceKit,
su dependencia, sus pantallas ni el registro simulado. El modo de cámara del móvil
usa la cámara física del móvil y está etiquetado por separado.

El micrófono y el altavoz en modo gafas se dirigen al perfil Bluetooth de
comunicaciones de las Ray-Ban. Si ese micrófono no está disponible, se informa del
error y no se sustituye silenciosamente por el micrófono del móvil.

Requisitos externos: gafas emparejadas y conectadas en Meta AI, firmware y aplicación
Meta AI compatibles, autorización de cámara y modo desarrollador de Meta AI para
esta instalación personal (APPLICATION_ID=0). El modo desarrollador autoriza
hardware real; no activa ninguna simulación.

La API key de Gemini se introduce en Ajustes, se guarda cifrada con Android Keystore
y no se incluye en el APK. OpenClaw es opcional y requiere un gateway para acciones
externas. No se afirma que un error de conexión del hardware esté resuelto hasta
probar la aplicación en el Xiaomi y las gafas del usuario.
