package com.toolbari.videorecorder.engine

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File

/**
 * Finds the playback length of an HTML animation file, in seconds.
 *
 * Two stages, in order (see BRIEF "Duration auto-fill"):
 * 1. [detectFromText] — a fast regex scan of the file text for a declared
 *    duration (`meta[name=duration]`, a `data-duration` attribute, or a
 *    `DURATION` / `__DURATION__` JS global).
 * 2. [detectFromWebView] — a headless WebView measures the real animation
 *    timeline ([TIMELINE_JS]); for safar_dua.html this must be ≈ 39.31s.
 * If neither finds a valid duration, the caller falls back to 30.0s.
 */
object DurationDetector {

    /**
     * JS that returns the longest FINITE animation end time in the page, in
     * seconds: the max over all animations of (startTime + computed end
     * time), in milliseconds, divided by 1000. Evaluated with
     * WebView.evaluateJavascript, so the result arrives as a JSON number
     * string (e.g. "39.31").
     *
     * The `.filter(Number.isFinite)` is essential, not optional: looping
     * animations (iteration-count infinite — the sun spin, the road, the
     * bobbing car in safar_dua.html) report endTime Infinity, and without
     * the filter the BRIEF's plain max() returns Infinity instead of the
     * content timeline. Verified in Chromium on safar_dua.html: 39.47s
     * (17 of 56 animations have an infinite end time).
     */
    const val TIMELINE_JS: String =
        "Math.max(0, ...document.getAnimations().map(a => " +
            "(a.startTime ?? 0) + (a.effect?.getComputedTiming().endTime ?? 0))" +
            ".filter(t => Number.isFinite(t))) / 1000"

    // A declared duration, in seconds. Accepts integers and decimals.
    private val META_DURATION_NAME_FIRST = Regex(
        "<meta[^>]*name\\s*=\\s*[\"']duration[\"'][^>]*content\\s*=\\s*[\"']([\\d.]+)[\"']",
        RegexOption.IGNORE_CASE
    )
    private val META_DURATION_CONTENT_FIRST = Regex(
        "<meta[^>]*content\\s*=\\s*[\"']([\\d.]+)[\"'][^>]*name\\s*=\\s*[\"']duration[\"']",
        RegexOption.IGNORE_CASE
    )
    private val DATA_DURATION = Regex(
        "data-duration\\s*=\\s*[\"']([\\d.]+)[\"']",
        RegexOption.IGNORE_CASE
    )
    private val DURATION_GLOBAL = Regex(
        "(?:(?:var|let|const)\\s+)?(?:window\\.)?(?:__DURATION__|DURATION)\\s*=\\s*([\\d.]+)",
        RegexOption.IGNORE_CASE
    )

    /**
     * Scans the HTML text for a declared duration. Returns the first valid
     * number (> 0, finite) found, in the order: meta[name=duration],
     * data-duration, DURATION / __DURATION__ global. Null when nothing
     * valid is declared.
     */
    fun detectFromText(htmlText: String): Double? {
        val candidates = listOf(
            META_DURATION_NAME_FIRST,
            META_DURATION_CONTENT_FIRST,
            DATA_DURATION,
            DURATION_GLOBAL
        )
        for (pattern in candidates) {
            val match = pattern.find(htmlText) ?: continue
            val seconds = match.groupValues.getOrNull(1)?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it > 0 }
            if (seconds != null) return seconds
        }
        return null
    }

    /**
     * Measures the animation timeline in a headless WebView that is never
     * attached to a window. Loads [htmlFile], waits for the first painted
     * frame, evaluates [TIMELINE_JS], and calls [onResult] exactly once
     * with the duration in seconds, or null when it cannot be measured
     * (load error or a 10-second timeout). Safe to call from any thread;
     * WebView work itself always runs on the main thread.
     */
    fun detectFromWebView(context: Context, htmlFile: File, onResult: (Double?) -> Unit) {
        val mainHandler = Handler(Looper.getMainLooper())
        if (Looper.myLooper() == Looper.getMainLooper()) {
            detectOnMainThread(context, htmlFile, onResult)
        } else {
            mainHandler.post { detectOnMainThread(context, htmlFile, onResult) }
        }
    }

    private fun detectOnMainThread(context: Context, htmlFile: File, onResult: (Double?) -> Unit) {
        var finished = false
        var evaluationStarted = false
        var destroyed = false
        val webView = WebView(context)
        val mainHandler = Handler(Looper.getMainLooper())
        lateinit var timeoutRunnable: Runnable

        fun cleanup() {
            if (!destroyed) {
                destroyed = true
                webView.destroy()
            }
        }

        fun finishOnce(seconds: Double?) {
            if (finished) return
            finished = true
            mainHandler.removeCallbacks(timeoutRunnable)
            cleanup()
            onResult(seconds)
        }

        timeoutRunnable = Runnable { finishOnce(null) }

        webView.settings.javaScriptEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (evaluationStarted) return
                evaluationStarted = true
                // Wait for the first painted frame so every CSS/WAAPI
                // animation exists in getAnimations() before measuring.
                view.postVisualStateCallback(1L, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        view.evaluateJavascript(TIMELINE_JS) { result ->
                            val seconds = result?.toDoubleOrNull()
                                ?.takeIf { it.isFinite() && it > 0 }
                            finishOnce(seconds)
                        }
                    }
                })
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) finishOnce(null)
            }
        }
        mainHandler.postDelayed(timeoutRunnable, TIMEOUT_MS)
        webView.loadUrl(Uri.fromFile(htmlFile).toString())
    }

    private const val TIMEOUT_MS = 10_000L
}
