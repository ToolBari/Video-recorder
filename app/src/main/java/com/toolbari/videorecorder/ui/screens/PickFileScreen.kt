package com.toolbari.videorecorder.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.toolbari.videorecorder.data.PickedFile

/**
 * Screen 1: pick the HTML file. "Choose HTML file" opens the system file
 * picker (MainActivity owns the launcher); the picked file's name + size
 * are shown, Continue goes to Options (enabled only when a file is
 * chosen), and the last 5 files can be re-selected with one tap.
 */
@Composable
fun PickFileScreen(
    pickedFile: PickedFile?,
    recentFiles: List<PickedFile>,
    errorMessage: String?,
    onPickFile: () -> Unit,
    onRecentSelected: (PickedFile) -> Unit,
    onContinue: () -> Unit,
    onClearError: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Text(
            text = "Pick an HTML file",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Choose a self-contained HTML animation. It will be recorded into an MP4 video.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onPickFile,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("Choose HTML file")
        }

        if (errorMessage != null) {
            Spacer(Modifier.height(16.dp))
            ErrorBanner(message = errorMessage, onDismiss = onClearError)
        }

        if (pickedFile != null) {
            Spacer(Modifier.height(24.dp))
            Text(
                text = "Selected file",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(pickedFile.name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = formatFileSize(pickedFile.sizeBytes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onContinue,
            enabled = pickedFile != null,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("Continue")
        }

        if (recentFiles.isNotEmpty()) {
            Spacer(Modifier.height(32.dp))
            Text(
                text = "Recent files",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(recentFiles, key = { it.uri.toString() }) { file ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onRecentSelected(file) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(file.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = formatFileSize(file.sizeBytes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** Dismissible red banner for [RecorderViewModel.errorMessage]. */
@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    }
}

/** "1.2 MB" / "48 KB" / "512 B" style. */
internal fun formatFileSize(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f MB", mb)
        kb >= 1.0 -> String.format(java.util.Locale.US, "%.0f KB", kb)
        else -> "$bytes B"
    }
}
