package com.toolbari.videorecorder.viewmodel

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.toolbari.videorecorder.data.PickedFile
import com.toolbari.videorecorder.data.RecentFilesStore
import com.toolbari.videorecorder.data.RecordingOptions
import com.toolbari.videorecorder.data.RecordingState
import com.toolbari.videorecorder.data.Shape
import com.toolbari.videorecorder.data.Size
import com.toolbari.videorecorder.engine.DurationDetector
import com.toolbari.videorecorder.engine.RecordingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToLong

/** Navigation is state-based: MainActivity renders `when (screen)`. */
sealed class Screen {
    data object PickFile : Screen()
    data object Options : Screen()
    data object Recording : Screen()
    data object Done : Screen()
}

/**
 * Single ViewModel for the whole flow. It is an AndroidViewModel because
 * file picking needs the Application's ContentResolver (name/size lookup,
 * persistable URI permission) and cache dir, and recents need
 * SharedPreferences.
 *
 * Duration auto-fill: runs when a file is picked (text scan first, then the
 * headless-WebView timeline). It never overwrites a length the user typed
 * by hand ([lengthManuallyEdited]).
 */
class RecorderViewModel(application: Application) : AndroidViewModel(application) {

    private val recentFilesStore = RecentFilesStore(application)

    private val _screen = MutableStateFlow<Screen>(Screen.PickFile)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _pickedFile = MutableStateFlow<PickedFile?>(null)
    val pickedFile: StateFlow<PickedFile?> = _pickedFile.asStateFlow()

    private val _options = MutableStateFlow(RecordingOptions())
    val options: StateFlow<RecordingOptions> = _options.asStateFlow()

    private val _recentFiles = MutableStateFlow(recentFilesStore.get())
    val recentFiles: StateFlow<List<PickedFile>> = _recentFiles.asStateFlow()

    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    private val _lengthText = MutableStateFlow(formatLength(RecordingOptions().lengthSeconds))
    val lengthText: StateFlow<String> = _lengthText.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** The cached copy of the picked HTML (cacheDir/html/<name>). The
     * RecordingScreen's preview WebView loads this file. */
    private val _cachedHtmlFile = MutableStateFlow<File?>(null)
    val cachedHtmlFile: StateFlow<File?> = _cachedHtmlFile.asStateFlow()

    /** One-shot event: MainActivity collects this and launches the system
     * screen-capture consent intent when the user taps RECORD. */
    private val _recordRequestEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val recordRequestEvent: SharedFlow<Unit> = _recordRequestEvent.asSharedFlow()

    /** Set the first time the user edits the Length field by hand; the
     * duration auto-fill never overwrites a hand-typed length. */
    private var lengthManuallyEdited = false

    init {
        // Mirror the service state and drive navigation from its terminal states.
        viewModelScope.launch {
            RecordingService.state.collect { state ->
                _recordingState.value = state
                when (state) {
                    is RecordingState.Done -> _screen.value = Screen.Done
                    is RecordingState.Error -> {
                        _errorMessage.value = state.message
                        _screen.value = Screen.Options
                    }
                    is RecordingState.Cancelled -> _screen.value = Screen.Options
                    else -> Unit // Idle / Recording: stay on the current screen.
                }
            }
        }
    }

    /** Picked from the system file picker. Resolves name/size, takes a
     * persistable URI permission, copies the HTML into the cache dir, adds
     * it to recents, and runs duration detection. */
    fun onFilePicked(uri: Uri) {
        viewModelScope.launch { loadFile(uri) }
    }

    /** A recent file was tapped: re-load it through the same path as a fresh
     * pick (the stored URI is the original content URI, permission held). */
    fun onRecentSelected(file: PickedFile) {
        viewModelScope.launch { loadFile(file.uri) }
    }

    /** Continue on the Pick screen: go to Options once a file is chosen. */
    fun onContinueToOptions() {
        if (_pickedFile.value != null) {
            _screen.value = Screen.Options
        }
    }

    fun onShapeSelected(shape: Shape) {
        _options.value = _options.value.copy(shape = shape)
    }

    fun onSizeSelected(size: Size) {
        _options.value = _options.value.copy(size = size)
    }

    /** The Length field is editable by hand. A valid number updates the
     * options; invalid text keeps the last valid value (the field still
     * shows what the user typed so they can correct it). */
    fun onLengthChanged(text: String) {
        lengthManuallyEdited = true
        _lengthText.value = text
        val parsed = text.replace(',', '.').toDoubleOrNull()
        if (parsed != null && parsed > 0) {
            _options.value = _options.value.copy(lengthSeconds = parsed)
        }
    }

    /** User tapped RECORD. MainActivity observes [recordRequestEvent] and
     * launches the capture-consent intent (after the API ≤ 28 storage
     * permission check). */
    fun onRecordRequested() {
        if (_pickedFile.value == null || _cachedHtmlFile.value == null) {
            _errorMessage.value = "Choose an HTML file first."
            return
        }
        if (_options.value.lengthSeconds <= 0) {
            _errorMessage.value = "Enter a recording length greater than zero."
            return
        }
        _recordRequestEvent.tryEmit(Unit)
    }

