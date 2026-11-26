package com.toolbari.videorecorder.data

import android.net.Uri

/** Output shape (aspect ratio) chosen on the Options screen. */
enum class Shape(val label: String) {
    NINE_16("9:16"),
    SIXTEEN_9("16:9"),
    ONE_1("1:1")
}

/** Output size. `longEdge` is the longer side in pixels (720 or 1080). */
enum class Size(val label: String, val longEdge: Int) {
    P720("720p", 720),
    P1080("1080p", 1080)
}

/**
 * The recording options chosen on the Options screen. Frame rate is fixed
 * at 30 fps in v1 and the format is always MP4, so neither is a field here.
 */
data class RecordingOptions(
    val shape: Shape = Shape.NINE_16,
    val size: Size = Size.P720,
    val lengthSeconds: Double = 30.0
)

/**
 * Exact output resolution as (width, height) in pixels.
 * 9:16 — 720p = 720x1280, 1080p = 1080x1920
 * 16:9 — 720p = 1280x720, 1080p = 1920x1080
 * 1:1  — 720p = 720x720,  1080p = 1080x1080
 * The 16:9 side is exact: longEdge * 16 / 9 (720 -> 1280, 1080 -> 1920).
 */
fun RecordingOptions.resolution(): Pair<Int, Int> {
    val long = size.longEdge
    return when (shape) {
        Shape.NINE_16 -> long to (long * 16 / 9)
        Shape.SIXTEEN_9 -> (long * 16 / 9) to long
        Shape.ONE_1 -> long to long
    }
}

/** Video bitrate in bits per second: 720p = 8 Mbps, 1080p = 12 Mbps. */
fun RecordingOptions.videoBitRate(): Int = when (size) {
    Size.P720 -> 8_000_000
    Size.P1080 -> 12_000_000
}

/** An HTML file the user picked (or re-picked from recents). */
data class PickedFile(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long
)

/**
 * The recording lifecycle, published by [com.toolbari.videorecorder.engine.RecordingService]
 * as a StateFlow. The ViewModel mirrors this flow to drive the screens:
 * Recording -> stay on the Recording screen; Done -> Done screen;
 * Error -> show the message and go back to Options; Cancelled -> Options.
 */
sealed class RecordingState {
    data object Idle : RecordingState()

    /** A recording is in progress. Times are in seconds. */
    data class Recording(val elapsedSeconds: Double, val totalSeconds: Double) : RecordingState()

    /** Recording finished and the MP4 is in Downloads. */
    data class Done(
        val outputUri: Uri,
        val fileName: String,
        val sizeBytes: Long,
        val durationSeconds: Double,
        val width: Int,
        val height: Int
    ) : RecordingState()

    /** Something failed; the message is shown to the user. */
    data class Error(val message: String) : RecordingState()

    /** The user cancelled; any partial file was deleted. */
    data object Cancelled : RecordingState()
}
