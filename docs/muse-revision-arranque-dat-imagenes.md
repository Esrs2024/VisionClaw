# Revisión Muse: arranque, conexión DAT e imágenes — VisionClaw (gemini-direct)

- Fecha: 2026-10-27. Rama revisada: `gemini-direct` @ `dc96a36` ("Verify Muse v7 APK version in CI", 2026-10-23).
- Rama de esta revisión: `muse/revision-arranque-dat-imagenes`. Solo lectura de código; no se tocó la app existente.
- Alcance: esta revisión es solo del código de `gemini-direct` tal como está en GitHub. No verifica que el APK instalado en el Xiaomi corresponda a este HEAD, ni el estado real del gateway Termux, ni el emparejamiento de las gafas. Eso solo se confirma probando en el teléfono y las gafas reales, como el propio `GEMINI-DIRECT.md` exige.

## Corrección importante: no es la app del ZIP antiguo

La app de este repo **no** es `com.morethantiles.inspector` (la del ZIP revisado en octubre). Es el sample de Meta adaptado: paquete `com.meta.wearable.dat.externalsampleapps.cameraaccess`, etiqueta `VisionClaw`, módulo `samples/CameraAccessAndroid`, DAT SDK `1.0.1`, sin `MockDeviceKit` (el CI lo prohíbe por texto). CI (`.github/workflows/android-apk.yml`): compila `:app:assembleDebug` (debug, firmado con `sample.keystore` `sample/sample`) y publica el artefacto con nombre `VisionClaw-v6-…` (inconsistencia cosmética: artefacto v6, commits v7, `versionName` 1.6-muse-conversacion, `versionCode` 7).

## 1. Arranque

Flujo real (`MainActivity.kt` 61-85): `SettingsManager.init` → `FLAG_KEEP_SCREEN_ON` → `checkPermissions { Wearables.initialize → startMonitoring }` → scaffold Compose. No hay clase `Application` propia ni handler global de crashes; el diagnóstico es solo `Log` + snackbar/toasts.

Navegación por estado (`ui/CameraAccessScaffold.kt:74-94`): no registrado → `HomeScreen` (botón "Conectar mis Ray-Ban Meta" → `startRegistration`); registrado sin streaming → `NonStreamScreen`; `isStreaming` → `StreamScreen` (al entrar lanza `startStream()` o cámara del móvil, `ui/StreamScreen.kt:89-97`; al salir detiene todo en `onDispose`, 100-107).

Riesgos concretos:
1. `MainActivity.kt:87-99`: `checkPermissions()` registra un **nuevo** `ActivityResultLauncher` en cada llamada y **solo comprueba `BLUETOOTH_CONNECT`**. Denegar `CAMERA` o `RECORD_AUDIO` no detiene el arranque y el fallo aparece después (permiso DAT de cámara en `WearablesViewModel.kt:137-169`, micrófono en `ui/StreamScreen.kt:204-217`). No es un crash en sí, pero es un hueco de permisos en arranque.
2. `settings/SettingsManager.kt:10-11`: `prefs` y `secureKeys` son `lateinit` y solo se inicializan en `MainActivity.onCreate`. Cualquier acceso anterior lanzaría `UninitializedPropertyAccessException`. Hoy `StreamingService` no toca `SettingsManager`, así que el riesgo es latente, no activo.
3. `settings/SecureKeyStore.kt:28-37`: `read()` devuelve `""` ante **cualquier** error de descifrado. Un fallo de Keystore se vería como "falta la API key de Gemini", sin diagnóstico.
4. Se compila **debug** con keystore de ejemplo. No es causa de cierre, pero no es firma de producción.

Sólido en arranque: si `Wearables.initialize` falla (Meta AI ausente/desactualizada, sin modo desarrollador), la app **no se cierra**: muestra el error y queda en `HomeScreen`. `startMonitoring()` es idempotente (`WearablesViewModel.kt:52-57`); errores de registro (`registrationErrorStream`, 67-71) y `DEVICE_UPDATE_REQUIRED` (109-116) se muestran como errores legibles; `startRegistration` con guarda `isSdkReady` y mensaje en español.

## 2. Conexión Meta DAT con las Ray-Ban

