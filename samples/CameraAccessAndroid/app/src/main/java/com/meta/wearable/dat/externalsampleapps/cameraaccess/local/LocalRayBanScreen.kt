package com.meta.wearable.dat.externalsampleapps.cameraaccess.local

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.livekit.LiveKitSessionViewModel
import java.io.ByteArrayOutputStream
import java.util.Locale
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Save the local OpenClaw operator secret with an Android Keystore AES key. */
private class LocalTokenStore(private val context: Context) {
    private val alias = "visionclaw-local-gateway-key"
    private val prefs = context.getSharedPreferences("visionclaw_local_openclaw", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    fun read(): String = try {
        val raw = prefs.getString("encrypted_token", null) ?: return ""
        val data = Base64.decode(raw, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        String(c.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8)
    } catch (_: Exception) { "" }
    fun write(value: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val result = c.iv + c.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("encrypted_token", Base64.encodeToString(result, Base64.NO_WRAP)).apply()
    }
}

private class RayBanVoiceController(
    private val context: Context,
    private val captureFrame: ((Bitmap?) -> Unit) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onTranscript: (String) -> Unit,
) : RecognitionListener {
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var recognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var running = false
    private var listening = false
    private var speaking = false
    private var activeToken = ""
    private var awaitingFollowup = false
    private var voiceReady = false
    // Android accepts the Bluetooth routing request before the device change
    // becomes visible. Treat the request as pending instead of reporting failure.
    private var requestedAudioDeviceId: Int? = null
    private var requestedAudioAtMs = 0L

    private val gateway = LocalOpenClawGateway(
        onStatus = { status ->
            onStatus(status)
            if (status.startsWith("OpenClaw conectado") && running) startRecognition()
        },
        onAnswer = { response ->
            awaitingFollowup = true
            onTranscript(response)
            say(response)
        },
        onFailure = { error ->
            onStatus(error)
            // A missing/invalid gateway token is not a voice failure.
            if (running) say(error)
        },
    )

    fun start(token: String) {
        if (running && activeToken == token && gateway.isReady()) return
        stop()
        running = true
        activeToken = token
        if (!routeGlassesAudio()) {
            running = false
            return
        }
        textToSpeech = TextToSpeech(context) { ready ->
            voiceReady = ready == TextToSpeech.SUCCESS
            if (voiceReady) {
                textToSpeech?.language = Locale.forLanguageTag("es-ES")
                textToSpeech?.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            }
        }.also { tts ->
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    main.post {
                        speaking = false
                        if (running) startRecognition()
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    main.post {
                        speaking = false
                        if (running) startRecognition()
                    }
                }
            })
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onStatus("Este Xiaomi no tiene reconocimiento de voz Android disponible.")
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { it.setRecognitionListener(this) }
        gateway.connect(token)
    }

    private fun hasGlassesAudioRoute(): Boolean {
        val selected = audio.communicationDevice ?: return false
        return selected.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            selected.type == AudioDeviceInfo.TYPE_BLE_HEADSET
    }

    private fun routeGlassesAudio(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED) {
            onStatus("Falta permiso Bluetooth. Actívalo en Ajustes > Apps > MoreThanTiles Gafas.")
            return false
        }
        val bluetoothDevices = audio.availableCommunicationDevices.filter {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
        if (bluetoothDevices.isEmpty()) {
            onStatus("El Xiaomi no ve las Ray-Ban para llamadas. En Bluetooth, activa Llamadas telefónicas.")
            return false
        }
        val headset = bluetoothDevices.firstOrNull {
            it.productName.toString().contains("ray-ban", ignoreCase = true) ||
                it.productName.toString().contains("meta", ignoreCase = true)
        } ?: bluetoothDevices.first()
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        if (audio.communicationDevice?.id == headset.id) return true
        val now = SystemClock.elapsedRealtime()
        if (requestedAudioDeviceId == headset.id && now - requestedAudioAtMs < 2500L) {
            // A successful setCommunicationDevice request is asynchronous.
            return true
        }
        val accepted = try {
            audio.setCommunicationDevice(headset)
        } catch (e: Exception) {
            false
        }
        if (accepted) {
            requestedAudioDeviceId = headset.id
            requestedAudioAtMs = now
            onStatus("Activando micrófono Bluetooth de las Ray-Ban…")
        } else {
            onStatus("Android rechazó el audio de llamadas. Comprueba Bluetooth > Ray-Ban > Llamadas.")
        }
        return accepted
    }

    private fun startRecognition() {
        if (!running || !gateway.isReady() || speaking || listening ||
            gateway.isWaitingForAgent()) return
        if (!routeGlassesAudio()) {
            main.postDelayed({ if (running) startRecognition() }, 2000)
            return
        }
        // Never record using the Xiaomi microphone while the Ray-Ban call
        // route is still negotiating. The audio switch is asynchronous.
        if (!hasGlassesAudioRoute()) {
            if (SystemClock.elapsedRealtime() - requestedAudioAtMs > 9000L) {
                onStatus("El audio Ray-Ban no se ha activado. Comprueba Bluetooth > Llamadas.")
            }
            main.postDelayed({ if (running) startRecognition() }, 400)
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) {
            onStatus("Falta el permiso del micrófono.")
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        try {
            listening = true
            recognizer?.startListening(intent)
            onStatus("Escuchando desde las Ray-Ban. Di: Oye, prepárame un presupuesto…")
        } catch (_: Exception) {
            listening = false
            main.postDelayed({ if (running) startRecognition() }, 2000)
        }
    }

    private fun say(text: String) {
        listening = false
        recognizer?.cancel()
        speaking = true
        val short = text.trim().take(850)
        if (voiceReady && short.isNotBlank()) {
            textToSpeech?.speak(short, TextToSpeech.QUEUE_FLUSH, null, "claw-reply")
        } else {
            speaking = false
            main.postDelayed({ if (running) startRecognition() }, 750)
        }
    }

    override fun onResults(results: Bundle?) {
        listening = false
        val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()?.trim().orEmpty()
        if (heard.isBlank()) {
            main.postDelayed({ if (running) startRecognition() }, 500)
            return
        }
        // Hands-free wake phrase, with a natural follow-up after a question.
        val lower = heard.lowercase(Locale.forLanguageTag("es-ES"))
        if (!awaitingFollowup && !listOf("oye", "morethantiles", "openclaw",
                "presupuesto", "factura").any { lower.contains(it) }) {
            main.postDelayed({ if (running) startRecognition() }, 350)
            return
        }
        awaitingFollowup = false
        onTranscript("Tú: $heard")
        onStatus("Recibiendo imagen REAL de las Ray-Ban…")
        captureFrame { bitmap ->
            // The DAT callback may be off the UI thread.
            val b64 = bitmap?.let {
                ByteArrayOutputStream().use { bytes ->
                    val scale = 960f / maxOf(it.width, it.height)
                    val image = if (scale < 1f) Bitmap.createScaledBitmap(
                        it, (it.width * scale).toInt(), (it.height * scale).toInt(), true
                    ) else it
                    image.compress(Bitmap.CompressFormat.JPEG, 65, bytes)
                    if (image !== it) image.recycle()
                    Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
                }
            }
            main.post { if (running) gateway.sendVoiceCommand(heard, b64) }
        }
    }

    override fun onError(error: Int) {
        listening = false
        if (!running || speaking) return
        val delayMs = if (error == SpeechRecognizer.ERROR_NO_MATCH ||
            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) 500L else 1800L
        // Don't permanently stop after three voice timeouts.
        main.postDelayed({ if (running) startRecognition() }, delayMs)
    }
    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    fun stop() {
        running = false
        listening = false
        speaking = false
        main.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        gateway.disconnect()
        requestedAudioDeviceId = null
        requestedAudioAtMs = 0L
        audio.clearCommunicationDevice()
        audio.mode = AudioManager.MODE_NORMAL
    }
}

@Composable
fun LocalRayBanScreen(startCapture: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { LocalTokenStore(context) }
    var token by remember { mutableStateOf(store.read()) }
    var editing by remember { mutableStateOf(token.isBlank()) }
    var input by remember { mutableStateOf(token) }
    var status by remember { mutableStateOf("Preparando conexión local…") }
    var transcript by remember { mutableStateOf("") }
    val camera: LiveKitSessionViewModel = viewModel()
    val cameraState by camera.uiState.collectAsStateWithLifecycle()
    val voice = remember(camera) {
        RayBanVoiceController(context, { callback -> camera.captureGlassesFrame(callback) },
            { status = it }, { transcript = it })
    }

    LaunchedEffect(startCapture) { if (startCapture) camera.startPreview() }
    LaunchedEffect(token) {
        if (token.isNotBlank()) voice.start(token)
    }
    DisposableEffect(voice) {
        onDispose {
            voice.stop()
            camera.leave()
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("MoreThanTiles · Ray-Ban", style = MaterialTheme.typography.headlineMedium)
            Text(if (cameraState.glassesStreaming) "Cámara real de las gafas conectada"
                 else "Conectando cámara Ray-Ban…")
            Text(status, style = MaterialTheme.typography.bodyLarge)
            if (transcript.isNotBlank()) Text(transcript)
            Spacer(Modifier.height(8.dp))
            if (editing) {
                Text("Conexión a TU OpenClaw de Termux. No se utiliza Google ni VisionClaw Cloud.")
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Clave local de OpenClaw (solo una vez)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        store.write(input.trim())
                        token = input.trim()
                        editing = false
                    },
                    enabled = input.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Conectar OpenClaw local") }
            } else {
                Button(onClick = { voice.start(token) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Reactivar voz en las gafas")
                }
                OutlinedButton(
                    onClick = { voice.stop(); input = token; editing = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Cambiar conexión local") }
            }
            Text(
                "Ejemplo: «Oye MoreThanTiles, prepara un presupuesto para 12 m² de azulejo. " +
                    "Pregúntame lo que falte y genera mi PDF con la plantilla».",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
