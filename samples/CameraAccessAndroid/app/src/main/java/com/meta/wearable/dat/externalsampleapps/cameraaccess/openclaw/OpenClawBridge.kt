package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw

import android.util.Log
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class OpenClawBridge {
    companion object {
        private const val TAG = "OpenClawBridge"
        private const val MAX_HISTORY_TURNS = 10
    }

    private val _lastToolCallStatus = MutableStateFlow<ToolCallStatus>(ToolCallStatus.Idle)
    val lastToolCallStatus: StateFlow<ToolCallStatus> = _lastToolCallStatus.asStateFlow()

    private val _connectionState = MutableStateFlow<OpenClawConnectionState>(OpenClawConnectionState.NotConfigured)
    val connectionState: StateFlow<OpenClawConnectionState> = _connectionState.asStateFlow()

    fun setToolCallStatus(status: ToolCallStatus) {
        _lastToolCallStatus.value = status
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private val pingClient = OkHttpClient.Builder()
        .readTimeout(5, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .build()

    private var sessionKey: String = "agent:main:glass"
    private val conversationHistory = mutableListOf<JSONObject>()

    /**
     * A GET on the POST-only /v1/chat/completions endpoint returns 405 even when
     * the token is invalid. Never treat arbitrary 4xx responses as "Connected".
     *
     * /v1/models checks authenticated access without invoking a model. A
     * deliberately invalid chat request then checks that chat completions are
     * enabled, without executing an agent or consuming model tokens.
     */
    suspend fun checkConnection() = withContext(Dispatchers.IO) {
        if (!GeminiConfig.isOpenClawConfigured) {
            _connectionState.value = OpenClawConnectionState.NotConfigured
            return@withContext
        }
        _connectionState.value = OpenClawConnectionState.Checking

        val baseUrl = "${GeminiConfig.openClawHost}:${GeminiConfig.openClawPort}"
        val token = GeminiConfig.openClawGatewayToken

        try {
            val modelsRequest = Request.Builder()
                .url("$baseUrl/v1/models")
                .get()
                .header("Authorization", "Bearer $token")
                .build()

            val modelsReady = pingClient.newCall(modelsRequest).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val body = response.body?.string().orEmpty()
                        val valid = runCatching {
                            JSONObject(body).optJSONArray("data") != null
                        }.getOrDefault(false)
                        if (!valid) {
                            _connectionState.value = OpenClawConnectionState.Unreachable(
                                "El Gateway no devolvió una lista de agentes válida."
                            )
                        }
                        valid
                    }
                    401 -> {
                        _connectionState.value = OpenClawConnectionState.Unreachable(
                            "Gateway Token no válido. Comprueba la clave de OpenClaw en Termux."
                        )
                        false
                    }
                    403 -> {
                        _connectionState.value = OpenClawConnectionState.Unreachable(
                            "El Gateway rechazó los permisos del token."
                        )
                        false
                    }
                    404, 405 -> {
                        _connectionState.value = OpenClawConnectionState.Unreachable(
                            "La API HTTP de OpenClaw no está disponible. Revisa gateway.http.endpoints.chatCompletions.enabled."
                        )
                        false
                    }
                    else -> {
                        _connectionState.value = OpenClawConnectionState.Unreachable(
                            "OpenClaw respondió HTTP ${response.code} al verificar el acceso."
                        )
                        false
                    }
                }
            }
            if (!modelsReady) return@withContext

            // Invalid by design: no valid messages are provided, so this must
            // fail at validation before any agent request is launched.
            val probeRequest = Request.Builder()
                .url("$baseUrl/v1/chat/completions")
                .post("""{"messages":"visionclaw-endpoint-probe-not-an-array"}"""
                    .toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer $token")
                .build()

            pingClient.newCall(probeRequest).execute().use { response ->
                _connectionState.value = when (response.code) {
                    400, 422 -> OpenClawConnectionState.Connected
                    401 -> OpenClawConnectionState.Unreachable(
                        "OpenClaw rechazó el Gateway Token."
                    )
                    403 -> OpenClawConnectionState.Unreachable(
                        "No hay permisos para ejecutar el agente de OpenClaw."
                    )
                    404, 405 -> OpenClawConnectionState.Unreachable(
                        "La API de Chat Completions está desactivada. Habilita gateway.http.endpoints.chatCompletions.enabled."
                    )
                    else -> OpenClawConnectionState.Unreachable(
                        "La API de Chat Completions no se pudo verificar (HTTP ${response.code})."
                    )
                }
                Log.d(TAG, "OpenClaw chat endpoint probe HTTP ${response.code}")
            }
        } catch (e: Exception) {
            _connectionState.value = OpenClawConnectionState.Unreachable(
                "No se pudo conectar con OpenClaw: ${e.message ?: "error de red"}"
            )
            Log.d(TAG, "OpenClaw connectivity check failed: ${e.javaClass.simpleName}")
        }
    }

    fun resetSession() {
        conversationHistory.clear()
        Log.d(TAG, "Session reset (key retained: $sessionKey)")
    }

    suspend fun delegateTask(
        task: String,
        toolName: String = "execute"
    ): ToolResult = withContext(Dispatchers.IO) {
        _lastToolCallStatus.value = ToolCallStatus.Executing(toolName)

        val url = "${GeminiConfig.openClawHost}:${GeminiConfig.openClawPort}/v1/chat/completions"

        // Append user message
        conversationHistory.add(JSONObject().apply {
            put("role", "user")
            put("content", task)
        })

        // Trim history
        if (conversationHistory.size > MAX_HISTORY_TURNS * 2) {
            val trimmed = conversationHistory.takeLast(MAX_HISTORY_TURNS * 2)
            conversationHistory.clear()
            conversationHistory.addAll(trimmed)
        }

        Log.d(TAG, "Sending ${conversationHistory.size} messages in conversation")

        try {
            val messagesArray = JSONArray()
            for (msg in conversationHistory) {
                messagesArray.put(msg)
            }

            val body = JSONObject().apply {
                put("model", "openclaw")
                put("messages", messagesArray)
                put("stream", false)
            }

            val request = Request.Builder()
                .url(url)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer ${GeminiConfig.openClawGatewayToken}")
                .addHeader("Content-Type", "application/json")
                .addHeader("x-openclaw-session-key", sessionKey)
                .addHeader("x-openclaw-message-channel", "glass")
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            val statusCode = response.code
            response.close()

            if (statusCode !in 200..299) {
                Log.d(TAG, "Chat failed: HTTP $statusCode - ${responseBody.take(200)}")
                _lastToolCallStatus.value = ToolCallStatus.Failed(toolName, "HTTP $statusCode")
                return@withContext ToolResult.Failure("Agent returned HTTP $statusCode")
            }

            val json = JSONObject(responseBody)
            val choices = json.optJSONArray("choices")
            val content = choices?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "")

            if (!content.isNullOrEmpty()) {
                conversationHistory.add(JSONObject().apply {
                    put("role", "assistant")
                    put("content", content)
                })
                Log.d(TAG, "Agent result: ${content.take(200)}")
                _lastToolCallStatus.value = ToolCallStatus.Completed(toolName)
                return@withContext ToolResult.Success(content)
            }

            conversationHistory.add(JSONObject().apply {
                put("role", "assistant")
                put("content", responseBody)
            })
            Log.d(TAG, "Agent raw: ${responseBody.take(200)}")
            _lastToolCallStatus.value = ToolCallStatus.Completed(toolName)
            return@withContext ToolResult.Success(responseBody)
        } catch (e: Exception) {
            Log.e(TAG, "Agent error: ${e.message}")
            _lastToolCallStatus.value = ToolCallStatus.Failed(toolName, e.message ?: "Unknown")
            return@withContext ToolResult.Failure("Agent error: ${e.message}")
        }
    }

}
