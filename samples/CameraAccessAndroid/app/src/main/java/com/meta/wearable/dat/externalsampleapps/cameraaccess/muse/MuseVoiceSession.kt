package com.meta.wearable.dat.externalsampleapps.cameraaccess.muse

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.speech.Speech
import com.meta.wearable.dat.speech.addSpeech
import com.meta.wearable.dat.speech.removeSpeech
import com.meta.wearable.dat.speech.types.SpeechState
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Spoken questions come from the official Meta DAT speech capability, not
 * the phone microphone. Visual data comes from the existing fullscreen DAT
 * camera pipeline. Only one question-triggered still is sent to OpenClaw.
 */
class MuseVoiceSession(
    private val context: Context,
    private val scope: CoroutineScope,
    private val frameProvider: () -> Pair<Bitmap?, Long>,
    private val onStatus: (String) -> Unit,
    private val onHeard: (String) -> Unit,
    private val onAnswer: (String) -> Unit,
) {
    private var running = false
    private var speech: Speech? = null
    private var speechOwner: DeviceSession? = null
    private var attachingSpeech = false
    private var transcriptJob: Job? = null
    private var speechStateJob: Job? = null
    private var speechErrorJob: Job? = null
    private var speechLocaleJob: Job? = null
    private var recognitionLocale = "sin confirmar"
    private var gateway: MuseGateway? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speaking = false

    fun start(deviceSession: DeviceSession, gatewayToken: String) {
        if (running) return
        if (gatewayToken.isBlank() || gatewayToken.startsWith("YOUR_")) {
            onStatus("Falta el Gateway Token de OpenClaw. No es necesario cambiar tu clave actual.")
            return
        }
        running = true
        ttsReady = false
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.forLanguageTag("es-ES")
                tts?.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            }
        }.also { engine ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    speaking = true
                }
                override fun onDone(utteranceId: String?) { 
                    speaking = false
                    if (running) scope.launch { resumeSpeech() }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    speaking = false
                    if (running) scope.launch { resumeSpeech() }
                }
            })
        }
        gateway = MuseGateway(
            onReady = { if (running) attachMetaSpeech(deviceSession) },
            onStatus = { if (running) onStatus(it) },
            onAnswer = { if (running) { onAnswer(it); sayThroughGlasses(it) } },
            onError = { if (running) { onStatus(it); scope.launch { resumeSpeech() } } },
        ).also { it.connect(gatewayToken) }
    }

    private fun attachMetaSpeech(deviceSession: DeviceSession) {
        if (!running || speech != null || attachingSpeech) return
        attachingSpeech = true
        try {
            deviceSession.addSpeech().fold(
                onSuccess = { instance ->
                    speech = instance
                    speechOwner = deviceSession
                    // Report real partial/final results BEFORE wake-word filtering.
                    // Until now the UI silently discarded every phrase that did not begin with Muse.
                    transcriptJob = scope.launch {
                        instance.transcriptions.collect { transcript ->
                            if (transcript == null || !running || speaking) return@collect
                            val words = transcript.text.trim()
                            if (words.isBlank()) return@collect
                            if (!transcript.isFinal) {
                                onStatus("Reconociendo desde las Ray-Ban: " + words.take(95))
                                return@collect
                            }
                            onHeard(words)
                            val spoken = words.lowercase(Locale.forLanguageTag("es-ES"))
                            // Keep wake-word protection: don't silently upload private images.
                            if (spoken.startsWith("muse") || spoken.contains("oye muse") ||
                                spoken.contains("hola muse")) {
                                onStatus("Reconocido: " + words.take(90) + ". Consultando…")
                                sendQuestion(words)
                            } else {
                                onStatus("Oí en las gafas: " + words.take(95) +
                                    ". Para preguntar, comienza diciendo Muse.")
                            }
                        }
                    }
                    speechStateJob = scope.launch {
                        var started = false
                        instance.state.collect { state ->
                            if (!running) return@collect
                            when (state) {
                                SpeechState.STARTED -> {
                                    started = true
                                    onStatus("Escuchando realmente desde las Ray-Ban (" +
                                        recognitionLocale + "). Di: Muse, qué ves.")
                                }
                                SpeechState.STOPPED -> {
                                    if (started && !speaking) onStatus(
                                        "El reconocimiento Ray-Ban se detuvo. Apaga y enciende AI.")
                                }
                                else -> Unit
                            }
                        }
                    }
                    speechErrorJob = scope.launch {
                        instance.errors.collect { error ->
                            if (error != null && running) {
                                onStatus("Error de reconocimiento Meta: " + error.description)
                            }
                        }
                    }
                    speechLocaleJob = scope.launch {
                        instance.locale.collect { locale ->
                            recognitionLocale = locale.toString()
                            if (running && instance.state.value == SpeechState.STARTED) {
                                onStatus("Escuchando Ray-Ban. Idioma de Meta: " +
                                    recognitionLocale + ". Di: Muse, qué ves.")
                            }
                        }
                    }
                    onStatus("Iniciando reconocimiento de voz REAL en las Ray-Ban…")
                    instance.start()
                        .onSuccess {
                            // Success only means the start was accepted. Confirm LISTENING via state.
                            onStatus("Inicio aceptado por Meta; comprobando si escucha…")
                        }
                        .onFailure { error, _ ->
                            onStatus("La voz Ray-Ban NO arrancó: " + error.description)
                        }
                },
                onFailure = { error, _ ->
                    onStatus("No se ha podido iniciar la voz de las Ray-Ban: " + error.description)
                },
            )
        } catch (error: Exception) {
            onStatus("Error de voz Meta: " + (error.message ?: "sin detalles"))
        } finally {
            attachingSpeech = false
        }
    }

    private fun sendQuestion(question: String) {
        val target = gateway ?: return
        if (!target.isConnected() || target.isBusy()) return
        val (frame, frameAt) = frameProvider()
        val age = SystemClock.elapsedRealtime() - frameAt
        if (frame == null || frameAt == 0L || age < 0L || age > 4000L) {
            onStatus("Sin imagen RECIENTE de las Ray-Ban. No se envió una imagen falsa.")
            return
        }
        val encoded = try {
            ByteArrayOutputStream().use { output ->
                val scale = 960f / maxOf(frame.width, frame.height)
                val reduced = if (scale < 1f) Bitmap.createScaledBitmap(
                    frame,
                    (frame.width * scale).toInt().coerceAtLeast(1),
                    (frame.height * scale).toInt().coerceAtLeast(1),
                    true,
                ) else frame
                try {
                    check(reduced.compress(Bitmap.CompressFormat.JPEG, 65, output))
                } finally {
                    if (reduced !== frame) reduced.recycle()
                }
                output.toByteArray()
            }
        } catch (_: Exception) {
            onStatus("No se pudo convertir el fotograma REAL de las gafas.")
            return
        }
        if (target.askWithRealGlassesImage(question, encoded)) {
            // Stop transcribing until the answer is spoken, avoiding self-echo.
            try { speech?.stop() } catch (_: Exception) {}
        }
    }

    fun askFromVisibleButton() {
        if (!running) {
            onStatus("Activa Muse primero con el botón AI.")
            return
        }
        sendQuestion("Muse, dime qué se ve en la cámara de mis gafas.")
    }

    private fun hasGlassesAudioOutput(): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
            val name = device.productName?.toString().orEmpty()
            (name.contains("Ray", true) || name.contains("Meta", true) ||
             name.startsWith("RB ", true)) &&
            (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
             device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
             device.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }

    private fun sayThroughGlasses(text: String) {
        if (!running) return
        if (!hasGlassesAudioOutput()) {
            onStatus("Muse respondió en pantalla. Android no confirma salida de audio Ray-Ban.")
            scope.launch { resumeSpeech() }
            return
        }
        if (!ttsReady) {
            onStatus("Muse respondió en pantalla, pero la voz española no está lista.")
            scope.launch { resumeSpeech() }
            return
        }
        speaking = true
        val result = tts?.speak(text.take(750), TextToSpeech.QUEUE_FLUSH, null, "visionclaw-muse")
        if (result != TextToSpeech.SUCCESS) {
            speaking = false
            onStatus("Respuesta en pantalla; el sintetizador de voz no pudo reproducirla.")
            scope.launch { resumeSpeech() }
        }
    }

    private fun resumeSpeech() {
        if (!running || speech == null || speaking) return
        try { speech?.start()?.onFailure { error, _ ->
            onStatus("No se pudo reactivar el micrófono de las gafas: " + error.description)
        } } catch (_: Exception) {
            onStatus("La voz Meta se interrumpió. Apaga y enciende el botón AI.")
        }
    }

    fun stop() {
        // Stopping recognition is NOT the same as removing the SDK capability.
        // Leaving Speech attached causes addSpeech() on the next AI toggle to fail
        // with "A capability of this type is already active on the session".
        running = false
        speaking = false
        transcriptJob?.cancel()
        speechStateJob?.cancel()
        speechErrorJob?.cancel()
        speechLocaleJob?.cancel()
        transcriptJob = null
        speechStateJob = null
        speechErrorJob = null
        speechLocaleJob = null
        recognitionLocale = "sin confirmar"
        try { speech?.stop() } catch (_: Exception) {}
        try { speechOwner?.removeSpeech() } catch (error: Exception) {
            android.util.Log.w("MuseVoiceSession", "Failed to release Meta speech capability", error)
        }
        speech = null
        speechOwner = null
        attachingSpeech = false
        gateway?.close()
        gateway = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }
}
