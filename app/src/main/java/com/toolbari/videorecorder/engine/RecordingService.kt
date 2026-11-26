package com.toolbari.videorecorder.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Presentation
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.toolbari.videorecorder.MainActivity
import com.toolbari.videorecorder.data.RecordingOptions
import com.toolbari.videorecorder.data.RecordingState
import com.toolbari.videorecorder.data.resolution
import com.toolbari.videorecorder.data.videoBitRate
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Records an HTML animation file to MP4 in REAL TIME (not frame by frame).
 *
 * Why a virtual display + Presentation, instead of recording the phone
 * screen: MediaProjection records a SCREEN, and a phone screen is e.g.
 * 1080x2400 — but the output must be EXACTLY the target resolution
 * (720x1280). Scaling the phone screen into the target size would distort
 * or letterbox it. So this service creates a [VirtualDisplay] at the exact
 * target resolution (dpi 320) fed by a [MediaRecorder] surface, and shows
 * a [Presentation] on that virtual display. The Presentation's window holds
 * the recording WebView, sized exactly to the virtual display, so the
 * WebView fills the frame edge to edge — no distortion, no letterbox, any
 * shape (9:16 / 16:9 / 1:1). The user watches a separate preview WebView
 * on the main screen (owned by the UI); this service only owns the
 * recording WebView.
 *
 * Recording continues if the user leaves the app and if the phone sleeps:
 * the service holds a PARTIAL_WAKE_LOCK and the virtual display stays
 * alive. The service is a foreground service
 * (foregroundServiceType="mediaProjection", see the manifest).
 *
 * Start sync (exact length, no black seconds): after the recording WebView
 * loads and paints its first frame, all animations are paused and rewound
 * to 0, the MediaRecorder starts, then the animations play and the timer
 * starts with the playback.
 *
 * Only this service's static intent builders create its intents — callers
 * (the ViewModel / MainActivity) use [startIntent], [stopIntent] and
 * [cancelIntent] and never build the intents by hand.
 */
class RecordingService : Service() {

    companion object {
        // The recording lifecycle. The ViewModel collects this flow.
        private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
        val state: StateFlow<RecordingState> = _state.asStateFlow()

        const val ACTION_START = "com.toolbari.videorecorder.action.START"
        const val ACTION_STOP = "com.toolbari.videorecorder.action.STOP"
        const val ACTION_CANCEL = "com.toolbari.videorecorder.action.CANCEL"

        const val EXTRA_HTML_PATH = "extra_html_path"
        const val EXTRA_SHAPE = "extra_shape"
        const val EXTRA_SIZE = "extra_size"
        const val EXTRA_LENGTH = "extra_length"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_data"

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "recording"

        private const val FRAME_RATE = 30
        private const val VIRTUAL_DISPLAY_DPI = 320
        private const val TIMER_TICK_MS = 100L

        // Beat after pause/rewind so the JS has applied before the
        // recorder starts (see onFirstPaint).
        private const val START_SYNC_DELAY_MS = 100L

        /**
         * Builds the intent that starts a recording. [htmlFile] is the
         cached copy of the picked HTML. [resultCode] and [data] are the
         MediaProjection capture-consent result from MainActivity.
         */
        fun startIntent(
            context: Context,
            htmlFile: File,
            options: RecordingOptions,
            resultCode: Int,
            data: Intent
        ): Intent = Intent(context, RecordingService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_HTML_PATH, htmlFile.absolutePath)
            putExtra(EXTRA_SHAPE, options.shape.name)
            putExtra(EXTRA_SIZE, options.size.name)
            putExtra(EXTRA_LENGTH, options.lengthSeconds)
            putExtra(EXTRA_RESULT_CODE, resultCode)
            putExtra(EXTRA_DATA, data)
        }

        /** Normal stop (used internally when the length ends). */
        fun stopIntent(context: Context): Intent =
            Intent(context, RecordingService::class.java).apply { action = ACTION_STOP }

        /** User Cancel: stops the recording and deletes the partial file. */
        fun cancelIntent(context: Context): Intent =
            Intent(context, RecordingService::class.java).apply { action = ACTION_CANCEL }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaProjection: MediaProjection? = null
    private var mediaRecorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var recordingWebView: WebView? = null
    private var tempFile: File? = null
    private var timerJob: Job? = null

    private var recordingActive = false
    private var finished = false
    private var firstPaintHandled = false
    private var recorderStarted = false

    private var htmlName: String = "recording"
    private var targetWidth: Int = 0
    private var targetHeight: Int = 0
    private var totalSeconds: Double = 0.0
    private var startTimeMs: Long = 0L

