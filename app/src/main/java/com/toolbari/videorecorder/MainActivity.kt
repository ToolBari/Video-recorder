package com.toolbari.videorecorder

import android.Manifest
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.toolbari.videorecorder.data.RecordingState
import com.toolbari.videorecorder.data.Shape
import com.toolbari.videorecorder.ui.screens.DoneScreen
import com.toolbari.videorecorder.ui.screens.OptionsScreen
import com.toolbari.videorecorder.ui.screens.PickFileScreen
import com.toolbari.videorecorder.ui.screens.RecordingScreen
import com.toolbari.videorecorder.ui.theme.VideoRecorderTheme
import com.toolbari.videorecorder.viewmodel.RecorderViewModel
import com.toolbari.videorecorder.viewmodel.Screen
import kotlinx.coroutines.launch

/**
 * Owns the two system launchers the ViewModel cannot own: the HTML file
 * picker (OpenDocument, MIME text/html) and the MediaProjection
 * screen-capture consent intent, plus the API <= 28 storage permission
 * request (before the consent intent, when the user taps RECORD; if
 * denied, the ViewModel shows an error and recording does not start).
 * Renders the current screen from `viewModel.screen` (state-based
 * navigation, no navigation-compose). Sets `requestedOrientation` on
 * entering the Recording screen (portrait for 9:16, landscape for 16:9,
 * unspecified for 1:1) and restores it when leaving it.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: RecorderViewModel by viewModels()

    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.onFilePicked(it) } }

    private val captureConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> viewModel.onCaptureConsent(result.resultCode, result.data) }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCaptureConsent() else viewModel.onStoragePermissionDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // RECORD tapped -> storage check (API <= 28) -> capture consent.
        lifecycleScope.launch {
            viewModel.recordRequestEvent.collect { requestRecording() }
        }

        // Orientation follows the screen + shape (Recording only).
        lifecycleScope.launch {
            viewModel.screen.collect { screen ->
                requestedOrientation = when (screen) {
                    Screen.Recording -> when (viewModel.options.value.shape) {
                        Shape.NINE_16 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        Shape.SIXTEEN_9 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        Shape.ONE_1 -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                    else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        }

        // System back: Options/Done -> Pick screen; Recording -> nothing
        // (the user must use the CANCEL button); Pick -> finish the app.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (viewModel.screen.value) {
                    Screen.Options, Screen.Done -> viewModel.onDone()
                    Screen.PickFile -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                    Screen.Recording -> Unit
                }
            }
        })

        setContent {
            VideoRecorderTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val screen by viewModel.screen.collectAsState()
                    val pickedFile by viewModel.pickedFile.collectAsState()
                    val options by viewModel.options.collectAsState()
                    val recentFiles by viewModel.recentFiles.collectAsState()
                    val recordingState by viewModel.recordingState.collectAsState()
                    val lengthText by viewModel.lengthText.collectAsState()
                    val errorMessage by viewModel.errorMessage.collectAsState()
                    val cachedHtmlFile by viewModel.cachedHtmlFile.collectAsState()

                    when (screen) {
                        Screen.PickFile -> PickFileScreen(
                            pickedFile = pickedFile,
                            recentFiles = recentFiles,
                            errorMessage = errorMessage,
                            onPickFile = { pickFileLauncher.launch(arrayOf("text/html")) },
                            onRecentSelected = { viewModel.onRecentSelected(it) },
                            onContinue = { viewModel.onContinueToOptions() },
                            onClearError = { viewModel.clearError() }
                        )
                        Screen.Options -> OptionsScreen(
                            pickedFile = pickedFile,
                            options = options,
                            lengthText = lengthText,
                            errorMessage = errorMessage,
                            onShapeSelected = { viewModel.onShapeSelected(it) },
                            onSizeSelected = { viewModel.onSizeSelected(it) },
                            onLengthChanged = { viewModel.onLengthChanged(it) },
                            onRecordRequested = { viewModel.onRecordRequested() },
                            onClearError = { viewModel.clearError() }
                        )
                        Screen.Recording -> RecordingScreen(
                            htmlFile = cachedHtmlFile,
                            recordingState = recordingState,
                            totalSeconds = options.lengthSeconds,
                            onCancelRecording = { viewModel.onCancelRecording() }
                        )
                        Screen.Done -> {
                            val done = recordingState as? RecordingState.Done
                            if (done != null) {
                                DoneScreen(
                                    done = done,
                                    onRecordAgain = { viewModel.onRecordAgain() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun requestRecording() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            launchCaptureConsent()
        }
    }

    private fun launchCaptureConsent() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureConsentLauncher.launch(manager.createScreenCaptureIntent())
    }
}
