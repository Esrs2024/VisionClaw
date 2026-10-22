package com.morethantiles.inspector

import android.os.Handler
import android.os.Looper
import android.util.Base64
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class Gateway(private val state: (String)->Unit, private val answer: (String)->Unit) {
    private val ui=Handler(Looper.getMainLooper())
    private val client=OkHttpClient.Builder().pingInterval(20,TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS).build()
    private var socket:WebSocket?=null
    private var session="agent:main:mtt-inspector"
    private var requestId=""
    private var last=""
    @Volatile var connected=false
        private set
    private fun status(s:String){ui.post{state(s)}}
    private fun receive(s:String){ui.post{answer(s)}}
    fun connect(token:String, inspectionId:String){
        disconnect()
        if(token.isBlank()){status("Falta el token de OpenClaw local.");return}
        session="agent:main:mtt-inspector-"+inspectionId
        socket=client.newWebSocket(Request.Builder().url("ws://127.0.0.1:18789").build(),
            object:WebSocketListener(){
                override fun onMessage(webSocket:WebSocket,text:String){
                    try{
                        val packet=JSONObject(text)
                        when(packet.optString("type")){
                            "event"->when(packet.optString("event")){
                                "connect.challenge"->{
                                    val auth=JSONObject().put("token",token)
                                    val ident=JSONObject().put("id","gateway-client")
                                        .put("version","1.0.0").put("platform","android").put("mode","backend")
                                    val params=JSONObject().put("minProtocol",4).put("maxProtocol",4)
                                        .put("client",ident).put("role","operator")
                                        .put("scopes",JSONArray().put("operator.read").put("operator.write"))
                                        .put("caps",JSONArray()).put("commands",JSONArray())
                                        .put("permissions",JSONObject()).put("auth",auth).put("locale","es-ES")
                                    webSocket.send(JSONObject().put("type","req").put("id","mtt-connect")
                                        .put("method","connect").put("params",params).toString())
                                }
                                "chat"->{
                                    val p=packet.optJSONObject("payload")?:return
                                    if(p.optString("sessionKey").let{it.isNotBlank()&&it!=session})return
                                    when(p.optString("state")){
                                        "delta"->last=readText(p).ifBlank{last}
                                        "final"->{val spoken=readText(p).ifBlank{last};last=""
                                            if(spoken.isNotBlank())receive(spoken)
                                            else status("OpenClaw terminó sin respuesta.")}
                                        "error"->status("OpenClaw no pudo procesar la orden.")
                                    }
                                }
                            }
                            "res"->{
                                val id=packet.optString("id")
                                if(id=="mtt-connect"){
                                    connected=packet.optBoolean("ok") &&
                                        packet.optJSONObject("payload")?.optString("type")=="hello-ok"
                                    status(if(connected)"OpenClaw conectado. Puedes hablar."
                                        else "OpenClaw rechazó la conexión: " +
                                            (packet.optJSONObject("error")?.optString("message")?.take(180)
                                                ?: "comprueba la clave local."))
                                }else if(id==requestId&&!packet.optBoolean("ok")){
                                    status("Orden rechazada: "+(packet.optJSONObject("error")?.optString("message")?:"error"))
                                }
                            }
                        }
                    }catch(_:Exception){status("Mensaje del agente no reconocido.")}
                }
                override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?){
                    connected=false;status("Sin conexión con OpenClaw en Termux: " + (t.message ?: "sin detalles").take(130))
                }
                override fun onClosed(webSocket:WebSocket,code:Int,reason:String){connected=false}
            })
    }
    private fun readText(p:JSONObject):String{
        val arr=p.optJSONObject("message")?.optJSONArray("content")?:return ""
        return (0 until arr.length()).mapNotNull{arr.optJSONObject(it)
            ?.takeIf{j->j.optString("type")=="text"}?.optString("text")}.joinToString("\n")
    }
    fun send(words:String,jpeg:ByteArray?,inspection:Inspection){
        if(!connected){status("OpenClaw no conectado.");return}
        val prompt="Inspección profesional MoreThanTiles con imágenes REALES de Ray-Ban. "+
            "No inventes medidas, precio, cliente, IVA ni firma. Haz solo una pregunta a la vez. "+
            "Devuelve EXCLUSIVAMENTE un JSON completo con say en español, client, address y "+
            "vatRate numérico (0, 9, 21 etc) o null cuando no esté confirmado, y "+
            "works lista COMPLETA de objetos con description en neerlandés, unit, quantity "+
            "numérico o null y price numérico o null. "+
            "Cliente conocido: "+inspection.client+"; dirección: "+inspection.address+
            "; partidas conocidas: "+inspection.works.joinToString {it.description}+
            ". Orden del usuario: "+words
        val args=JSONObject().put("sessionKey",session).put("message",prompt)
            .put("deliver",false).put("idempotencyKey",UUID.randomUUID().toString())
        if(jpeg!=null){
            args.put("attachments",JSONArray().put(JSONObject().put("type","image")
                .put("mimeType","image/jpeg").put("content",Base64.encodeToString(jpeg,Base64.NO_WRAP))))
        }
        requestId=UUID.randomUUID().toString()
        val ok=socket?.send(JSONObject().put("type","req").put("id",requestId)
            .put("method","chat.send").put("params",args).toString())?:false
        status(if(ok)"Enviando imagen y observaciones a OpenClaw…"
               else "No se pudo enviar la inspección.")
    }
    /**
     * Send ONE real Meta-glasses frame to the vision-capable OpenClaw agent.
     * No simulated/phone frames, no image fallback and no app-side provider key.
     */
    fun askMuse(words: String, jpeg: ByteArray): Boolean {
        if (!connected) {
            status("OpenClaw no está conectado. No se ha enviado imagen.")
            return false
        }
        if (jpeg.isEmpty()) {
            status("No llegó imagen real de las gafas. No se envió la pregunta.")
            return false
        }
        val prompt = "MUSE VISION: La siguiente imagen es un fotograma REAL de las Ray-Ban Meta Gen 2. " +
            "Describe SOLO lo que realmente puedes identificar, en español sencillo y breve. " +
            "No inventes objetos, personas, medidas o detalles no visibles. " +
            "Si no recibes imagen, dilo explícitamente. Pregunta: " + words
        val params = JSONObject().put("sessionKey", session)
            .put("message", prompt).put("deliver", false)
            .put("idempotencyKey", UUID.randomUUID().toString())
            .put("attachments", JSONArray().put(JSONObject()
                .put("type", "image").put("mimeType", "image/jpeg")
                .put("content", Base64.encodeToString(jpeg, Base64.NO_WRAP))))
        requestId = UUID.randomUUID().toString()
        val sent = socket?.send(JSONObject().put("type", "req").put("id", requestId)
            .put("method", "chat.send").put("params", params).toString()) ?: false
        status(if (sent) "Enviada imagen real Ray-Ban a OpenClaw (Muse)." else
               "No se pudo enviar la imagen real a OpenClaw.")
        return sent
    }

    fun disconnect(){socket?.close(1000,"end");socket=null;connected=false}
}