    // The system calls this when the projection is stopped from outside
    // (e.g. the user revokes screen capture). Our own stop() unregisters
    // this callback first, so this only fires for an external stop.
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (recordingActive && !finished) finishRecording(save = false)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        acquireWakeLock()
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_STOP -> {
                if (recordingActive) finishRecording(save = true) else stopSelf()
            }
            ACTION_CANCEL -> {
                if (recordingActive) finishRecording(save = false) else stopSelf()
            }
            else -> stopSelf()
        }
        // Do not restart after a kill: a restarted service would have no
        // valid capture-consent intent to record with.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        timerJob?.cancel()
        timerJob = null
        tearDownRecorder()
        tempFile?.delete()
        tempFile = null
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Recording setup
    // ------------------------------------------------------------------

    private fun startRecording(intent: Intent) {
        if (recordingActive) return
        // The foreground notification must be posted before any heavy
        // work: a service started with startForegroundService has only a
        // few seconds to call startForeground.
        startForegroundWithType(buildNotification())

        val htmlPath = intent.getStringExtra(EXTRA_HTML_PATH)
        val shapeName = intent.getStringExtra(EXTRA_SHAPE)
        val sizeName = intent.getStringExtra(EXTRA_SIZE)
        val lengthSeconds = intent.getDoubleExtra(EXTRA_LENGTH, 0.0)
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val captureData = intent.captureData()
        val htmlFile = htmlPath?.let { File(it) }

        if (htmlFile == null || !htmlFile.exists() || shapeName == null ||
            sizeName == null || lengthSeconds <= 0.0 || captureData == null
        ) {
            fail("Could not start recording: missing recording details.")
            return
        }

        val options = try {
            RecordingOptions(
                shape = com.toolbari.videorecorder.data.Shape.valueOf(shapeName),
                size = com.toolbari.videorecorder.data.Size.valueOf(sizeName),
                lengthSeconds = lengthSeconds
            )
        } catch (_: IllegalArgumentException) {
            fail("Could not start recording: invalid recording options.")
            return
        }

        finished = false
        firstPaintHandled = false
        recorderStarted = false
        recordingActive = true
        htmlName = htmlFile.nameWithoutExtension
        val (width, height) = options.resolution()
        targetWidth = width
        targetHeight = height
        totalSeconds = options.lengthSeconds

        val recordingsDir = File(cacheDir, "recordings").apply { mkdirs() }
        tempFile = File(recordingsDir, "recording-${System.currentTimeMillis()}.mp4")

        try {
            val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(resultCode, captureData)
                ?: throw IllegalStateException("Screen capture was not granted.")
            mediaProjection = projection
            projection.registerCallback(projectionCallback, mainHandler)

            // Video-only: no audio source, so the MP4 has no audio track
            // (allowed by SPEC section 5).
            val recorder = MediaRecorder().apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoEncodingBitRate(options.videoBitRate())
                setVideoFrameRate(FRAME_RATE)
                // Exact target resolution — this is what makes the output
                // 720x1280 (or the chosen shape/size), never the phone
                // screen size.
                setVideoSize(width, height)
                setOutputFile(tempFile!!.absolutePath)
                prepare()
            }
            mediaRecorder = recorder

            // Virtual display at the EXACT target resolution, fed by the
            // recorder's surface. dpi 320: the display density the
            // WebView uses for its CSS pixel mapping.
            val display = projection.createVirtualDisplay(
                "VideoRecorder",
                width,
                height,
                VIRTUAL_DISPLAY_DPI,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.surface,
                null,
                null
            )
            virtualDisplay = display

            val pres = object : Presentation(this, display.display) {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    // The content view (the recording WebView) is set below.
                }
            }
            presentation = pres

            // The WebView is created with the Presentation's (display)
            // context so it renders on the virtual display, not the phone
            // screen.
            val webView = WebView(pres.context).apply {
                WebViewSetup.configure(this, width, height)
                webViewClient = RecordingWebViewClient()
            }
            recordingWebView = webView
            pres.setContentView(webView)
            pres.show()

            WebViewSetup.loadHtmlFile(webView, htmlFile)
        } catch (e: Exception) {
            fail("Could not start recording: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // Start sync: first paint -> pause/rewind -> record -> play -> timer
    // ------------------------------------------------------------------

    private inner class RecordingWebViewClient : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String) {
            if (firstPaintHandled) return
            firstPaintHandled = true
            // Wait for the first painted frame so every CSS/WAAPI animation
            // exists before we pause and rewind it.
            view.postVisualStateCallback(1L, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) = onFirstPaint()
            })
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            if (request.isForMainFrame) fail("Could not load the HTML file.")
        }
    }

    private fun onFirstPaint() {
        val webView = recordingWebView ?: return
        if (finished) return
        WebViewSetup.pauseAndRewind(webView)
        // The pause/rewind JS runs asynchronously; give it a beat to apply
        // before starting the recorder so the first captured frame is the
        // rewound frame (time 0), not a frame mid-animation.
        mainHandler.postDelayed({ startRecorderAndPlay() }, START_SYNC_DELAY_MS)
    }

    private fun startRecorderAndPlay() {
        val webView = recordingWebView ?: return
        if (finished) return
        try {
            mediaRecorder?.start()
        } catch (e: Exception) {
            fail("Could not start recording: ${e.message}")
            return
        }
        recorderStarted = true
        WebViewSetup.playAll(webView)
        // The timer starts with the playback, so the video length matches
        // the Length setting exactly — no extra seconds at the start.
        startTimeMs = SystemClock.elapsedRealtime()
        _state.value = RecordingState.Recording(0.0, totalSeconds)
        startTimer()
    }

    private fun startTimer() {
        timerJob = serviceScope.launch {
            while (isActive) {
                delay(TIMER_TICK_MS)
                val elapsed = (SystemClock.elapsedRealtime() - startTimeMs) / 1000.0
                if (elapsed >= totalSeconds) {
                    finishRecording(save = true)
                    break
                }
                _state.value = RecordingState.Recording(elapsed, totalSeconds)
            }
        }
    }

    // ------------------------------------------------------------------
    // Finish: stop, save (or delete on cancel), report, stopSelf
    // ------------------------------------------------------------------

    /**
     * Stops the recording. [save] true: the MP4 is saved to Downloads and
     * [RecordingState.Done] is posted. False (Cancel): the partial file is
     * deleted and [RecordingState.Cancelled] is posted. Runs only once.
     */
    private fun finishRecording(save: Boolean) {
        if (finished) return
        finished = true
        recordingActive = false
        timerJob?.cancel()
        timerJob = null
        val elapsed = ((SystemClock.elapsedRealtime() - startTimeMs) / 1000.0)
            .coerceAtMost(totalSeconds)
            .coerceAtLeast(0.0)
        tearDownRecorder()
        if (save) {
            val temp = tempFile
            if (temp == null || !temp.exists() || temp.length() == 0L) {
                _state.value = RecordingState.Error("Recording produced no video file.")
            } else {
                val sizeBytes = temp.length()
                try {
                    val fileName = OutputSaver.outputFileName(htmlName)
                    val uri = OutputSaver.saveToDownloads(this, temp, fileName)
                    _state.value = RecordingState.Done(
                        outputUri = uri,
                        fileName = fileName,
                        sizeBytes = sizeBytes,
                        durationSeconds = elapsed,
                        width = targetWidth,
                        height = targetHeight
                    )
                } catch (e: Exception) {
                    _state.value =
                        RecordingState.Error("Could not save the video: ${e.message}")
                } finally {
                    temp.delete()
                }
            }
            tempFile = null
        } else {
            tempFile?.delete()
            tempFile = null
            _state.value = RecordingState.Cancelled
        }
        stopSelf()
    }

    /** Unrecoverable failure: tear everything down and report the error. */
    private fun fail(message: String) {
        if (finished) return
        finished = true
        recordingActive = false
        timerJob?.cancel()
        timerJob = null
        tearDownRecorder()
        tempFile?.delete()
        tempFile = null
        _state.value = RecordingState.Error(message)
        stopSelf()
    }

    /**
     * Stops and releases the recorder, virtual display, Presentation,
     * WebView and MediaProjection. Safe to call when nothing was set up
     * yet, and safe to call twice (onDestroy also calls it).
     */
    private fun tearDownRecorder() {
        if (recorderStarted) {
            try {
                mediaRecorder?.stop()
            } catch (_: RuntimeException) {
                // No frames were captured, or the recorder already stopped.
            }
        }
        recorderStarted = false
        runCatching { mediaRecorder?.release() }
        mediaRecorder = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        try {
            presentation?.dismiss()
        } catch (_: Exception) {
            // Already dismissed.
        }
        presentation = null
        runCatching { recordingWebView?.destroy() }
        recordingWebView = null
        mediaProjection?.let { projection ->
            runCatching { projection.unregisterCallback(projectionCallback) }
            runCatching { projection.stop() }
        }
        mediaProjection = null
    }

    // ------------------------------------------------------------------
    // Foreground notification + wake lock
    // ------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Recording",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows while a video is being recorded"
            setShowBadge(false)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openApp = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openApp,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPending = PendingIntent.getService(
            this, 0, cancelIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Recording…")
            .setContentText("Tap to stop")
            .setContentIntent(openPending)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(null, "Stop", stopPending).build()
            )
            .build()
    }

    private fun startForegroundWithType(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "VideoRecorder::Recording"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) runCatching { lock.release() }
        }
        wakeLock = null
    }

    @Suppress("DEPRECATION")
    private fun Intent.captureData(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            getParcelableExtra(EXTRA_DATA)
        }
}