- Registro: `WearablesViewModel.startRegistration()` → `Wearables.startRegistration(activity)` (autorización en la app Meta AI). Configuración en `app/src/main/res/values/mwdat_config.xml:4-5`: `mwdat_application_id = 0`, `mwdat_client_token = 0` (modo desarrollador para instalación personal, confirmado en `GEMINI-DIRECT.md`). Sin `APPLICATION_ID` real no hay atestación de producción. (Verificado por Muse: valores 0/0 en el archivo.)
- Selección de dispositivo: `WearablesViewModel.kt:46` usa `AutoDeviceSelector()` ("primer wearable disponible"). **No hay ninguna comprobación de que el dispositivo sea una Ray-Ban Meta Gen 2**; con otro wearable disponible podría seleccionar el equivocado.
- Monitorización correcta: `devices`, `registrationState`, `devicesMetadata`, cancelación de jobs de dispositivos retirados (`WearablesViewModel.kt:96-121`) y limpieza en `onCleared()` (203-209).
- Permisos DAT: cámara antes de streaming (`WearablesViewModel.kt:143-167`, solo ramifica `Denied`/`Granted`; otros valores de `PermissionStatus` caerían en silencio); micrófono al pulsar AI (`ui/StreamScreen.kt:206-211`). Manifiesto: `BLUETOOTH`, `BLUETOOTH_ADMIN`, `BLUETOOTH_CONNECT`, `INTERNET`, `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`, `CAMERA`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `WAKE_LOCK`. Falta `POST_NOTIFICATIONS` (Android 13+): el servicio seguiría corriendo pero su notificación podría no mostrarse. `usesCleartextTraffic=true` (necesario para `ws://127.0.0.1`, pero habilita cleartext en general).
- No hay código de atestación propio en la app: la atestación la resuelven el SDK y la app Meta AI. No hay placeholders de dispositivo ni simuladores en esta rama.

## 3. Sesión y cámara (imágenes reales)

`startStream()` (`stream/StreamViewModel.kt:135-200`): `stopStream()` previo → `Wearables.createSession(deviceSelector)` → `created.start()` → espera máx. 20 s a `STARTED`/`STOPPED` → `created.addCamera(StreamConfiguration(videoQuality = MEDIUM, frameRate = 15, compressVideo = false))` → `StreamingService.start()` → recoge `videoStream`, `errorStream` y `state`.

- **BUG 1, fuga de sesión en timeout (verificado por Muse):** si los 20 s expiran, `withTimeout` lanza `TimeoutCancellationException`, subclase de `CancellationException`, que se relanza en `StreamViewModel.kt:192-193` **sin llamar a `stopStream()`**, dejando la `DeviceSession` creada (`deviceSession = created`, línea 143) abierta sin cámara ni limpieza. El `catch` genérico (194-197) sí llama a `stopStream()`.
- Errores de sesión/vídeo/estado terminal provocan `stopStream()` + vuelta a selección de dispositivo (144-150, 168-174, 175-186): agresivo pero coherente; un error transitorio expulsa al usuario de la galería.
- `stopStream()` (224-248) es completo y en orden: `stopMuse()`, resetea el timestamp de frame, para el servicio, cancela jobs, `camera?.close()`, `deviceSession?.stop()`, para la cámara del móvil y resetea el estado. Es idempotente, así que las dobles llamadas desde `StreamScreen.onDispose` y desde los colectores de error son seguras.

Vídeo (`stream/StreamViewModel.kt:322-355`, `handleVideoFrame`):
- Rechaza frames comprimidos con error visible (`isCompressed`, 323-326) aunque pidió `compressVideo = false`; si el SDK entregara comprimido, el error se repetiría en **cada** frame sin detener el stream.
- Convierte I420 → NV21 → `YuvImage` → JPEG calidad 50 → `Bitmap`, actualiza `lastRealGlassesFrameAt` y `uiState.videoFrame` (pantalla completa en `ui/StreamScreen.kt:131-138`), y reenvía a Gemini (limitado, `gemini/GeminiSessionViewModel.kt:203-211`, 1 frame/s) y a WebRTC (cada frame).
- Riesgos de parada silenciosa: (a) si `width`/`height`/tamaño del buffer no cuadran con I420 empaquetado, `convertI420toNV21` puede lanzar `IndexOutOfBoundsException` dentro del `collect` de `videoStream` → se cancela `videoJob` sin error visible, la UI queda congelada en el último frame con estado todavía `STREAMING`; (b) `YuvImage` se construye con `strides = null` (planos empaquetados asumidos): buffers con stride distinto darían imagen distorsionada, no crash; (c) si `BitmapFactory.decodeByteArray` devuelve `null`, `lastRealGlassesFrameAt` se actualiza igualmente (línea 348) y el `null` se propaga a Gemini/WebRTC (el camino de visión de Muse se salva porque `MuseVoiceSession` comprueba `frame == null` antes de enviar, pero el timestamp queda "fresco" con un frame fallido).

