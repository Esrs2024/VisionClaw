package com.morethantiles.inspector

import android.Manifest
import android.app.Activity
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
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.*
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.camera.StreamSession
import com.meta.wearable.dat.camera.startStreamSession
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.camera.types.VideoQuality
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.io.ByteArrayOutputStream
import java.util.Locale

class MainActivity : ComponentActivity(), RecognitionListener {
    private lateinit var inspect: Inspection
    private lateinit var bridge: Gateway
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var preview: ImageView
    private lateinit var client: EditText
    private lateinit var address: EditText
    private lateinit var token: EditText
    private lateinit var signatureConsent: CheckBox
    private lateinit var log: TextView
    private lateinit var audio: AudioManager
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var talking = false
    private var listening = false
    private var running = false
    private var headsetTrials = 0
    private var cameraLive = false
    private var stream: StreamSession? = null
    private var videoJob: Job? = null
    private var stateJob: Job? = null
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var currentJpeg: ByteArray? = null
    private var lastFrameAt = 0L
    private var lastPhotoAt = 0L
    private var awaitingFollowup = false

    private val androidPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if(result.values.all{it}) startInspection() else show("Faltan permisos Android de cámara, voz o Bluetooth.")
    }
    private val metaPermission=registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        if(result.getOrDefault(PermissionStatus.Denied)==PermissionStatus.Granted) startVideo()
        else show("Meta no autorizó la cámara de las gafas. Vincula la aplicación con las Ray-Ban.")
    }
    private val signaturePicker=registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if(uri!=null) {
            try {
                val bytes=contentResolver.openInputStream(uri)?.use{it.readBytes()}
                if(bytes!=null && BitmapFactory.decodeByteArray(bytes,0,bytes.size)!=null){
                    inspect.saveSignature(bytes)
                    signatureConsent.isChecked=false
                    show("Firma auténtica guardada. Debes autorizar su uso en cada inspección.")
                } else show("La firma debe ser una imagen PNG o JPG legible.")
            }catch(e:Exception){show("No se pudo importar la firma: "+e.message)}
        }
    }

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        inspect=Inspection(this)
        audio=getSystemService(AUDIO_SERVICE) as AudioManager
        bridge=Gateway({s->show(s);if(s.startsWith("OpenClaw conectado"))activateAudio()},
            {s->onAgent(s)})
        makeScreen()
        tts=TextToSpeech(this){ state ->
            ttsReady=state==TextToSpeech.SUCCESS
            if(ttsReady){
                tts?.language=Locale.forLanguageTag("es-ES")
                tts?.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                tts?.setOnUtteranceProgressListener(object:UtteranceProgressListener(){
                    override fun onStart(utteranceId:String?){}
                    override fun onDone(utteranceId:String?){ui.post{talking=false;startListening()}}
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId:String?){ui.post{talking=false;startListening()}}
                })
            } else show("La voz de Android no está disponible.")
        }
    }

    private fun makeScreen(){
        val scroll=ScrollView(this)
        val box=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(35,45,35,55)
            setBackgroundColor(android.graphics.Color.rgb(249,248,245))
        }
        fun label(text:String,size:Float=16f):TextView{
            val v=TextView(this).apply{
                this.text=text; textSize=size
                setTextColor(android.graphics.Color.rgb(32,29,23))
                setPadding(0,12,0,10)
            }
            box.addView(v);return v
        }
        fun edit(hint:String):EditText{
            return EditText(this).apply{this.hint=hint;box.addView(this)}
        }
        fun button(text:String, action:()->Unit){
            box.addView(Button(this).apply{this.text=text;setOnClickListener{action()}})
        }
        label("MORETHANTILES  |  INSPECTOR",23f)
        label("Inspección con cámara real de las Ray-Ban + OpenClaw local")
        status=label("Sin iniciar. No hay confirmación de cámara ni micrófono.",14f)
        details=label("0 fotografías; 0 partidas registradas",14f)
        preview=ImageView(this).apply{
            adjustViewBounds=true
            layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,330)
            setBackgroundColor(android.graphics.Color.DKGRAY)
            scaleType=ImageView.ScaleType.FIT_CENTER
        }
        box.addView(preview)
        client=edit("Nombre del cliente (también puedes dictarlo)")
        address=edit("Dirección de la obra (también puedes dictarla)")
        token=edit("Clave de OpenClaw local (una sola vez)").apply{
            inputType=android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(SecureToken(this@MainActivity).read())
        }
        button("Vincular estas Ray-Ban a la aplicación"){
            try{Wearables.initialize(applicationContext);Wearables.startRegistration(this)}
            catch(e:Exception){show("Error de vinculación Meta: "+e.message)}
        }
        button("COMENZAR INSPECCIÓN COMPLETA"){startInspection()}
        button("CAPTURAR FOTO DE LAS GAFAS"){snapshot()}
        button("GENERAR PDF DE PRESUPUESTO"){exportPdf()}
        button("Elegir imagen de firma auténtica"){signaturePicker.launch("image/*")}
        signatureConsent=CheckBox(this).apply{
            text="Autorizo mi firma auténtica en el PDF de esta inspección"
            isChecked=false;box.addView(this)
        }
        log=label("Conversación e inspección:",13f)
        button("Terminar inspección"){stop()}
        scroll.addView(box);setContentView(scroll)
    }

    private fun show(text:String){
        runOnUiThread{status.text=text}
    }
    private fun refresh(){
        runOnUiThread{
            details.text="Cámara: "+if(cameraLive)"transmitiendo fotogramas" else "sin imagen"+
                " | Fotos: "+inspect.photos+" | Partidas: "+inspect.works.size
        }
    }
    private fun startInspection(){
        if(running){show("Inspección ya iniciada.");return}
        val needed=listOf(Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_CONNECT)
        val missing=needed.filter{ContextCompat.checkSelfPermission(this,it)!=PackageManager.PERMISSION_GRANTED}
        if(missing.isNotEmpty()){androidPermission.launch(missing.toTypedArray());return}
        running=true
        if(token.text.toString().isNotBlank()){
            SecureToken(this).write(token.text.toString().trim())
            bridge.connect(token.text.toString().trim(),inspect.dateCode)
        }else show("La cámara puede funcionar, pero falta la clave local para el agente.")
        try{
            Wearables.initialize(applicationContext)
            lifecycleScope.launch{
                try{
                    val check=Wearables.checkPermissionStatus(Permission.CAMERA)
                    if(check.getOrNull()==PermissionStatus.Granted)startVideo()
                    else metaPermission.launch(Permission.CAMERA)
                }catch(e:Exception){show("Permiso Meta: "+e.message)}
            }
        }catch(e:Exception){show("No se pudo iniciar Meta Wearables: "+e.message)}
    }

    private fun startVideo(){
        if(stream!=null)return
        try{
            val s=Wearables.startStreamSession(applicationContext,AutoDeviceSelector(),
                StreamConfiguration(videoQuality=VideoQuality.HIGH,15))
            stream=s
            show("Conectando cámara REAL Ray-Ban…")
            stateJob=lifecycleScope.launch {
                s.state.collect {state->
                    cameraLive=state==StreamSessionState.STREAMING
                    if(cameraLive)show("La cámara está transmitiendo fotogramas reales.")
                    else show("Estado de cámara Ray-Ban: "+state.toString())
                    refresh()
                }
            }
            videoJob=lifecycleScope.launch(Dispatchers.Default){
                s.videoStream.collect {frame->
                    val now=SystemClock.elapsedRealtime()
                    if(now-lastFrameAt<1500L)return@collect
                    lastFrameAt=now
                    try{
                        val w=frame.width;val h=frame.height
                        if(w<=0||h<=0||w%2!=0||h%2!=0)return@collect
                        val buf=frame.buffer.duplicate()
                        val data=ByteArray(buf.remaining())
                        buf.get(data)
                        val jpeg=toJpeg(data,w,h)
                        currentJpeg=jpeg
                        if(now-lastPhotoAt>7000L){
                            lastPhotoAt=now
                            inspect.savePhoto(jpeg)
                            refresh()
                        }
                        withContext(Dispatchers.Main){
                            preview.setImageBitmap(BitmapFactory.decodeByteArray(jpeg,0,jpeg.size))
                        }
                    }catch(e:Exception){show("No se pudo procesar un fotograma: "+e.message)}
                }
            }
        }catch(e:Exception){show("No se pudo abrir cámara Ray-Ban: "+e.message)}
    }

    private fun toJpeg(data:ByteArray,w:Int,h:Int):ByteArray{
        val y=w*h
        val chroma=y/4
        if(data.size<y+chroma*2)throw IllegalArgumentException("fotograma incompleto")
        val nv21=ByteArray(y+chroma*2)
        System.arraycopy(data,0,nv21,0,y)
        for(i in 0 until chroma){
            nv21[y+2*i]=data[y+chroma+i]
            nv21[y+2*i+1]=data[y+i]
        }
        val out=ByteArrayOutputStream()
        YuvImage(nv21,ImageFormat.NV21,w,h,null)
            .compressToJpeg(Rect(0,0,w,h),60,out)
        return out.toByteArray()
    }
    private fun snapshot(){
        val bytes=currentJpeg
        if(!cameraLive||bytes==null){show("No hay fotograma real: no se guardará una imagen falsa.");return}
        inspect.savePhoto(bytes);refresh();speak("Fotografía guardada.")
    }

    private fun activateAudio(){
        if(!running)return
        headsetTrials=0
        tryRoute()
    }
    private fun tryRoute(){
        if(!running)return
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.BLUETOOTH_CONNECT)!=
            PackageManager.PERMISSION_GRANTED){show("Falta permiso Bluetooth.");return}
        val glasses=audio.availableCommunicationDevices.firstOrNull{
            (it.type==AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
             it.type==AudioDeviceInfo.TYPE_BLE_HEADSET) &&
            (it.productName.toString().contains("Ray",true) ||
             it.productName.toString().contains("Meta",true))
        } ?: audio.availableCommunicationDevices.firstOrNull{
            it.type==AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            it.type==AudioDeviceInfo.TYPE_BLE_HEADSET
        }
        if(glasses==null){
            show("Android no expone una ruta de llamadas Bluetooth para las Ray-Ban.")
            return
        }
        audio.mode=AudioManager.MODE_IN_COMMUNICATION
        audio.setCommunicationDevice(glasses)
        if(audio.communicationDevice?.id==glasses.id){
            show("Ruta de llamadas Bluetooth activa. Escuchando instrucciones…")
            startListening()
        }else if(headsetTrials++<14){
            ui.postDelayed({tryRoute()},500)
        }else show("El audio de llamadas no se activó; no usaré el micrófono del teléfono.")
    }

    private fun startListening(){
        if(!running||talking||listening||!bridge.connected)return
        val dev=audio.communicationDevice
        if(dev==null||(dev.type!=AudioDeviceInfo.TYPE_BLUETOOTH_SCO &&
                dev.type!=AudioDeviceInfo.TYPE_BLE_HEADSET)){
            show("Micrófono Ray-Ban no disponible; reconocimiento detenido.");return
        }
        if(!SpeechRecognizer.isRecognitionAvailable(this)){
            show("No hay servicio de reconocimiento de voz Android.");return
        }
        try{
            if(recognizer==null){
                recognizer=SpeechRecognizer.createSpeechRecognizer(this)
                recognizer?.setRecognitionListener(this)
            }
            val intent=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE,"es-ES")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,false)
            listening=true
            recognizer?.startListening(intent)
            show("Escuchando instrucciones desde las Ray-Ban…")
        }catch(e:Exception){listening=false;show("Error de voz: "+e.message)}
    }
    private fun speak(s:String){
        if(!ttsReady){show(s);ui.postDelayed({startListening()},800);return}
        talking=true
        recognizer?.cancel()
        listening=false
        tts?.speak(s.take(750),TextToSpeech.QUEUE_FLUSH,null,"mtt-reply")
    }
    private fun onAgent(raw:String){
        val speech=inspect.receive(raw)
        client.setText(inspect.client)
        address.setText(inspect.address)
        log.text="Agente: "+speech
        refresh();speak(speech)
    }
    override fun onResults(results:Bundle?){
        listening=false
        val words=results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()?.trim().orEmpty()
        if(words.isBlank()){rearm();return}
        val lower=words.lowercase(Locale.forLanguageTag("es-ES"))
        if(!awaitingFollowup && listOf("oye","mira","morethantiles","inspección",
                "presupuesto","foto","guardar","pdf","firma").none{lower.contains(it)}){
            rearm();return
        }
        awaitingFollowup=true
        inspect.client=client.text.toString().ifBlank{inspect.client}
        inspect.address=address.text.toString().ifBlank{inspect.address}
        inspect.addObservation(words,currentJpeg)
        log.text="Tú: "+words
        refresh()
        if(lower.contains("generar")&&lower.contains("pdf") ||
           lower.contains("exportar")&&lower.contains("presupuesto")){
            exportPdf()
        }else if(lower.contains("hacer foto")||lower.contains("guardar foto")){
            snapshot()
        }else{
            val jpeg=if(cameraLive)currentJpeg else null
            bridge.send(words,jpeg,inspect)
        }
    }
    private fun rearm(){ui.postDelayed({startListening()},600)}
    override fun onError(error:Int){listening=false
        if(running)ui.postDelayed({startListening()},1200)}
    override fun onReadyForSpeech(params:Bundle?){}
    override fun onBeginningOfSpeech(){}
    override fun onRmsChanged(rmsdB:Float){}
    override fun onBufferReceived(buffer:ByteArray?){}
    override fun onEndOfSpeech(){}
    override fun onPartialResults(partialResults:Bundle?){}
    override fun onEvent(eventType:Int,params:Bundle?){}

    private fun exportPdf(){
        inspect.client=client.text.toString().ifBlank{inspect.client}
        inspect.address=address.text.toString().ifBlank{inspect.address}
        inspect.signatureAllowed=signatureConsent.isChecked
        try{
            val file=inspect.makePdf()
            show("PDF generado: "+file.name+
                if(inspect.readyForOfficial())" (partidas completas)" else " (BORRADOR)")
            speak(if(inspect.readyForOfficial())"Presupuesto generado. Revisa antes de enviarlo."
                else "He creado un borrador, faltan datos por confirmar.")
            val uri=FileProvider.getUriForFile(this,packageName+".fileprovider",file)
            val share=Intent(Intent.ACTION_SEND).apply{
                type="application/pdf"
                putExtra(Intent.EXTRA_STREAM,uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share,"Guardar o enviar presupuesto"))
        }catch(e:Exception){show("Error PDF: "+e.message)}
    }
    private fun stop(){
        running=false;cameraLive=false;listening=false
        videoJob?.cancel();stateJob?.cancel()
        videoJob=null;stateJob=null
        stream?.close();stream=null
        recognizer?.cancel()
        bridge.disconnect()
        audio.clearCommunicationDevice();audio.mode=AudioManager.MODE_NORMAL
        inspect.persist();refresh()
        show("Inspección guardada en el teléfono.")
    }
    override fun onDestroy(){
        stop()
        recognizer?.destroy();recognizer=null
        tts?.shutdown();tts=null
        super.onDestroy()
    }
}
