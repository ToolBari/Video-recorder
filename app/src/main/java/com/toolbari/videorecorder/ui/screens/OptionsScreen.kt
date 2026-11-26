package com.toolbari.videorecorder.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.toolbari.videorecorder.data.PickedFile
import com.toolbari.videorecorder.data.RecordingOptions
import com.toolbari.videorecorder.data.Shape
import com.toolbari.videorecorder.data.Size
import com.toolbari.videorecorder.data.resolution

/**
 * Screen 2: recording options. Shape (9:16 / 16:9 / 1:1) and size
 * (720p / 1080p) are chips; format is fixed MP4 and frame rate is fixed
 * 30 fps in v1, shown as text; Length is auto-filled from the HTML file
 * and stays editable by hand (decimals allowed). The big RECORD button
 * starts the flow (MainActivity launches the capture-consent intent).
 */
@Composable
fun OptionsScreen(
    pickedFile: PickedFile?,
    options: RecordingOptions,
    lengthText: String,
    errorMessage: String?,
    onShapeSelected: (Shape) -> Unit,
    onSizeSelected: (Size) -> Unit,
    onLengthChanged: (String) -> Unit,
    onRecordRequested: () -> Unit,
    onClearError: () -> Unit
) {
    val (outWidth, outHeight) = options.resolution()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Text(
            text = "Recording options",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        if (pickedFile != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = pickedFile.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(24.dp))

        Text(
            text = "Shape",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row {
            Shape.entries.forEach { shape ->
                FilterChip(
                    selected = options.shape == shape,
                    onClick = { onShapeSelected(shape) },
                    label = { Text(shape.label) },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }
        Spacer(Modifier.height(20.dp))

        Text(
            text = "Size",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row {
            Size.entries.forEach { size ->
                FilterChip(
                    selected = options.size == size,
                    onClick = { onSizeSelected(size) },
                    label = { Text(size.label) },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }
        Spacer(Modifier.height(20.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Format",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(text = "MP4", style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Frame rate",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(text = "30 fps", style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Output",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "$outWidth × $outHeight",
                style = MaterialTheme.typography.bodyLarge
            )
        }
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = lengthText,
            onValueChange = onLengthChanged,
            label = { Text("Length (seconds)") },
            supportingText = { Text("Auto-filled from the file; edit if needed.") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
        )

        if (errorMessage != null) {
            Spacer(Modifier.height(16.dp))
            OptionsErrorBanner(message = errorMessage, onDismiss = onClearError)
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onRecordRequested,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
        ) {
            Text(
                text = "RECORD",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** Dismissible red banner for [RecorderViewModel.errorMessage]. */
@Composable
private fun OptionsErrorBanner(message: String, onDismiss: () -> Unit) {
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