Foto fija (`StreamViewModel.kt:250-287`, `capturePhoto()`): solo con `STREAMING`; en modo móvil captura el frame actual; en modo gafas usa `streamSession.capturePhoto()` → `handlePhotoData` (372-387): `Bitmap` directo o `HEIC` con transformación EXIF. **Fallo silencioso:** el `onFailure` de captura solo hace `Log.e` (276-279), sin error para el usuario; si `decodeHeic` devuelve `null`, el diálogo de compartir no se abre (`StreamScreen.kt:235`, guarda `?.let`) y tampoco hay mensaje.

Validación de frescura (sólida y explícita): `muse/MuseVoiceSession.kt:226-232` — para preguntas de visión exige frame no nulo, `frameAt != 0`, edad `0..4000 ms`; si no, rechaza con "NO hay imagen reciente Ray-Ban. No se ha enviado una foto inventada." No hay sustitución por cámara del móvil ni foto cacheada en ese camino. La imagen de visión se reduce a máx. 960 px y JPEG 65 antes de enviar (`MuseVoiceSession.kt:233-253`).

Modo móvil separado y etiquetado: `phone/PhoneCameraManager.kt` (CameraX, cámara trasera, `STRATEGY_KEEP_ONLY_LATEST`, `imageProxy.close()` correcto). Riesgos menores: el `Executor` de un hilo nunca se cierra en `stop()` (63-67), y `imageProxyToBitmap` depende de `createBitmap` con bitmap potencialmente nulo, aunque queda dentro del `try/catch` (105-108).

## 4. Voz: veredicto del patrón conocido

En esta rama, el patrón "speech registrado y nunca liberado" **NO está presente** en el camino de voz Muse. La única llamada a `addSpeech()` es `muse/MuseVoiceSession.kt:103`, y su `stop()` (313-343) hace exactamente lo que faltaba antes: cancela los 4 jobs, `speech?.stop()`, **`speechOwner?.removeSpeech()`** (líneas 331-333, verificado por Muse), `gateway?.close()`, y `tts?.stop()` + `tts?.shutdown()`. El propio comentario (314-316) documenta el fallo antiguo ("A capability of this type is already active on the session"). `StreamViewModel.stopStream()` llama a `stopMuse()` en primer lugar (línea 225), y `StreamScreen.onDispose` y el botón de parada también lo hacen.

- Riesgo residual menor: si `stop()` se llama mientras `attachMetaSpeech` está a mitad de `addSpeech()`, el `onSuccess` posterior podría dejar un `Speech` asignado tras la limpieza (la guarda `!running` solo se comprueba al entrar, línea 100). No es el bug original, pero es una carrera real.
- El camino Gemini **no usa DAT speech**: usa `AudioRecord` + WebSocket Gemini Live (`gemini/AudioManager.kt`, `gemini/GeminiLiveService.kt`). Su micrófono de gafas es estricto y correcto en lo esencial: exige dispositivo SCO `Ray`/`Meta`, verifica que `routedDevice` siga siendo SCO y **no** sustituye por el micrófono del móvil (`AudioManager.kt:61, 127-136`); `stopCapture()` libera `AudioRecord`/`AudioTrack` y restaura la ruta (174-199). Riesgo menor: si `check(STATE_INITIALIZED)` (113) o el constructor de `AudioRecord` fallan tras cambiar la ruta de audio, el `AudioTrack` ya construido y la ruta modificada quedan sin restaurar (no hay `try/finally` en ese tramo).
- Voz Muse (TTS por gafas): `MuseVoiceSession.kt:282-302` — TTS `es-ES`, truncado a 750 caracteres, con comprobación heurística de salida Ray-Ban/Meta (`hasGlassesAudioOutput`, 270-280, nombres `Ray`/`Meta`/`RB ` + tipos A2DP/SCO/BLE_HEADSET) y fallback explícito a "respuesta en pantalla" si no se confirma. Ojo: usa `USAGE_MEDIA`, no `USAGE_VOICE_COMMUNICATION`, así que la respuesta va por audio multimedia (A2DP), no por canal de llamada. Wake word: `muse` / `oye muse` / `hola muse` + ventana de 10 s (`MuseVoiceSession.kt:119-135`); mientras suena la respuesta, el reconocimiento se suspende (`speech?.stop()`, línea 258) y se reanuda al terminar (`resumeSpeech`, 304-311). Si el estado DAT pasa a `STOPPED` tras haber arrancado, muestra "Apaga y enciende AI" (152-155).

