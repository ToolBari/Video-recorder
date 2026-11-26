package com.toolbari.videorecorder.ui.screens

import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.toolbari.videorecorder.data.RecordingState
import com.toolbari.videorecorder.engine.WebViewSetup
import java.io.File
import java.util.Locale

/**
 * Screen 3: recording in progress. The full-screen WebView here is a
 * PREVIEW only — it loads the same picked file and plays it normally so
 * the user can watch. The recording WebView lives in the service's
 * Presentation on the virtual display and is the deliverable; it is never
 * touched from this screen.
 *
 * The preview is sized to fit the phone screen (not the target
 * resolution) via [WebViewSetup.configure]. It is destroyed in
 * DisposableEffect.onDispose when the screen is left, so no WebView (and
 * no playing page) outlives the screen.
 */
@Composable
fun RecordingScreen(
    htmlFile: File?,
    recordingState: RecordingState,
    totalSeconds: Double,
    onCancelRecording: () -> Unit
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx().toInt() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx().toInt() }

    // Keyed on the screen size: if the orientation changes (MainActivity
    // sets it per shape), the preview is rebuilt at the new size. The old
    // WebView is destroyed by the DisposableEffect below.
    val previewWebView = remember(screenWidthPx, screenHeightPx) {
        WebView(context).apply {
            WebViewSetup.configure(this, screenWidthPx, screenHeightPx)
            if (htmlFile != null) {
                WebViewSetup.loadHtmlFile(this, htmlFile)
            }
        }
    }
    DisposableEffect(previewWebView) {
        onDispose {
            previewWebView.destroy()
        }
    }

    val elapsedSeconds = (recordingState as? RecordingState.Recording)?.elapsedSeconds ?: 0.0
    val progress = if (totalSeconds > 0) {
        (elapsedSeconds / totalSeconds).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            AndroidView(
                factory = { previewWebView },
                modifier = Modifier.fillMaxSize()
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${formatSeconds(elapsedSeconds)} / ${formatSeconds(totalSeconds)}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(16.dp))
                Button(
                    onClick = onCancelRecording,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("CANCEL")
                }
            }
        }
    }
}

/** "12.5s" style, one decimal. */
private fun formatSeconds(seconds: Double): String =
    String.format(Locale.US, "%.1fs", seconds)
