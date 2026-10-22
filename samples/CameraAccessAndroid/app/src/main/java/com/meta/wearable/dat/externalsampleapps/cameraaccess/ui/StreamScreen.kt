/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlinx.coroutines.launch
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiSessionViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamingMode
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.webrtc.WebRTCSessionViewModel

@Composable
fun StreamScreen(
    wearablesViewModel: WearablesViewModel,
    onRequestWearablesPermission: suspend (Permission) -> PermissionStatus,
    isPhoneMode: Boolean = false,
    modifier: Modifier = Modifier,
    streamViewModel: StreamViewModel =
        viewModel(
            factory =
                StreamViewModel.Factory(
                    application = (LocalActivity.current as ComponentActivity).application,
                    wearablesViewModel = wearablesViewModel,
                ),
        ),
    geminiViewModel: GeminiSessionViewModel = viewModel(),
    webrtcViewModel: WebRTCSessionViewModel = viewModel(),
) {
    val streamUiState by streamViewModel.uiState.collectAsStateWithLifecycle()
    val geminiUiState by geminiViewModel.uiState.collectAsStateWithLifecycle()
    val webrtcUiState by webrtcViewModel.uiState.collectAsStateWithLifecycle()
    val museUiState by streamViewModel.museUiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    // Wire Gemini VM to Stream VM for frame forwarding
    LaunchedEffect(geminiViewModel) {
        streamViewModel.geminiViewModel = geminiViewModel
    }

    // Wire WebRTC VM to Stream VM for frame forwarding
    LaunchedEffect(webrtcViewModel) {
        streamViewModel.webrtcViewModel = webrtcViewModel
    }

    // Start stream or phone camera
    LaunchedEffect(isPhoneMode) {
        if (isPhoneMode) {
            geminiViewModel.streamingMode = StreamingMode.PHONE
            streamViewModel.startPhoneCamera(lifecycleOwner)
        } else {
            geminiViewModel.streamingMode = StreamingMode.GLASSES
            streamViewModel.startStream()
        }
    }

    // Clean up on exit
    DisposableEffect(Unit) {
        onDispose {
            geminiViewModel.stopSession()
            webrtcViewModel.stopSession()
            streamViewModel.stopMuse()
            streamViewModel.stopStream()
        }
    }

    // Show errors as toasts
    LaunchedEffect(geminiUiState.errorMessage) {
        geminiUiState.errorMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            geminiViewModel.clearError()
        }
    }
    LaunchedEffect(webrtcUiState.errorMessage) {
        webrtcUiState.errorMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            webrtcViewModel.clearError()
        }
    }
    LaunchedEffect(museUiState.error) {
        museUiState.error?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            streamViewModel.clearMuseError()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Video feed
        streamUiState.videoFrame?.let { videoFrame ->
            Image(
                bitmap = videoFrame.asImageBitmap(),
                contentDescription = stringResource(R.string.live_stream),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }

        if (streamUiState.streamSessionState == StreamState.STARTING) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // Overlays + controls
        Box(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            // Top overlays (below status bar)
            Column(modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(top = 8.dp)) {
                // Preserve the existing full-screen camera and top overlay.
                // The same AI button now connects to Muse through local OpenClaw.
                if (museUiState.active) {
                    Text(
                        text = "Muse · " + museUiState.status,
                        color = Color.White,
                        maxLines = 2,
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.6f)).padding(8.dp),
                    )
                    if (museUiState.answer.isNotBlank()) {
                        Text(
                            text = museUiState.answer.take(180),
                            color = Color.White,
                            maxLines = 3,
                            modifier = Modifier.padding(top = 4.dp)
                                .background(Color.Black.copy(alpha = 0.6f)).padding(8.dp),
                        )
                    }
                } else if (geminiUiState.isGeminiActive) {
                    GeminiOverlay(uiState = geminiUiState)
                }

                // WebRTC overlay
                if (webrtcUiState.isActive) {
                    Spacer(modifier = Modifier.height(4.dp))
                    WebRTCOverlay(uiState = webrtcUiState)
                }
            }

            // Controls at bottom
            ControlsRow(
                onStopStream = {
                    streamViewModel.stopMuse()
                    if (geminiUiState.isGeminiActive) geminiViewModel.stopSession()
                    if (webrtcUiState.isActive) webrtcViewModel.stopSession()
                    streamViewModel.stopStream()
                    wearablesViewModel.navigateToDeviceSelection()
                },
                onCapturePhoto = { streamViewModel.capturePhoto() },
                onToggleAI = {
                    if (museUiState.active) {
                        streamViewModel.stopMuse()
                    } else if (isPhoneMode) {
                        Toast.makeText(context, "Muse necesita la cámara real de las gafas.", Toast.LENGTH_LONG).show()
                    } else {
                        scope.launch {
                            try {
                                val permission = Wearables.checkPermissionStatus(Permission.MICROPHONE).getOrNull()
                                val granted = permission == PermissionStatus.Granted ||
                                    onRequestWearablesPermission(Permission.MICROPHONE) == PermissionStatus.Granted
                                if (granted) streamViewModel.startMuse()
                                else Toast.makeText(context,
                                    "Meta no dio permiso al micrófono de las Ray-Ban.", Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                Toast.makeText(context,
                                    "No se pudo autorizar la voz Meta: " + (e.message ?: "error"),
                                    Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                },
                isAIActive = museUiState.active,
                onToggleLive = {
                    if (webrtcUiState.isActive) {
                        webrtcViewModel.stopSession()
                    } else {
                        webrtcViewModel.startSession()
                    }
                },
                isLiveActive = webrtcUiState.isActive,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    // Share photo dialog
    streamUiState.capturedPhoto?.let { photo ->
        if (streamUiState.isShareDialogVisible) {
            SharePhotoDialog(
                photo = photo,
                onDismiss = { streamViewModel.hideShareDialog() },
                onShare = { bitmap ->
                    streamViewModel.sharePhoto(bitmap)
                    streamViewModel.hideShareDialog()
                },
            )
        }
    }
}