## 5. OpenClaw y el punto 5 del encargo

**No hay ninguna llamada a una API de Muse en la app.** "Muse" es solo el nombre de la sesión de voz local; el modelo real es el que tenga configurado el gateway de Termux del usuario (la cabecera de `muse/MuseGateway.kt:16-20` lo dice: "never stores a Muse API key", "the only remote AI provider is the one configured in the owner's Termux Gateway"). Por tanto, "hablar con Muse AI" a través de OpenClaw **no está implementado como chat con el modelo Muse**; está implementado como chat con el agente OpenClaw local. Esto debe quedar claro en la coordinación: el cerebro es el del gateway (Gemini en las pruebas del usuario), no Muse.

Existen **tres clientes OpenClaw distintos e inconsistentes entre sí**:
1. `muse/MuseGateway.kt` (camino de voz AI): WebSocket `ws://127.0.0.1:18789` fijo, protocolo 4, rol `operator`, sesión `agent:main:visionclaw-original-muse`, `chat.send` con adjunto JPEG en Base64, timeout de respuesta 75 s (42-52). Filtra eventos `chat` por `sessionKey` exacta (línea 100): un `final` de otra sesión no limpiaría `busy` hasta el timeout. El `catch` de `onMessage` traga **todas** las excepciones en silencio (142-144): un paquete malformado se vuelve indistinguible de "sin respuesta" hasta los 75 s.
2. `openclaw/OpenClawBridge.kt` (tool calls de Gemini): HTTP `${host}:${port}/v1/chat/completions`, modelo `"openclaw"`, sesión `agent:main:glass`, canal `glass`, historial de 10 turnos, circuit breaker de 3 fallos en `ToolCallRouter.kt:31-40`. **Chequeo de conexión débil** (`OpenClawBridge.kt:67-69`): cualquier HTTP 200–499 (incluidos 401/404) se marca `Connected`.
3. `openclaw/OpenClawEventClient.kt` (notificaciones): WebSocket `ws://host:port`, protocolo **3** (no 4), rol `node`, `caps = [camera, voice]`, `displayName "VisionClaw Glass"`, reconexión exponencial hasta 30 s; solo reenvía `heartbeat`/`cron` a Gemini como texto.
- Riesgo de integración: dos `sessionKey` fijos distintos (`visionclaw-original-muse` vs `glass`) y dos versiones de protocolo; si el gateway de Termux espera otro agente/sesión (en el contexto previo el agente era `morethantiles`), las preguntas de voz pueden no llegar o no volver aunque el socket conecte.
- Tokens: la clave Gemini va cifrada con Android Keystore (`settings/SecureKeyStore.kt`, AES/GCM, correcto); **el token del gateway OpenClaw y el hook token van en `SharedPreferences` en claro** (`settings/SettingsManager.kt:42-47`, prefs `visionclaw_settings`; `Secrets.kt` no está en el repo, solo `Secrets.kt.example` con valores vacíos, y el CI lo genera a partir del ejemplo). `MuseGateway` y `StreamViewModel.startMuse()` rechazan token vacío o con prefijo `YOUR_` (líneas 63-65 y `StreamViewModel.kt:117-121`).

## 6. Qué está sólido (resumen)

