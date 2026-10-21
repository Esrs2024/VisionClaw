package com.meta.wearable.dat.externalsampleapps.cameraaccess.local

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Talks ONLY to OpenClaw on this Android device (Termux) over loopback.
 * Never forwards OpenClaw credentials to the VisionClaw hosted gateway.
 * The OpenClaw gateway must explicitly enable the Chat Completions endpoint.
 */
object LocalOpenClawClient {
    private const val BASE_URL = "http://127.0.0.1:18789"
    private const val AGENT = "openclaw/morethantiles"
    private val client = OkHttpClient.Builder()
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    private fun builder(path: String, token: String): Request.Builder {
        val request = Request.Builder().url(BASE_URL + path)
        if (token.isNotBlank()) request.header("Authorization", "Bearer " + token.trim())
        return request
    }

    suspend fun checkAgent(token: String): String = withContext(Dispatchers.IO) {
        try {
            client.newCall(builder("/v1/models", token).get().build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.code == 401 || response.code == 403) {
                    return@withContext "OpenClaw rechazó la clave local. No se ha conectado."
                }
                if (!response.isSuccessful) {
                    return@withContext "OpenClaw devolvió HTTP " + response.code + "; revisa si está activa la API /v1."
                }
                val arr = JSONObject(body).optJSONArray("data")
                val found = (0 until (arr?.length() ?: 0)).any {
                    arr?.optJSONObject(it)?.optString("id") == AGENT
                }
                if (found) "Conectado con MoreThanTiles en Termux."
                else "OpenClaw responde, pero el agente morethantiles no figura en /v1/models."
            }
        } catch (e: Exception) {
            "No se puede conectar a OpenClaw en 127.0.0.1:18789. Comprueba que Termux esté ejecutándolo."
        }
    }

    suspend fun ask(token: String, message: String): String = withContext(Dispatchers.IO) {
        require(message.isNotBlank())
        val body = JSONObject()
            .put("model", AGENT)
            .put("stream", false)
            .put("user", "visionclaw-local-morethantiles")
            .put("messages", org.json.JSONArray().put(
                JSONObject().put("role", "user").put("content", message)
            ))
            .toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        try {
            client.newCall(builder("/v1/chat/completions", token).post(body).build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.code == 401 || response.code == 403) {
                    throw IOException("Clave de OpenClaw rechazada. Revisa la clave en la aplicación.")
                }
                if (!response.isSuccessful) {
                    val json = runCatching { JSONObject(text) }.getOrNull()
                    val detail = json?.optJSONObject("error")?.optString("message")
                        ?.takeIf { it.isNotBlank() }
                    throw IOException("OpenClaw HTTP " + response.code + ": " + (detail ?: "API local no disponible"))
                }
                val json = JSONObject(text)
                val reply = json.optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content").orEmpty()
                if (reply.isBlank()) throw IOException("OpenClaw devolvió una respuesta vacía.")
                reply
            }
        } catch (e: IOException) {
            throw IOException(e.message ?: "No se pudo conectar con OpenClaw.", e)
        }
    }
}
