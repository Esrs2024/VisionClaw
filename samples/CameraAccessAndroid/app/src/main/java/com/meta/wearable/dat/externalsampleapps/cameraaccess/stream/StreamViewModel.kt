/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.SystemClock
import android.util.Log
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiSessionViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.muse.MuseVoiceSession
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.phone.PhoneCameraManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.webrtc.WebRTCSessionViewModel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MuseUiState(
    val active: Boolean = false,
    val status: String = "",
    val heard: String = "",
    val answer: String = "",
    val error: String? = null,
)

class StreamViewModel(
    application: Application,
    private val wearablesViewModel: WearablesViewModel,
) : AndroidViewModel(application) {

  companion object {
    private const val TAG = "StreamViewModel"
    private val INITIAL_STATE = StreamUiState()
  }

  private val deviceSelector: DeviceSelector = wearablesViewModel.deviceSelector
  private var streamSession: Stream? = null
  private var deviceSession: DeviceSession? = null
  private var camera: Camera? = null
  private var sessionJob: Job? = null
  private var sessionErrors: Job? = null
  private var streamErrors: Job? = null

  private val _uiState = MutableStateFlow(INITIAL_STATE)
  val uiState: StateFlow<StreamUiState> = _uiState.asStateFlow()

  private var videoJob: Job? = null
  private var stateJob: Job? = null

  // VisionClaw additions
  var geminiViewModel: GeminiSessionViewModel? = null
  var webrtcViewModel: WebRTCSessionViewModel? = null
  private var phoneCameraManager: PhoneCameraManager? = null
  private var lastRealGlassesFrameAt = 0L
  private val _museUiState = MutableStateFlow(MuseUiState())
  val museUiState: StateFlow<MuseUiState> = _museUiState.asStateFlow()
  private val museVoiceSession = MuseVoiceSession(
      context = getApplication(),
      scope = viewModelScope,
      frameProvider = { _uiState.value.videoFrame to lastRealGlassesFrameAt },
      onStatus = { message -> _museUiState.update { it.copy(status = message) } },
      onHeard = { words -> _museUiState.update { it.copy(heard = words) } },
      onAnswer = { text -> _museUiState.update { it.copy(answer = text, status = "Respuesta de OpenClaw recibida.") } },
  )

  fun startMuse() {
    if (_museUiState.value.active) return
    val currentSession = deviceSession
    if (currentSession == null || _uiState.value.streamingMode != StreamingMode.GLASSES ||
        _uiState.value.streamSessionState != StreamState.STREAMING) {
      _museUiState.update { it.copy(error = "Muse necesita vídeo REAL activo de tus Ray-Ban. No se utilizará la cámara del Xiaomi.") }
      return
    }
    val token = SettingsManager.openClawGatewayToken
    if (token.isBlank() || token.startsWith("YOUR_")) {
      _museUiState.update { it.copy(error = "Falta el Gateway Token de OpenClaw en Ajustes de VisionClaw. Puedes usar tu clave actual.") }
      return
    }
    _museUiState.value = MuseUiState(active = true, status = "Conectando Muse con OpenClaw local…")
    museVoiceSession.start(currentSession, token)
  }

  fun stopMuse() {
    museVoiceSession.stop()
    _museUiState.value = MuseUiState()
  }

  fun clearMuseError() {
    _museUiState.update { it.copy(error = null) }
  }

  fun startStream() {
    stopStream()
    _uiState.update { it.copy(streamingMode = StreamingMode.GLASSES, streamSessionState = StreamState.STARTING) }
    sessionJob = viewModelScope.launch {
      try {
        val created = Wearables.createSession(deviceSelector)
            .onFailure { error, _ -> wearablesViewModel.setRecentError("No se pudo abrir la sesión: " + error.description) }
            .getOrNull() ?: run { stopStream(); return@launch }
        deviceSession = created
        sessionErrors = viewModelScope.launch {
          created.errors.collect { error ->
            wearablesViewModel.setRecentError("Gafas: " + error.description)
            stopStream()
            wearablesViewModel.navigateToDeviceSelection()
          }
        }
        created.start()
        // withTimeout throws TimeoutCancellationException (a CancellationException).
        // Handle timeout explicitly so the created device session is always released.
        val state = withTimeoutOrNull(20_000L) {
          created.state.first { it == DeviceSessionState.STARTED || it == DeviceSessionState.STOPPED }
        }
        if (state == null) {
          wearablesViewModel.setRecentError(
              "Las gafas no iniciaron la sesión en 20 segundos. Sesión cerrada; comprueba Meta AI y vuelve a intentarlo."
          )
          stopStream()
          wearablesViewModel.navigateToDeviceSelection()
          return@launch
        }
        check(state == DeviceSessionState.STARTED) { "Las gafas cerraron la sesión antes de iniciarla." }
        val addedCamera = created.addCamera(
            StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 15, compressVideo = false)
        ).onFailure { error, _ ->
          wearablesViewModel.setRecentError("Cámara de las gafas: " + error.description)
        }.getOrNull() ?: run { stopStream(); return@launch }
        camera = addedCamera
        val active = addedCamera.stream
        streamSession = active
        StreamingService.start(getApplication())
        videoJob = viewModelScope.launch {
          try {
            active.videoStream.collect { frame -> handleVideoFrame(frame) }
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            // Never leave a frozen last frame on screen while claiming STREAMING.
            Log.e(TAG, "Failed to process a real Ray-Ban video frame", e)
            wearablesViewModel.setRecentError(
                "El vídeo real de las gafas se interrumpió: ${e.message ?: "no se pudo convertir el fotograma"}. Vuelve a conectar."
            )
            stopStream()
            wearablesViewModel.navigateToDeviceSelection()
          }
        }
        streamErrors = viewModelScope.launch {
          active.errorStream.collect { error ->
            wearablesViewModel.setRecentError("Vídeo de las gafas: " + error.description)
            stopStream()
            wearablesViewModel.navigateToDeviceSelection()
          }
        }
        stateJob = viewModelScope.launch {
          var wasActive = false
          active.state.collect { current ->
            _uiState.update { it.copy(streamSessionState = current) }
            val terminal = current == StreamState.STOPPED || current == StreamState.CLOSED
            if (!terminal) wasActive = true
            if (wasActive && terminal) {
              stopStream()
              wearablesViewModel.navigateToDeviceSelection()
            }
          }
        }
        active.start().onFailure { error, _ ->
          wearablesViewModel.setRecentError("No se pudo iniciar la cámara real: " + error.description)
          stopStream()
          wearablesViewModel.navigateToDeviceSelection()
        }
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        wearablesViewModel.setRecentError("No se pudo iniciar la sesión real de las gafas. Revisa la autorización y las actualizaciones en Meta AI.")
        stopStream()
        wearablesViewModel.navigateToDeviceSelection()
      }
    }
  }

  fun startPhoneCamera(lifecycleOwner: LifecycleOwner) {
    val manager = PhoneCameraManager(getApplication())
    phoneCameraManager = manager

    manager.onFrameCaptured = { bitmap ->
      _uiState.update { it.copy(videoFrame = bitmap) }
      // Forward to Gemini (throttled inside the VM)
      geminiViewModel?.sendVideoFrameIfThrottled(bitmap)
      // Forward to WebRTC (every frame)
      webrtcViewModel?.pushVideoFrame(bitmap)
    }

    _uiState.update {
      it.copy(
        streamingMode = StreamingMode.PHONE,
        streamSessionState = StreamState.STREAMING,
      )
    }
    manager.start(lifecycleOwner)
    Log.d(TAG, "Phone camera mode started")
  }

  fun stopStream() {
    stopMuse()
    lastRealGlassesFrameAt = 0L
    // Stop foreground service
    StreamingService.stop(getApplication())

    videoJob?.cancel()
    videoJob = null
    stateJob?.cancel()
    stateJob = null
    streamErrors?.cancel()
    streamErrors = null
    sessionErrors?.cancel()
    sessionErrors = null
    sessionJob?.cancel()
    sessionJob = null
    camera?.close()
    camera = null
    streamSession = null
    deviceSession?.stop()
    deviceSession = null
    phoneCameraManager?.stop()
    phoneCameraManager = null
    _uiState.update { INITIAL_STATE }
  }

  fun capturePhoto() {
    if (uiState.value.isCapturing) {
      Log.d(TAG, "Photo capture already in progress, ignoring request")
      return
    }

    if (uiState.value.streamSessionState == StreamState.STREAMING) {
      // Phone mode: capture current video frame as photo
      if (uiState.value.streamingMode == StreamingMode.PHONE) {
        uiState.value.videoFrame?.let { frame ->
          _uiState.update { it.copy(capturedPhoto = frame, isShareDialogVisible = true) }
        }
        return
      }

      Log.d(TAG, "Starting photo capture")
      _uiState.update { it.copy(isCapturing = true) }

      viewModelScope.launch {
        try {
          val currentStream = streamSession
          if (currentStream == null) {
            wearablesViewModel.setRecentError("No hay cámara Ray-Ban activa para hacer la fotografía.")
          } else {
            currentStream.capturePhoto()
                .onSuccess { photoData ->
                  Log.d(TAG, "Photo capture received")
                  handlePhotoData(photoData)
                }
                .onFailure { error, _ ->
                  Log.e(TAG, "Photo capture failed: ${error.description}")
                  wearablesViewModel.setRecentError(
                      "No se pudo hacer la foto con las gafas: ${error.description}"
                  )
                }
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.e(TAG, "Failed to decode captured Ray-Ban photo", e)
          wearablesViewModel.setRecentError(
              "La foto de las gafas no se pudo procesar. Inténtalo de nuevo."
          )
        } finally {
          _uiState.update { it.copy(isCapturing = false) }
        }
      }
    } else {
      Log.w(
          TAG,
          "Cannot capture photo: stream not active (state=${uiState.value.streamSessionState})",
      )
    }
  }

  fun showShareDialog() {
    _uiState.update { it.copy(isShareDialogVisible = true) }
  }

  fun hideShareDialog() {
    _uiState.update { it.copy(isShareDialogVisible = false) }
  }

  fun sharePhoto(bitmap: Bitmap) {
    val context = getApplication<Application>()
    val imagesFolder = File(context.cacheDir, "images")
    try {
      imagesFolder.mkdirs()
      val file = File(imagesFolder, "shared_image.png")
      FileOutputStream(file).use { stream ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
      }

      val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
      val intent = Intent(Intent.ACTION_SEND)
      intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
      intent.putExtra(Intent.EXTRA_STREAM, uri)
      intent.type = "image/png"
      intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

      val chooser = Intent.createChooser(intent, "Share Image")
      chooser.flags = Intent.FLAG_ACTIVITY_NEW_TASK
      context.startActivity(chooser)
    } catch (e: IOException) {
      Log.e("StreamViewModel", "Failed to share photo", e)
    }
  }

  private fun handleVideoFrame(videoFrame: VideoFrame) {
    if (videoFrame.isCompressed) {
      throw IllegalStateException(
          "La cámara envió vídeo comprimido, aunque se solicitó vídeo sin comprimir."
      )
    }
    // The conversion below needs tightly packed, even-dimension I420.
    // Validate size before copying to avoid silently cancelling the collector.
    val width = videoFrame.width
    val height = videoFrame.height
    require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) {
      "Dimensiones I420 no válidas: ${width}x${height}"
    }
    val expectedBytes = width.toLong() * height.toLong() * 3 / 2
    require(expectedBytes <= Int.MAX_VALUE && videoFrame.buffer.remaining() >= expectedBytes) {
      "Fotograma I420 incompleto (${videoFrame.buffer.remaining()} bytes, esperados $expectedBytes)"
    }
    val byteArray = ByteArray(expectedBytes.toInt())
    videoFrame.buffer.duplicate().get(byteArray)

    // Convert I420 to NV21 format which is supported by Android's YuvImage
    val nv21 = convertI420toNV21(byteArray, videoFrame.width, videoFrame.height)
    val image = YuvImage(nv21, ImageFormat.NV21, videoFrame.width, videoFrame.height, null)
    val out =
        ByteArrayOutputStream().use { stream ->
          check(image.compressToJpeg(Rect(0, 0, videoFrame.width, videoFrame.height), 50, stream)) {
            "Falló la conversión de imagen Ray-Ban a JPEG"
          }
          stream.toByteArray()
        }

    val bitmap = BitmapFactory.decodeByteArray(out, 0, out.size)
        ?: throw IllegalStateException("No se pudo decodificar el fotograma de las Ray-Ban")
    // Mark a frame fresh only when its bitmap was decoded successfully.
    lastRealGlassesFrameAt = SystemClock.elapsedRealtime()
    _uiState.update { it.copy(videoFrame = bitmap) }

    // Forward to Gemini (throttled inside the VM)
    geminiViewModel?.sendVideoFrameIfThrottled(bitmap)
    // Forward to WebRTC (every frame)
    webrtcViewModel?.pushVideoFrame(bitmap)
  }

  // Convert I420 (YYYYYYYY:UUVV) to NV21 (YYYYYYYY:VUVU)
  private fun convertI420toNV21(input: ByteArray, width: Int, height: Int): ByteArray {
    val output = ByteArray(input.size)
    val size = width * height
    val quarter = size / 4

    input.copyInto(output, 0, 0, size) // Y is the same

    for (n in 0 until quarter) {
      output[size + n * 2] = input[size + quarter + n] // V first
      output[size + n * 2 + 1] = input[size + n] // U second
    }
    return output
  }

  private fun handlePhotoData(photo: PhotoData) {
    val capturedPhoto =
        when (photo) {
          is PhotoData.Bitmap -> photo.bitmap
          is PhotoData.HEIC -> {
            val data = photo.data.duplicate()
            val byteArray = ByteArray(data.remaining())
            data.get(byteArray)

            // Extract EXIF transformation matrix and apply to bitmap
            val exifInfo = getExifInfo(byteArray)
            val transform = getTransform(exifInfo)
            decodeHeic(byteArray, transform)
          }
        }
    if (capturedPhoto == null) {
      wearablesViewModel.setRecentError(
          "La cámara envió una foto que Android no pudo abrir. Inténtalo de nuevo."
      )
      return
    }
    _uiState.update { it.copy(capturedPhoto = capturedPhoto, isShareDialogVisible = true) }
  }

  // HEIC Decoding with EXIF transformation
  private fun decodeHeic(heicBytes: ByteArray, transform: Matrix): Bitmap? {
    val bitmap = BitmapFactory.decodeByteArray(heicBytes, 0, heicBytes.size) ?: return null
    return applyTransform(bitmap, transform)
  }

  private fun getExifInfo(heicBytes: ByteArray): ExifInterface? {
    return try {
      ByteArrayInputStream(heicBytes).use { inputStream -> ExifInterface(inputStream) }
    } catch (e: IOException) {
      Log.w(TAG, "Failed to read EXIF from HEIC", e)
      null
    }
  }

  private fun getTransform(exifInfo: ExifInterface?): Matrix {
    val matrix = Matrix()

    if (exifInfo == null) {
      return matrix // Identity matrix (no transformation)
    }

    when (
        exifInfo.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    ) {
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_180 -> {
        matrix.postRotate(180f)
      }
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
        matrix.postScale(1f, -1f)
      }
      ExifInterface.ORIENTATION_TRANSPOSE -> {
        matrix.postRotate(90f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_90 -> {
        matrix.postRotate(90f)
      }
      ExifInterface.ORIENTATION_TRANSVERSE -> {
        matrix.postRotate(270f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_270 -> {
        matrix.postRotate(270f)
      }
      ExifInterface.ORIENTATION_NORMAL,
      ExifInterface.ORIENTATION_UNDEFINED -> {
        // No transformation needed
      }
    }

    return matrix
  }

  private fun applyTransform(bitmap: Bitmap, matrix: Matrix): Bitmap {
    if (matrix.isIdentity) {
      return bitmap
    }

    return try {
      val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
      if (transformed != bitmap) {
        bitmap.recycle()
      }
      transformed
    } catch (e: OutOfMemoryError) {
      Log.e(TAG, "Failed to apply transformation due to memory", e)
      bitmap
    }
  }

  override fun onCleared() {
    super.onCleared()
    stopStream()
    stateJob?.cancel()
  }

  class Factory(
      private val application: Application,
      private val wearablesViewModel: WearablesViewModel,
  ) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      if (modelClass.isAssignableFrom(StreamViewModel::class.java)) {
        @Suppress("UNCHECKED_CAST", "KotlinGenericsCast")
        return StreamViewModel(
            application = application,
            wearablesViewModel = wearablesViewModel,
        )
            as T
      }
      throw IllegalArgumentException("Unknown ViewModel class")
    }
  }
}
