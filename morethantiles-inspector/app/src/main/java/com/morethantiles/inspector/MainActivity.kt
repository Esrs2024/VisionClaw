package com.morethantiles.inspector

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.speech.Speech
import com.meta.wearable.dat.speech.addSpeech
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale

/** Genuine Ray-Ban camera and on-device speech. No phone-camera or microphone substitution. */
class MainActivity : ComponentActivity() {
    private lateinit var inspect: Inspection
    private lateinit var gateway: Gateway
    private lateinit var status: TextView
    private lateinit var info: TextView
    private lateinit var transcript: TextView
    private lateinit var preview: ImageView
    private lateinit var client: EditText
    private lateinit var address: EditText
    private lateinit var token: EditText
    private lateinit var signatureConsent: CheckBox
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speaking = false
    private var running = false
    private var session: DeviceSession? = null
    private var camera: Camera? = null
    private var speech: Speech? = null
    private var cameraStarted = false
    private var speechStarted = false
    private var useVoice = false
    private var pendingPermission: Permission? = null
    private var permissionLauncher: ActivityResultLauncher<Permission>? = null
    private var stateJob: Job? = null
    private var errorJob: Job? = null
    private var videoJob: Job? = null
    private var cameraStateJob: Job? = null
    private var voiceJob: Job? = null
    private var speechStateJob: Job? = null
    @Volatile private var lastJpeg: ByteArray? = null
    private var cameraLive = false
    private var lastFrameMillis = 0L
    private var lastPhotoMillis = 0L

