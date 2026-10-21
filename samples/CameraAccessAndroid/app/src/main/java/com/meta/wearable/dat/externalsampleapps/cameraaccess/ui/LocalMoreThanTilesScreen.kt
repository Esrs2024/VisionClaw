package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.meta.wearable.dat.externalsampleapps.cameraaccess.local.LocalOpenClawClient
import com.meta.wearable.dat.externalsampleapps.cameraaccess.livekit.LiveKitSessionViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * A self-contained voice mode using the phone's native speech services and
 * local Termux OpenClaw, with Bluetooth communication routing when available.
 *
 * It does not depend on the hosted VisionClaw gateway or LiveKit. It does NOT
 * claim that DAT camera streaming is attached: this mode is audio+documents.
 */
@Composable
fun LocalMoreThanTilesScreen(
    wearableViewModel: WearablesViewModel,
    previewViewModel: LiveKitSessionViewModel,
    onRequestWearablesPermission: suspend (Permission) -> PermissionStatus,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val wearableState by wearableViewModel.uiState.collectAsStateWithLifecycle()
    val previewState by previewViewModel.uiState.collectAsStateWithLifecycle()
    var includePicture by remember { mutableStateOf(true) }
    var voiceErrors by remember { mutableStateOf(0) }
    var token by remember { mutableStateOf(SettingsManager.localOpenClawToken) }
    var input by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("Aquí aparecerá la respuesta de MoreThanTiles.") }
    var status by remember { mutableStateOf("Conexión local sin comprobar.") }
    var audioStatus by remember { mutableStateOf("Audio Bluetooth sin comprobar.") }
    var busy by remember { mutableStateOf(false) }
    var handsFree by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var ttsReady by remember { mutableStateOf(false) }
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }

    val handler = remember { Handler(Looper.getMainLooper()) }
    val language = remember { Locale("es", "ES") }
    val recognizerIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
    }

    // The actual camera is the SDK stream, not a simulator or phone-camera substitute.
    // Ask Meta permission before starting. No hosted gateway or LiveKit room is dialed.
    LaunchedEffect(Unit) {
        // Stop any cloud session before using the phone microphone locally.
        previewViewModel.leave()
        SettingsManager.captureSource = com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.CaptureSource.GLASSES
        wearableViewModel.startMonitoring()
    }
    LaunchedEffect(wearableState.isRegistered, wearableState.hasActiveDevice) {
        if (wearableState.isRegistered && wearableState.hasActiveDevice && !wearableState.isStreaming) {
            wearableViewModel.navigateToStreaming(onRequestWearablesPermission, quietIfUnavailable = true)
        }
    }
    LaunchedEffect(wearableState.isStreaming) {
        if (wearableState.isStreaming) previewViewModel.startLocalGlassesPreview()
    }

    fun routeAudioToGlasses(): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val bluetooth = audio.availableCommunicationDevices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
        if (bluetooth == null) {
            audioStatus = "No se detectan gafas como dispositivo Bluetooth de comunicación."
            return false
        }
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        val routed = audio.setCommunicationDevice(bluetooth)
        audioStatus = if (routed) {
            "Bluetooth de comunicación seleccionado. Confirma el audio real en las gafas."
        } else {
            "Android rechazó seleccionar el audio Bluetooth."
        }
        return routed
    }

    fun listenAgain() {
        if (!handsFree || busy || !SpeechRecognizer.isRecognitionAvailable(context)) return
        listening = true
        status = "Escuchando..."
        recognizer?.startListening(recognizerIntent)
    }

    fun speak(text: String) {
        if (!ttsReady || tts == null) {
            status = "Respuesta recibida, pero la voz del teléfono no está disponible."
            handsFree = false
            return
        }
        val spoken = text.take(2900)
        tts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "mtt-answer")
    }

    fun send(text: String) {
        if (text.isBlank() || busy) return
        busy = true
        listening = false
        input = text
        status = "Consultando MoreThanTiles en Termux..."
        scope.launch {
            try {
                val photo = if (includePicture && previewState.glassesStreaming) {
                    status = "Capturando una imagen REAL de las gafas..."
                    previewViewModel.captureLocalGlassesJpeg()
                } else null
                // A failed capture is never described to the agent as a real view.
                status = if (photo != null) "Enviando voz + imagen real a MoreThanTiles..." else
                    "Enviando texto a MoreThanTiles (sin imagen de gafas)..."
                answer = LocalOpenClawClient.ask(token, text, photo)
                status = if (photo != null) "Respuesta con imagen real de gafas." else
                    "Respuesta recibida, pero SIN imagen de las gafas."
                busy = false
                speak(answer)
                if (!ttsReady && handsFree) {
                    handler.postDelayed({ listenAgain() }, 650L)
                }
            } catch (e: Exception) {
                answer = e.message ?: "OpenClaw no ha respondido."
                status = "Error de conexión; no se ha generado ningún documento verificable."
                busy = false
                handsFree = false
            }
        }
    }

    DisposableEffect(context) {
        routeAudioToGlasses()
        val speech = if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else null
        recognizer = speech

        speech?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { listening = false }
            override fun onError(error: Int) {
                listening = false
                voiceErrors += 1
                if (voiceErrors >= 3) {
                    handsFree = false
                    status = "Tres fallos de voz seguidos. Modo manos libres detenido."
                } else if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
                    error == SpeechRecognizer.ERROR_CLIENT) {
                    handsFree = false
                    status = "El reconocimiento de voz no está disponible (error " + error + ")."
                } else if (handsFree && !busy) {
                    handler.postDelayed({ listenAgain() }, 1000L)
                }
            }
            override fun onResults(results: Bundle?) {
                listening = false
                val recognized = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty().trim()
                if (recognized.isNotBlank()) {
                    voiceErrors = 0
                    send(recognized)
                } else if (handsFree) {
                    handler.postDelayed({ listenAgain() }, 900L)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val engine = TextToSpeech(context) { result -> ttsReady = result == TextToSpeech.SUCCESS }
        engine.language = language
        engine.setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build())
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                handler.post { if (handsFree) handler.postDelayed({ listenAgain() }, 650L) }
            }
            @Deprecated("Required by Android's UtteranceProgressListener")
            override fun onError(utteranceId: String?) {
                handler.post { handsFree = false }
            }
        })
        tts = engine

        onDispose {
            handsFree = false
            handler.removeCallbacksAndMessages(null)
            speech?.cancel()
            speech?.destroy()
            engine.stop()
            engine.shutdown()
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audio.clearCommunicationDevice()
            audio.mode = AudioManager.MODE_NORMAL
        }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("MoreThanTiles · voz local", style = MaterialTheme.typography.headlineSmall)
        Text("Habla con el agente de OpenClaw instalado en este Xiaomi. Sin servidor VisionClaw externo.")
        Text(
            when {
                !wearableState.isRegistered -> "Ray-Ban: todavía no registradas en esta app."
                !wearableState.hasActiveDevice -> "Ray-Ban registradas, pero no detectadas ahora mismo."
                previewState.glassesStreaming -> "Ray-Ban: vídeo REAL de las gafas disponible."
                else -> "Ray-Ban detectadas; esperando permiso o vídeo de la cámara."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!wearableState.isRegistered) {
            val activity = context as? Activity
            OutlinedButton(
                onClick = { if (activity != null) wearableViewModel.startRegistration(activity) },
                enabled = activity != null,
            ) { Text("Conectar Ray-Ban Meta con Meta AI") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = includePicture,
                onCheckedChange = { includePicture = it },
            )
            Text(
                " Incluir una foto real de las gafas al enviar cada pregunta (si hay vídeo).",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(audioStatus, style = MaterialTheme.typography.bodySmall)
        Text(status, color = MaterialTheme.colorScheme.primary)

        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Clave local OpenClaw (nunca se envía a la nube VisionClaw)") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = {
            SettingsManager.localOpenClawToken = token
            scope.launch { status = LocalOpenClawClient.checkAgent(token) }
        }) { Text("Guardar clave y comprobar agente") }

        Button(
            enabled = !busy && SpeechRecognizer.isRecognitionAvailable(context),
            onClick = {
                if (handsFree) {
                    handsFree = false
                    recognizer?.cancel()
                    tts?.stop()
                    listening = false
                    status = "Modo manos libres detenido."
                } else {
                    routeAudioToGlasses()
                    handsFree = true
                    listenAgain()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (handsFree) "Detener conversación" else "Iniciar conversación por voz") }

        if (listening) Text("Escuchando tu voz...")
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("También puedes escribir") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        OutlinedButton(onClick = { send(input) }, enabled = !busy && input.isNotBlank()) {
            Text("Enviar a MoreThanTiles")
        }

        Text("Respuesta del agente:", style = MaterialTheme.typography.titleMedium)
        Text(answer)
        Text(
            "La app envía una foto de la cámara DAT de las Ray-Ban al agente solo " +
                "cuando la opción de foto está activada y la transmisión existe. " +
                "La voz utiliza el servicio de reconocimiento Android y la salida Bluetooth. " +
                "La creación de PDF sigue dependiendo de morethantiles-docs en Termux.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = {
            handsFree = false
            onExit()
        }) { Text("Volver a VisionClaw") }
    }
}
