package com.toolbari.videorecorder.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.toolbari.videorecorder.data.RecordingState
import java.util.Locale

/**
 * Screen 4: the recording finished. Shows the output file's name, size,
 * duration and resolution. [Play] opens the MP4 in the phone's video
 * player, [Share] sends it with a read grant, [Record again] goes back
 * to Options keeping the file and the options.
 */
@Composable
fun DoneScreen(
    done: RecordingState.Done,
    onRecordAgain: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Text(
            text = "Recording done",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Your video is saved in Downloads and shows in Gallery.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))

        InfoRow(label = "File name", value = done.fileName)
        Spacer(Modifier.height(12.dp))
        InfoRow(label = "Size", value = formatFileSize(done.sizeBytes))
        Spacer(Modifier.height(12.dp))
        InfoRow(label = "Duration", value = formatDuration(done.durationSeconds))
        Spacer(Modifier.height(12.dp))
        InfoRow(label = "Resolution", value = "${done.width} × ${done.height}")

        Spacer(Modifier.weight(1f))

        Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    val playIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(done.outputUri, "video/mp4")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching { context.startActivity(playIntent) }.onFailure {
                        Toast.makeText(
                            context,
                            "No app found to play this video.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Play")
            }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "video/mp4"
                        putExtra(Intent.EXTRA_STREAM, done.outputUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching {
                        context.startActivity(
                            Intent.createChooser(shareIntent, "Share video")
                        )
                    }.onFailure {
                        Toast.makeText(
                            context,
                            "Sharing is not available.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Share")
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onRecordAgain,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("Record again")
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

/** "131.2 s" style, one decimal. */
private fun formatDuration(seconds: Double): String =
    String.format(Locale.US, "%.1f s", seconds)
