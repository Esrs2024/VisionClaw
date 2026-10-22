package com.meta.wearable.dat.externalsampleapps.cameraaccess.muse

import android.os.Handler
import android.os.Looper
import android.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local OpenClaw bridge. The only remote AI provider is the one configured in
 * the owner's Termux Gateway; this application never stores a Muse API key.
 * No camera fallback, no mock frames, no periodic image uploads.
 */
class MuseGateway(
    private val onReady: () -> Unit,
    private val onStatus: (String) -> Unit,
    private val onAnswer: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var socket: WebSocket? = null
    @Volatile private var connected = false
    @Volatile private var busy = false
    private var responseId: String? = null
    private var lastReply = ""
    private val session = "agent:main:visionclaw-original-muse"

    private fun status(v: String) = main.post { onStatus(v) }
    private fun error(v: String) = main.post { onError(v) }
    private fun answer(v: String) = main.post { onAnswer(v) }
    fun isConnected(): Boolean = connected
    fun isBusy(): Boolean = busy

    fun connect(gatewayToken: String) {
        close()
        if (gatewayToken.isBlank() || gatewayToken.startsWith("YOUR_")) {
            error("Falta el Gateway Token de OpenClaw. Usa el que ya tienes, sin cambiarlo.")
            return
        }
        status("Conectando VisionClaw con OpenClaw local…")
        socket = client.newWebSocket(
            Request.Builder().url("ws://127.0.0.1:18789").build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val event = JSONObject(text)
                        when (event.optString("type")) {
                            "event" -> when (event.optString("event")) {
                                "connect.challenge" -> {
                                    val handshake = JSONObject()
                                        .put("minProtocol", 4).put("maxProtocol", 4)
                                        .put("client", JSONObject()
                                            .put("id", "gateway-client")
                                            .put("version", "1.0.0")
                                            .put("platform", "android")
                                            .put("mode", "backend"))
                                        .put("role", "operator")
                                        .put("scopes", JSONArray()
                                            .put("operator.read").put("operator.write"))
                                        .put("caps", JSONArray())
                                        .put("commands", JSONArray())
                                        .put("permissions", JSONObject())
                                        .put("auth", JSONObject().put("token", gatewayToken))
                                        .put("locale", "es-ES")
                                    webSocket.send(JSONObject().put("type", "req")
                                        .put("id", "muse-conn")
                                        .put("method", "connect")
                                        .put("params", handshake).toString())
                                }
                                "chat" -> {
                                    val p = event.optJSONObject("payload") ?: return
                                    val sk = p.optString("sessionKey")
                                    if (sk.isNotBlank() && sk != session) return
                                    when (p.optString("state")) {
                                        "delta" -> {
                                            val spoken = extractText(p)
                                            if (spoken.isNotBlank()) lastReply = spoken
                                        }
                                        "final" -> {
                                            busy = false
                                            val spoken = extractText(p).ifBlank { lastReply }
                                            lastReply = ""
                                            if (spoken.isNotBlank()) answer(spoken)
                                            else error("OpenClaw terminó sin respuesta del modelo.")
                                        }
                                        "error" -> {
                                            busy = false
                                            error("OpenClaw informó de un error de agente o modelo.")
                                        }
                                    }
                                }
                            }
                            "res" -> when (event.optString("id")) {
                                "muse-conn" -> {
                                    connected = event.optBoolean("ok") &&
                                        event.optJSONObject("payload")?.optString("type") == "hello-ok"
                                    if (connected) {
                                        status("OpenClaw conectado. Di tu pregunta mirando con las gafas.")
                                        main.post { onReady() }
                                    } else {
                                        val why = event.optJSONObject("error")?.optString("message") ?: "revisa la conexión"
                                        error("OpenClaw rechazó la conexión: " + why.take(160))
                                    }
                                }
                                responseId -> if (!event.optBoolean("ok")) {
                                    busy = false
                                    val why = event.optJSONObject("error")?.optString("message") ?: "error"
                                    error("El agente no aceptó la imagen: " + why.take(160))
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // Never print a raw packet: requests contain private image data.
                    }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    connected = false
                    busy = false
                    error("OpenClaw no responde en Termux (127.0.0.1:18789).")
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    connected = false
                    busy = false
                    status("Conexión OpenClaw cerrada.")
                }
            }
        )
    }

    /**
     * Only an explicitly spoken question triggers a single real Ray-Ban photo.
     * Muse Spark must be selected separately in OpenClaw; a prompt cannot
     * secretly change the provider.
     */
    fun askWithRealGlassesImage(question: String, jpeg: ByteArray): Boolean {
        if (!connected) {
            error("OpenClaw no está conectado.")
            return false
        }
        if (busy) {
            status("Esperando la respuesta anterior…")
            return false
        }
        if (jpeg.isEmpty()) {
            error("La cámara de las gafas no ha enviado una imagen válida.")
            return false
        }
        val instruction = "Estás respondiendo en español a una pregunta hecha con unas Ray-Ban Meta Gen 2. " +
            "La fotografía adjunta es un fotograma real tomado ahora con las gafas. " +
            "Describe sólo lo visible; no afirmes ver vídeo continuo si sólo recibes una foto. " +
            "Da respuestas breves, naturales y habladas. " +
            "Si se piden acciones, comprueba que estén disponibles antes de prometerlas. " +
            "PREGUNTA: " + question
        val payload = JSONObject()
            .put("sessionKey", session)
            .put("message", instruction)
            .put("deliver", false)
            .put("idempotencyKey", UUID.randomUUID().toString())
            .put("attachments", JSONArray().put(JSONObject()
                .put("type", "image")
                .put("mimeType", "image/jpeg")
                .put("content", Base64.encodeToString(jpeg, Base64.NO_WRAP))))
        val id = UUID.randomUUID().toString()
        responseId = id
        busy = true
        val ok = socket?.send(JSONObject()
            .put("type", "req")
            .put("id", id)
            .put("method", "chat.send")
            .put("params", payload).toString()) ?: false
        if (!ok) {
            busy = false
            error("No se pudo enviar la fotografía de las Ray-Ban a OpenClaw.")
        } else status("Imagen REAL enviada. Esperando a Muse…")
        return ok
    }

    private fun extractText(payload: JSONObject): String {
        val content = payload.optJSONObject("message")?.optJSONArray("content") ?: return ""
        return (0 until content.length()).mapNotNull { i ->
            content.optJSONObject(i)
                ?.takeIf { it.optString("type") == "text" }
                ?.optString("text")
        }.joinToString("\n").trim()
    }

    fun close() {
        connected = false
        busy = false
        responseId = null
        lastReply = ""
        socket?.close(1000, "VisionClaw stop")
        socket = null
    }
}