- Sin simuladores en esta rama; CI que lo verifica por texto y fija DAT 1.0.1.
- Sesión DAT con timeout, errores visibles en español, `DEVICE_UPDATE_REQUIRED` y `registrationErrorStream` surfacing, y `stopStream()` completo e idempotente.
- Voz Muse con `removeSpeech()` real en `stop()` (el bug antiguo está corregido en este camino), sin sustitución silenciosa por micrófono del móvil, y regla de imagen fresca de 4 s sin fotos inventadas.
- Foto HEIC con EXIF, compartir por `FileProvider`, servicio en primer plano `connectedDevice` con wake lock de 10 min y liberación en `onDestroy`.
- Clave Gemini en Keystore; tokens con validación de placeholder antes de conectar.

## 7. Prioridad de arreglo para ChatGPT

1. **Fuga de `DeviceSession` en timeout de 20 s** (`stream/StreamViewModel.kt:152-155,192-193`): llamar a `stopStream()` antes de relanzar `CancellationException`.
2. **Parada silenciosa del vídeo** (`stream/StreamViewModel.kt:322-355`): error visible y/o `stopStream()` al fallar la conversión; no actualizar el timestamp de frescura si `decodeByteArray` devuelve `null`; no repetir el error de frame comprimido sin detener el stream.
3. **Fallo de foto silencioso** (`stream/StreamViewModel.kt:276-279`): error visible al usuario cuando `capturePhoto` falla o `decodeHeic` devuelve `null`.
4. **Unificar los tres clientes OpenClaw** (una `sessionKey`, una versión de protocolo) y endurecer el chequeo `Connected` (solo 2xx con respuesta válida).
5. **Redefinir el punto 5**: no existe API de Muse; decidir entre (a) mantener el cerebro del gateway (Gemini) o (b) cambiar la arquitectura.
6. **Permisos en arranque**: comprobar `CAMERA` y `RECORD_AUDIO` en `MainActivity`, no solo `BLUETOOTH_CONNECT`; verificar que el dispositivo seleccionado sea Ray-Ban Meta Gen 2 (no solo el primer wearable).
7. **Token OpenClaw en claro**: guardarlo en Keystore como la clave Gemini.
8. **Menores**: `try/finally` en `AudioManager.startCapture`; cerrar executors; no destruir/recrear el stream al entrar/salir de Ajustes (`SettingsScreen` sustituye a `StreamScreen` en el scaffold); no tragar excepciones en `MuseGateway.onMessage`; unificar `versionName`/`versionCode`/artefacto; no distribuir APK debug con keystore de ejemplo.

## 8. Archivos leídos íntegros

`MainActivity.kt`, `wearables/WearablesViewModel.kt`, `WearablesUiState.kt`, `stream/StreamViewModel.kt`, `StreamUiState.kt`, `StreamingService.kt`, `muse/MuseVoiceSession.kt`, `MuseGateway.kt`, `openclaw/OpenClawBridge.kt`, `OpenClawEventClient.kt`, `ToolCallRouter.kt`, `gemini/GeminiLiveService.kt`, `GeminiSessionViewModel.kt`, `GeminiConfig.kt`, `AudioManager.kt`, `phone/PhoneCameraManager.kt`, `settings/SettingsManager.kt`, `SecureKeyStore.kt`, `Secrets.kt.example`, `ui/CameraAccessScaffold.kt`, `StreamScreen.kt`, `HomeScreen.kt`, `NonStreamScreen.kt` (primeras 120 líneas), `AndroidManifest.xml`, `mwdat_config.xml`, `app/build.gradle.kts`, `gradle/libs.versions.toml`, `samples/CameraAccessAndroid/GEMINI-DIRECT.md`, `.github/workflows/android-apk.yml`. No leídos (fuera del foco): `webrtc/*`, `ui/SettingsScreen.kt`, `GeminiOverlayView.kt`, `WebRTCOverlayView.kt`, `ControlsRow.kt`, `SharePhotoDialog.kt`, `openclaw/ToolCallModels.kt`, `androidTest/`.

---
Fin de la revisión. Siguiente paso: ChatGPT revisa y propone los arreglos en `gemini-direct`; esta rama no toca la app existente.
