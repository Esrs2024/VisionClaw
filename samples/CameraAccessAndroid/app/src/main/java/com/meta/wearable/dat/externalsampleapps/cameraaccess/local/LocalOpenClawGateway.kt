package com.meta.wearable.dat.externalsampleapps.cameraaccess.local

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Local operator connection to the OpenClaw Gateway already running in Termux.
 * No VisionClaw cloud, Google account, remote gateway, or additional subscription.
 * A shared Gateway token must be entered once by the owner. The socket never
 * leaves 127.0.0.1 and the token is not sent to any third-party service.
 */
class LocalOpenClawGateway(
    private val onStatus: (String) -> Unit,
    private val onAnswer: (String) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var socket: WebSocket? = null
    private var connected = false
    private var sessionKey = "agent:main:visionclaw-rayban"
    private var pendingSend: String? = null
    private var lastReply = ""
    private var awaitingResponse = false

    private fun status(text: String) { main.post { onStatus(text) } }
    private fun failure(text: String) { main.post { onFailure(text) } }
    private fun answer(text: String) { main.post { onAnswer(text) } }
    fun isReady(): Boolean = connected
    fun isWaitingForAgent(): Boolean = awaitingResponse

    fun connect(token: String) {
        if (token.isBlank()) {
            failure("Falta la clave de tu OpenClaw local.")
            return
        }
        disconnect()
        status("Conectando con OpenClaw en este Xiaomi…")
        socket = http.newWebSocket(
            Request.Builder().url("ws://127.0.0.1:18789").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    status("Esperando autenticación local…")
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val packet = JSONObject(text)
                        when (packet.optString("type")) {
                            "event" -> when (packet.optString("event")) {
                                "connect.challenge" -> {
                                    // Local backend clients are allowed to authenticate using
                                    // shared-secret auth on loopback without device pairing.
                                    // Other clients must use signed device pairing instead.
                                    val params = JSONObject()
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
                                        .put("auth", JSONObject().put("token", token))
                                        .put("locale", "es-ES")
                                    webSocket.send(JSONObject().put("type", "req")
                                        .put("id", "vc-connect")
                                        .put("method", "connect")
                                        .put("params", params).toString())
                                }
                                "chat" -> {
                                    val p = packet.optJSONObject("payload") ?: return
                                    if (p.optString("sessionKey").isNotBlank() &&
                                        p.optString("sessionKey") != sessionKey) return
                                    if (p.optString("state") == "final") {
                                        awaitingResponse = false
                                        val m = p.optJSONObject("message")
                                        val parts = m?.optJSONArray("content")
                                        val output = StringBuilder()
                                        if (parts != null) {
                                            for (i in 0 until parts.length()) {
                                                val item = parts.optJSONObject(i) ?: continue
                                                if (item.optString("type") == "text") {
                                                    output.append(item.optString("text")).append("\n")
                                                }
                                            }
                                        }
                                        val spoken = output.toString().trim()
                                        if (spoken.isNotBlank()) answer(spoken)
                                        else if (lastReply.isNotBlank()) answer(lastReply)
                                        else status("OpenClaw terminó sin respuesta de voz.")
                                        lastReply = ""
                                    } else if (p.optString("state") == "error") {
                                        awaitingResponse = false
                                        failure("OpenClaw no pudo completar la tarea.")
                                    } else if (p.optString("state") == "delta") {
                                        val m = p.optJSONObject("message")
                                        val a = m?.optJSONArray("content")
                                        if (a != null) {
                                            lastReply = (0 until a.length())
                                                .mapNotNull { i -> a.optJSONObject(i)
                                                    ?.takeIf { it.optString("type") == "text" }
                                                    ?.optString("text") }
                                                .joinToString("\n")
                                        }
                                    }
                                }
                            }
                            "res" -> {
                                val id = packet.optString("id")
                                if (id == "vc-connect") {
                                    if (packet.optBoolean("ok") &&
                                        packet.optJSONObject("payload")?.optString("type") == "hello-ok") {
                                        connected = true
                                        status("OpenClaw conectado. Puedes hablar con las gafas.")
                                    } else {
                                        connected = false
                                        failure("OpenClaw ha rechazado la conexión: " +
                                            (packet.optJSONObject("error")?.optString("message")
                                                ?: "comprueba la clave local"))
                                    }
                                } else if (id == pendingSend && !packet.optBoolean("ok")) {
                                    awaitingResponse = false
                                    failure("OpenClaw rechazó la orden: " +
                                        (packet.optJSONObject("error")?.optString("message")
                                            ?: "error de agente"))
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // Never dump raw frames: they may contain personal documents.
                    }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    connected = false
                    awaitingResponse = false
                    failure("No responde OpenClaw en Termux (127.0.0.1:18789).")
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    connected = false
                    awaitingResponse = false
                    status("Conexión local cerrada.")
                }
            }
        )
    }

    fun sendVoiceCommand(words: String, imageJpegBase64: String? = null) {
        if (!connected) {
            failure("No hay conexión con OpenClaw local.")
            return
        }
        if (awaitingResponse) {
            status("Estoy terminando el presupuesto anterior.")
            return
        }
        awaitingResponse = true
        lastReply = ""
        val id = UUID.randomUUID().toString()
        pendingSend = id
        val command = """
          Estás escuchando a través de las Ray-Ban Meta Gen 2, en español.
          Responde de forma breve y hablada, sin tablas ni código.
          Si el usuario pide un presupuesto o una factura MoreThanTiles, utiliza
          la habilidad LOCAL ya instalada morethantiles-docs, no un texto simulado.
          La plantilla debe ser la de MoreThanTiles con su logotipo y condiciones.
          Redacta el PDF en neerlandés cuando sea un cliente en Países Bajos.
          Incluye la firma guardada auténtica únicamente cuando exista y esté
          autorizada; nunca inventes ni dibujes firmas. Si faltan datos reales,
          haz UNA pregunta de voz para recopilarlos. No inventes medidas, cliente,
          importes, dirección, IVA ni firma. Solo marca el documento definitivo
          y lo envía cuando el usuario confirme expresamente.
          Conserva el resultado en la carpeta MoreThanTiles/Documenten del teléfono.
          PETICIÓN POR VOZ: $words
        """.trimIndent()
        val params = JSONObject()
            .put("sessionKey", sessionKey)
            .put("message", command)
            .put("idempotencyKey", UUID.randomUUID().toString())
            .put("deliver", false)
        if (!imageJpegBase64.isNullOrBlank()) {
            // Attach a real frame from Meta DAT; never use the phone's camera
            // or a generated image as a substitute.
            params.put("attachments", JSONArray().put(JSONObject()
                .put("type", "image")
                .put("mimeType", "image/jpeg")
                .put("content", imageJpegBase64)))
        }
        val ok = socket?.send(JSONObject()
            .put("type", "req")
            .put("id", id)
            .put("method", "chat.send")
            .put("params", params).toString()) ?: false
        if (!ok) {
            awaitingResponse = false
            failure("El teléfono no ha podido enviar la orden a OpenClaw.")
        } else status("OpenClaw está preparando la respuesta…")
    }

    fun disconnect() {
        connected = false
        awaitingResponse = false
        socket?.close(1000, "Closing VisionClaw local session")
        socket = null
    }
}
