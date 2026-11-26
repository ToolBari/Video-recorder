package com.toolbari.videorecorder.engine

import android.graphics.Color
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import java.io.File

/**
 * Sets up the WebViews used for recording (and the headless duration
 * probe). The viewport handling here is the exact fix for the Animation
 * Studio bug: that app's WebView used the default ~980px-wide viewport,
 * so a 720px stage rendered small with black bars on the right and
 * bottom. Here the stage fills the frame edge to edge.
 *
 * All evaluateJavascript calls must run on the main thread; [eval] posts
 * to the main thread when the caller is on another thread.
 */
object WebViewSetup {

    // JS from the BRIEF (same snippets as the Animation Studio
    // deterministic-capture fix). rAF/canvas pages ignore pause/rewind —
    // acceptable; the spec's HTMLs are CSS/WAAPI animations.
    private const val PAUSE_JS =
        "document.getAnimations().forEach(a => a.pause())"
    private const val REWIND_JS =
        "document.getAnimations().forEach(a => a.currentTime = 0)"
    private const val PLAY_JS =
        "document.getAnimations().forEach(a => a.play())"

    /**
     * Configures [webView] to render exactly [widthPx] x [heightPx]
     * device pixels, with the HTML stage filling the frame edge to edge:
     * JavaScript on, narrow viewport (no ~980px wide-viewport scaling),
     * no overview mode, initial scale so the page width equals the WebView
     * width, no scrollbars, no zoom, no overscroll, black background (any
     * uncovered pixel is black, never white), and exact-pixel layout
     * params. Call before [loadHtmlFile]; the initial scale only takes
     * effect on the following load.
     */
    fun configure(webView: WebView, widthPx: Int, heightPx: Int) {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        // Narrow viewport: the layout viewport equals the WebView width,
        // so a stage authored at the target width fills it exactly.
        settings.useWideViewPort = false
        settings.loadWithOverviewMode = false
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.allowFileAccess = true

        // Initial scale so the page width equals the WebView width:
        // (webViewWidthPx * 100) / pageWidthCssPx. The HTMLs are authored
        // at the 720p stage size (720 CSS px wide for 9:16 and 1:1, 1280
        // for 16:9), so the scale is 100 at 720p and 150 at 1080p.
        // Computed from the actual widths, never hardcoded to 100.
        val stageWidthCssPx = stageWidthCssPx(widthPx, heightPx)
        webView.setInitialScale((widthPx * 100) / stageWidthCssPx)

        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.setBackgroundColor(Color.BLACK)
        // Exact pixels — never MATCH_PARENT on a phone screen for the
        // recording WebView. (Inside the Presentation, whose window IS
        // exactly the target size, these exact params fill it edge to edge.)
        webView.layoutParams = ViewGroup.LayoutParams(widthPx, heightPx)
    }

    /**
     * The stage width, in CSS px, that the spec's HTMLs are authored at:
     * the 720p width of the target shape — 720 for 9:16 and 1:1, 1280 for
     * 16:9. Derived from the aspect ratio of the target frame.
     */
    private fun stageWidthCssPx(widthPx: Int, heightPx: Int): Int =
        if (widthPx > heightPx) 1280 else 720

    /** Loads a local HTML file into [webView] (file:// URL). */
    fun loadHtmlFile(webView: WebView, file: File) {
        webView.loadUrl(Uri.fromFile(file).toString())
    }

    /**
     * Pauses every animation and rewinds them all to time 0, in one
     * ordered JS call so the rewind can never run before the pause.
     * Called after the first frame is painted, before the recorder starts.
     */
    fun pauseAndRewind(webView: WebView) {
        eval(webView, "$PAUSE_JS; $REWIND_JS")
    }

    /** Plays every animation from time 0 — the moment the timer starts. */
    fun playAll(webView: WebView) {
        eval(webView, PLAY_JS)
    }

    private fun eval(webView: WebView, js: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            webView.evaluateJavascript(js, null)
        } else {
            webView.post { webView.evaluateJavascript(js, null) }
        }
    }
}