    private val bluetoothPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { allowed ->
        if (allowed) startInspection()
        else show("Hace falta permiso Bluetooth para comunicar con las gafas.")
    }
    private val signaturePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null && BitmapFactory.decodeByteArray(bytes, 0, bytes.size) != null) {
                    inspect.saveSignature(bytes)
                    signatureConsent.isChecked = false
                    show("Firma auténtica guardada. Autoriza cada presupuesto por separado.")
                } else show("La firma debe ser una imagen JPG o PNG.")
            }.onFailure { show("No se pudo guardar la firma: ${it.message}") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        inspect = Inspection(this)
        gateway = Gateway(
            { text -> show(text) },
            { text -> receiveAgent(text) }
        )
        makeScreen()
        // Registration depends on the Meta AI app. Errors must never crash this UI.
        try {
            permissionLauncher = registerForActivityResult(
                Wearables.RequestPermissionContract()
            ) { result ->
                result.onSuccess { granted ->
                    val requested = pendingPermission
                    pendingPermission = null
                    if (granted != PermissionStatus.Granted) {
                        if (requested == Permission.MICROPHONE) {
                            show("Meta no dio permiso de voz. Puedes inspeccionar con la cámara.")
                            useVoice = false
                            beginSession()
                        } else show("No hay permiso de cámara de las Ray-Ban.")
                    } else when (requested) {
                        Permission.CAMERA -> verifyMicrophone()
                        Permission.MICROPHONE -> { useVoice = true; beginSession() }
                        else -> show("Permiso Meta registrado.")
                    }
                }.onFailure { error, _ ->
                    pendingPermission = null
                    show("Permiso Meta: ${error.description}")
                }
            }
        } catch (t: Throwable) {
            show("No se pudo preparar el permiso Meta: ${t.javaClass.simpleName}.")
            Log.e("MTTInspector", "Meta permission registration failed", t)
        }
        tts = TextToSpeech(this) { success ->
            ttsReady = success == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.forLanguageTag("es-ES")
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                tts?.setOnUtteranceProgressListener(object: UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        runOnUiThread { speaking = false; if (running) resumeSpeech() }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        runOnUiThread { speaking = false; if (running) resumeSpeech() }
                    }
                })
            }
        }
    }

    private fun makeScreen() {
        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(34, 34, 34, 46)
            setBackgroundColor(android.graphics.Color.rgb(250, 249, 246))
        }
        fun label(value: String, size: Float = 15f): TextView {
            val view = TextView(this).apply {
                text = value
                textSize = size
                setTextColor(android.graphics.Color.rgb(38, 32, 23))
                setPadding(0, 12, 0, 10)
            }
            column.addView(view)
            return view
        }
        fun button(value: String, action: () -> Unit) {
            column.addView(Button(this).apply { text = value; setOnClickListener { action() } })
        }
        fun field(hintText: String): EditText = EditText(this).also {
            it.hint = hintText
            column.addView(it)
        }
        label("MORETHANTILES | INSPECTOR", 22f)
        label("Cámara + voz reales de las Ray-Ban Meta • OpenClaw local")
        status = label("Sin conectar. No hay imágenes ni voz confirmadas.", 14f)
        info = label("Fotografías: 0 | Partidas: 0", 14f)
        preview = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 360)
            setBackgroundColor(android.graphics.Color.DKGRAY)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        column.addView(preview)
        client = field("Nombre del cliente")
        address = field("Dirección de obra")
        token = field("Token local de OpenClaw").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(SecureToken(this@MainActivity).read())
        }
        button("VINCULAR RAY-BAN EN META AI") {
            runCatching { Wearables.startRegistration(this) }.onFailure {
                show("No se pudo iniciar el registro de Meta: ${it.message}")
            }
        }
        button("COMENZAR INSPECCIÓN CON GAFAS") { startInspection() }
        button("GUARDAR FOTO REAL") { snapshot() }
        button("GENERAR PDF DEL PRESUPUESTO") { exportPdf() }
        button("Añadir imagen de mi firma") { signaturePicker.launch("image/*") }
        signatureConsent = CheckBox(this).apply {
            text = "Autorizo mi firma para ESTE presupuesto"
            isChecked = false
            column.addView(this)
        }
        transcript = label("Conversación: sin iniciar", 14f)
        button("FINALIZAR INSPECCIÓN") { stopInspection() }
        scroll.addView(column)
        setContentView(scroll)
    }

    private fun show(message: String) {
        runOnUiThread { if (::status.isInitialized) status.text = message }
    }
    private fun updateInfo() {
        runOnUiThread {
            if (::info.isInitialized) info.text =
                "Cámara: ${if (cameraLive) "fotogramas reales" else "sin imagen"}" +
                    " | Fotos: ${inspect.photos} | Partidas: ${inspect.works.size}" +
                    " | Voz Meta: ${if (speechStarted) "activa" else "no disponible"}"
        }
    }

    private fun startInspection() {
        if (running) { show("Inspección ya iniciada."); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        running = true
        client.text?.toString()?.takeIf { it.isNotBlank() }?.let { inspect.client = it }
        address.text?.toString()?.takeIf { it.isNotBlank() }?.let { inspect.address = it }
        val secret = token.text.toString().trim()
        if (secret.isNotBlank()) {
            runCatching { SecureToken(this).write(secret) }
                .onFailure { show("No se pudo guardar la clave local.") }
            gateway.connect(secret, inspect.dateCode)
        } else show("Falta el token de OpenClaw; la inspección visual puede iniciarse.")
        verifyCamera()
    }

    private fun verifyCamera() {
        lifecycleScope.launch {
            try {
                Wearables.checkPermissionStatus(Permission.CAMERA)
                    .onSuccess { permission ->
                        if (permission == PermissionStatus.Granted) verifyMicrophone()
                        else requestMeta(Permission.CAMERA)
                    }
                    .onFailure { error, _ -> show("Meta cámara: ${error.description}") }
            } catch (t: Throwable) { show("Error al consultar cámara Meta: ${t.message}") }
        }
    }

    private fun verifyMicrophone() {
        lifecycleScope.launch {
            try {
                Wearables.checkPermissionStatus(Permission.MICROPHONE)
                    .onSuccess { permission ->
                        if (permission == PermissionStatus.Granted) {
                            useVoice = true
                            beginSession()
                        } else requestMeta(Permission.MICROPHONE)
                    }
                    .onFailure { error, _ ->
                        useVoice = false
                        show("Micrófono Meta: ${error.description}. Iniciando solo cámara.")
                        beginSession()
                    }
            } catch (t: Throwable) {
                useVoice = false
                show("Voz Meta no disponible. Iniciando cámara.")
                beginSession()
            }
        }
    }

    private fun requestMeta(permission: Permission) {
        val launcher = permissionLauncher
        if (launcher == null) {
            show("Meta no está disponible. Revisa el modo desarrollador en Meta AI.")
            return
        }
        pendingPermission = permission
        runCatching { launcher.launch(permission) }
            .onFailure { show("No se pudo solicitar permiso Meta: ${it.message}") }
    }

    private fun beginSession() {
        if (!running || session != null) return
        try {
            Wearables.createSession(AutoDeviceSelector()).fold(
                onSuccess = { connected ->
                    session = connected
                    stateJob = lifecycleScope.launch {
                        connected.state.collect { state ->
                            if (state == DeviceSessionState.STARTED) attachCapabilities(connected)
                            if (state == DeviceSessionState.STOPPED) {
                                cameraLive = false
                                show("Sesión de las gafas finalizada.")
                                updateInfo()
                            }
                        }
                    }
                    errorJob = lifecycleScope.launch {
                        connected.errors.collect { error -> show("Sesión Meta: ${error.description}") }
                    }
                    show("Conectando con las Ray-Ban...")
                    connected.start()
                },
                onFailure = { error, _ -> show("Ray-Ban: ${error.description}") }
            )
        } catch (t: Throwable) {
            show("Error de conexión con las gafas: ${t.javaClass.simpleName}: ${t.message}")
            Log.e("MTTInspector", "start session error", t)
        }
    }

    private fun attachCapabilities(connected: DeviceSession) {
        if (!cameraStarted) {
            cameraStarted = true
            try {
                connected.addCamera(
                    StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 15)
                ).fold(
                    onSuccess = { acquired ->
                        camera = acquired
                        videoJob = lifecycleScope.launch(Dispatchers.Default) {
                            acquired.stream.videoStream.collect { frame ->
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastFrameMillis < 1700) return@collect
                                lastFrameMillis = now
                                try {
                                    val w = frame.width; val h = frame.height
                                    if (w <= 0 || h <= 0 || w % 2 != 0 || h % 2 != 0) return@collect
                                    val buffer = frame.buffer.duplicate()
                                    val bytes = ByteArray(buffer.remaining())
                                    buffer.get(bytes)
                                    val jpeg = i420ToJpeg(bytes, w, h)
                                    lastJpeg = jpeg
                                    cameraLive = true
                                    if (now - lastPhotoMillis > 19000) {
                                        lastPhotoMillis = now
                                        inspect.savePhoto(jpeg)
                                    }
                                    withContext(Dispatchers.Main) {
                                        preview.setImageBitmap(
                                            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                                        )
                                        updateInfo()
                                    }
                                } catch (e: Exception) {
                                    show("Problema al recibir imagen Ray-Ban: ${e.message}")
                                }
                            }
                        }
                        cameraStateJob = lifecycleScope.launch {
                            acquired.stream.state.collect { state ->
                                show("Cámara Ray-Ban: ${state}")
                            }
                        }
                        acquired.stream.start()
                            .onFailure { error, _ -> show("Cámara Meta: ${error.description}") }
                    },
                    onFailure = { error, _ ->
                        cameraStarted = false
                        show("No se pudo abrir la cámara: ${error.description}")
                    }
                )
            } catch (t: Throwable) {
                cameraStarted = false
                show("Error de cámara real Meta: ${t.message}")
            }
        }
        if (useVoice && !speechStarted) {
            speechStarted = true
            try {
                connected.addSpeech().fold(
                    onSuccess = { activeSpeech ->
                        speech = activeSpeech
                        voiceJob = lifecycleScope.launch {
                            activeSpeech.transcriptions.collect { result ->
                                if (result != null && result.isFinal && result.text.isNotBlank()) {
                                    withContext(Dispatchers.Main) { onSpeech(result.text) }
                                }
                            }
                        }
                        activeSpeech.start()
                            .onFailure { error, _ ->
                                speechStarted = false
                                show("Reconocimiento de voz Meta: ${error.description}")
                            }
                        updateInfo()
                    },
                    onFailure = { error, _ ->
                        speechStarted = false
                        show("Voz Ray-Ban no iniciada: ${error.description}")
                        updateInfo()
                    }
                )
            } catch (t: Throwable) {
                speechStarted = false
                show("Error de voz Meta: ${t.message}")
            }
        }
    }

    private fun i420ToJpeg(input: ByteArray, width: Int, height: Int): ByteArray {
        val area = width * height
        val chroma = area / 4
        require(input.size >= area + chroma * 2) { "fotograma YUV incompleto" }
        val nv21 = ByteArray(area + 2 * chroma)
        System.arraycopy(input, 0, nv21, 0, area)
        for (i in 0 until chroma) {
            nv21[area + i * 2] = input[area + chroma + i]
            nv21[area + i * 2 + 1] = input[area + i]
        }
        return ByteArrayOutputStream().use { out ->
            YuvImage(nv21, ImageFormat.NV21, width, height, null)
                .compressToJpeg(Rect(0, 0, width, height), 65, out)
            out.toByteArray()
        }
    }

    private fun snapshot() {
        val real = lastJpeg
        if (!cameraLive || real == null) {
            show("Sin imagen de Ray-Ban. No se guardarán fotografías simuladas.")
            return
        }
        inspect.savePhoto(real)
        updateInfo()
        say("Fotografía real guardada.")
    }

    private fun onSpeech(words: String) {
        if (!running || speaking) return
        val command = words.trim()
        if (command.isBlank()) return
        transcript.text = "Tú: $command"
        inspect.client = client.text.toString().ifBlank { inspect.client }
        inspect.address = address.text.toString().ifBlank { inspect.address }
        val photo = if (cameraLive) lastJpeg else null
        inspect.addObservation(command, photo)
        updateInfo()
        val lower = command.lowercase(Locale.forLanguageTag("es-ES"))
        when {
            lower.contains("guardar foto") || lower.contains("hacer foto") -> snapshot()
            lower.contains("generar presupuesto") || lower.contains("generar pdf") ||
                lower.contains("exportar presupuesto") -> exportPdf(fromVoice = true)
            else -> gateway.send(command, photo, inspect)
        }
    }

    private fun receiveAgent(message: String) {
        runOnUiThread {
            val response = inspect.receive(message)
            client.setText(inspect.client)
            address.setText(inspect.address)
            transcript.text = "Agente: $response"
            updateInfo()
            say(response)
        }
    }

    private fun say(message: String) {
        if (!running || message.isBlank()) return
        val sound = getSystemService(AUDIO_SERVICE) as AudioManager
        val hasGlassesOutput = try {
            sound.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                val name = it.productName?.toString().orEmpty()
                (name.contains("Meta", true) || name.contains("Ray", true) ||
                  name.startsWith("RB ", true)) &&
                    (it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                     it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                     it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
            }
        } catch (_: Exception) { false }
        if (!hasGlassesOutput) {
            show("Respuesta en pantalla. Android no confirma salida de audio Ray-Ban.")
            return
        }
        if (!ttsReady) {
            show("Sí hay respuesta, pero el lector de voz de Android no está listo.")
            return
        }
        speaking = true
        try { speech?.stop() } catch (_: Exception) {}
        tts?.speak(message.take(700), TextToSpeech.QUEUE_FLUSH, null, "morethantiles-reply")
    }

    private fun resumeSpeech() {
        if (!running || !useVoice) return
        try {
            speech?.start()?.onFailure { error, _ -> show("No se reanudó voz: ${error.description}") }
        } catch (t: Throwable) { show("No se pudo reanudar voz Meta: ${t.message}") }
    }

    private fun exportPdf(fromVoice: Boolean = false) {
        inspect.client = client.text.toString().ifBlank { inspect.client }
        inspect.address = address.text.toString().ifBlank { inspect.address }
        inspect.signatureAllowed = signatureConsent.isChecked
        try {
            val file = inspect.makePdf()
            show("PDF ${if (inspect.readyForOfficial()) "completo" else "borrador"} guardado: ${file.name}")
            if (fromVoice) {
                say("Presupuesto guardado en el teléfono. Revisa los datos antes de enviarlo.")
            } else {
                val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(share, "Guardar o compartir presupuesto"))
            }
        } catch (e: Exception) { show("Error al crear PDF: ${e.message}") }
    }

    private fun stopInspection() {
        running = false
        cameraLive = false
        speechStarted = false
        cameraStarted = false
        stateJob?.cancel()
        errorJob?.cancel()
        videoJob?.cancel()
        voiceJob?.cancel()
        cameraStateJob?.cancel()
        speechStateJob?.cancel()
        try { speech?.stop() } catch (_: Exception) {}
        try { camera?.stop() } catch (_: Exception) {}
        try { session?.stop() } catch (_: Exception) {}
        speech = null
        camera = null
        session = null
        gateway.disconnect()
        runCatching { inspect.persist() }
        updateInfo()
        show("Inspección guardada. Fotos y presupuesto permanecen en el móvil.")
    }

    override fun onDestroy() {
        stopInspection()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}