    /** Result of the system screen-capture consent dialog. Starts the
     * recording service and navigates to Recording. */
    fun onCaptureConsent(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            _errorMessage.value = "Screen capture permission was denied."
            return
        }
        val htmlFile = _cachedHtmlFile.value
        if (htmlFile == null) {
            _errorMessage.value = "The HTML file is no longer available. Pick it again."
            return
        }
        val intent = RecordingService.startIntent(
            getApplication(), htmlFile, _options.value, resultCode, data
        )
        getApplication<Application>().startForegroundService(intent)
        _screen.value = Screen.Recording
    }

    /** User tapped CANCEL while recording: stop the service (it deletes the
     * partial file) and go back to Options. */
    fun onCancelRecording() {
        val intent = RecordingService.cancelIntent(getApplication())
        getApplication<Application>().startService(intent)
        _screen.value = Screen.Options
    }

    /** "Record again" on the Done screen: back to Options, keeping the file
     * and the options. */
    fun onRecordAgain() {
        _screen.value = Screen.Options
    }

    /** Back to the Pick screen (e.g. system back from Options/Done). The
     * picked file is kept, so Continue still works. */
    fun onDone() {
        _screen.value = Screen.PickFile
    }

    /** Called by MainActivity when the API ≤ 28 storage permission request
     * was denied: show the error and do not start recording. */
    fun onStoragePermissionDenied() {
        _errorMessage.value = "Storage permission is needed to save the video on Android 8 and 9."
    }

    fun clearError() {
        _errorMessage.value = null
    }

    private suspend fun loadFile(uri: Uri) {
        val app = getApplication<Application>()
        val resolver = app.contentResolver

        // 1. Resolve name + size via OpenableColumns.
        val (displayName, sizeBytes) = withContext(Dispatchers.IO) {
            var name: String? = null
            var size: Long = 0L
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex)
                    if (sizeIndex >= 0) size = cursor.getLong(sizeIndex)
                }
            }
            name to size
        }
        val safeName = (displayName ?: "animation.html")
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .ifBlank { "animation.html" }

        // 2. Take a persistable read permission so recent files stay usable.
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Already held, or not grantable for this URI — the copy below
            // will fail loudly if we really cannot read it.
        }

        // 3. Copy the HTML into the cache dir and load the copy from there.
        val htmlDir = File(app.cacheDir, "html").apply { mkdirs() }
        val cached = File(htmlDir, safeName)
        val copied = withContext(Dispatchers.IO) {
            try {
                resolver.openInputStream(uri)?.use { input ->
                    cached.outputStream().use { output -> input.copyTo(output) }
                } != null
            } catch (_: Exception) {
                false
            }
        }
        if (!copied) {
            _errorMessage.value = "Could not read the selected file."
            return
        }

        val picked = PickedFile(uri, safeName, cached.length())
        _pickedFile.value = picked
        _cachedHtmlFile.value = cached

        // 4. Recents (most recent first, max 5).
        recentFilesStore.add(picked)
        _recentFiles.value = recentFilesStore.get()

        // 5. Duration auto-fill (never overwrites a hand-typed length).
        if (!lengthManuallyEdited) {
            detectDuration(cached)
        }
    }

    private fun detectDuration(cached: File) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    cached.readText()
                } catch (_: Exception) {
                    ""
                }
            }
            // 1. Fast text scan (meta / data-duration / DURATION global).
            val fromText = DurationDetector.detectFromText(text)
            if (fromText != null && fromText > 0) {
                applyDetectedLength(fromText)
                return@launch
            }
            // 2. Headless-WebView timeline (max animation end time).
            // The callback may arrive on any thread; hop back to Main.
            DurationDetector.detectFromWebView(getApplication(), cached) { seconds ->
                viewModelScope.launch {
                    applyDetectedLength(seconds?.takeIf { it > 0 } ?: FALLBACK_LENGTH_SECONDS)
                }
            }
        }
    }

    private fun applyDetectedLength(seconds: Double) {
        if (lengthManuallyEdited) return
        val value = seconds.takeIf { it > 0 } ?: FALLBACK_LENGTH_SECONDS
        _lengthText.value = formatLength(value)
        _options.value = _options.value.copy(lengthSeconds = value)
    }

    companion object {
        private const val FALLBACK_LENGTH_SECONDS = 30.0
    }
}

/** "131.16" style: up to 2 decimals, no trailing zeros. */
private fun formatLength(seconds: Double): String {
    val rounded = (seconds * 100).roundToLong() / 100.0
    return if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        String.format(Locale.US, "%.2f", rounded).trimEnd('0').trimEnd('.')
    }
}
