package com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini

import android.content.Context
import android.media.AudioDeviceInfo
import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream

class AudioManager {
    companion object {
        private const val TAG = "AudioManager"
        private const val MIN_SEND_BYTES = 3200 // 100ms at 16kHz mono Int16 = 1600 frames * 2 bytes
    }

    var onAudioCaptured: ((ByteArray) -> Unit)? = null

    private var systemAudio: android.media.AudioManager? = null
    private var previousAudioMode = android.media.AudioManager.MODE_NORMAL
    private var previousCommunicationDevice: AudioDeviceInfo? = null
    private var glassesAudio = false
    var onCaptureError: ((String) -> Unit)? = null

    private fun restoreRoute() {
        val manager = systemAudio ?: return
        if (glassesAudio) {
            runCatching {
                val previous = previousCommunicationDevice
                if (previous != null) manager.setCommunicationDevice(previous)
                else manager.clearCommunicationDevice()
                manager.mode = previousAudioMode
            }
        }
        glassesAudio = false
        systemAudio = null
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var captureThread: Thread? = null
    @Volatile
    private var isCapturing = false
    private val accumulatedData = ByteArrayOutputStream()
    private val accumulateLock = Any()

    @SuppressLint("MissingPermission")
    fun startCapture(context: Context, fromGlasses: Boolean) {
        if (isCapturing) return

        systemAudio = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val manager = systemAudio!!
        var glassesInput: AudioDeviceInfo? = null
        if (fromGlasses) {
            val sco = manager.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO &&
                    (it.productName.toString().contains("Ray", true) || it.productName.toString().contains("Meta", true))
            } ?: throw IllegalStateException("El audio Bluetooth de las Ray-Ban no está disponible. Comprueba su conexión de llamadas.")
            previousAudioMode = manager.mode
            previousCommunicationDevice = manager.communicationDevice
            glassesAudio = true
            manager.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
            if (!manager.setCommunicationDevice(sco)) {
                restoreRoute()
                throw IllegalStateException("Android no pudo dirigir el audio a las gafas.")
            }
            glassesInput = manager.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        }

        val bufferSize = AudioRecord.getMinBufferSize(
            GeminiConfig.INPUT_AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            if (fromGlasses) MediaRecorder.AudioSource.VOICE_RECOGNITION else MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            GeminiConfig.INPUT_AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(GeminiConfig.OUTPUT_AUDIO_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(
                AudioTrack.getMinBufferSize(
                    GeminiConfig.OUTPUT_AUDIO_SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ) * 2
            )
            .build()

        if (fromGlasses && glassesInput != null) audioRecord?.setPreferredDevice(glassesInput)
        check(audioRecord?.state == AudioRecord.STATE_INITIALIZED) { "No se pudo abrir el micrófono." }
        audioRecord?.startRecording()
        audioTrack?.play()
        isCapturing = true

        synchronized(accumulateLock) {
            accumulatedData.reset()
        }

        captureThread = Thread({
            val buffer = ByteArray(bufferSize)
            if (fromGlasses) {
                var attempts = 0
                while (isCapturing && audioRecord?.routedDevice == null && attempts++ < 20) Thread.sleep(50)
                if (audioRecord?.routedDevice?.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    onCaptureError?.invoke("No se pudo usar el micrófono de las gafas. No se utilizará el del móvil.")
                    return@Thread
                }
            }
            var tapCount = 0
            while (isCapturing) {
                if (fromGlasses && audioRecord?.routedDevice?.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    onCaptureError?.invoke("Se desconectó el micrófono de las gafas.")
                    break
                }
                val read = runCatching { audioRecord?.read(buffer, 0, buffer.size) ?: -1 }.getOrDefault(-1)
                if (read < 0) {
                    onCaptureError?.invoke("Se interrumpió el audio del micrófono.")
                    break
                }
                if (read > 0) {
                    tapCount++
                    synchronized(accumulateLock) {
                        accumulatedData.write(buffer, 0, read)
                        if (accumulatedData.size() >= MIN_SEND_BYTES) {
                            val chunk = accumulatedData.toByteArray()
                            accumulatedData.reset()
                            if (tapCount <= 3) {
                                Log.d(TAG, "Sending chunk: ${chunk.size} bytes (~${chunk.size / 32}ms)")
                            }
                            onAudioCaptured?.invoke(chunk)
                        }
                    }
                }
            }
        }, "audio-capture").also { it.start() }

        Log.d(TAG, "Audio capture started (16kHz mono PCM16)")
    }

    fun playAudio(data: ByteArray) {
        if (!isCapturing || data.isEmpty()) return
        audioTrack?.write(data, 0, data.size)
    }

    fun stopPlayback() {
        audioTrack?.pause()
        audioTrack?.flush()
        audioTrack?.play()
    }

    fun stopCapture() {
        isCapturing = false
        runCatching { audioRecord?.stop() }

        captureThread?.join(1000)
        captureThread = null

        // Flush remaining accumulated audio
        synchronized(accumulateLock) {
            if (accumulatedData.size() > 0) {
                val chunk = accumulatedData.toByteArray()
                accumulatedData.reset()
                onAudioCaptured?.invoke(chunk)
            }
        }

        runCatching { audioRecord?.release() }
        audioRecord = null

        runCatching { audioTrack?.stop() }
        runCatching { audioTrack?.release() }
        audioTrack = null

        restoreRoute()
        Log.d(TAG, "Audio capture stopped")
    }
}
