package com.toolbari.videorecorder.engine

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Names and saves the recorded MP4 into the phone's public Downloads
 * folder so it shows in Gallery and the file manager at once.
 */
object OutputSaver {

    private const val DATE_PATTERN = "yyyyMMdd-HHmm"

    /**
     * Output file name: `<html-name>-<yyyyMMdd-HHmm>.mp4`, where
     * [htmlName] is the picked file name without its extension. A trailing
     * .html/.htm is stripped defensively, and path separators are removed
     * so the name can never escape the Downloads folder.
     */
    fun outputFileName(htmlName: String, now: Date = Date()): String {
        val base = htmlName
            .removeSuffix(".html")
            .removeSuffix(".htm")
            .removeSuffix(".HTML")
            .removeSuffix(".HTM")
            .replace('/', '_')
            .replace('\\', '_')
            .trim()
            .ifEmpty { "recording" }
        val stamp = SimpleDateFormat(DATE_PATTERN, Locale.US).format(now)
        return "$base-$stamp.mp4"
    }

    /**
     * Copies [tempFile] into Downloads as [fileName] and returns its Uri.
     * API 29+: MediaStore Downloads with the IS_PENDING dance (the entry
     * is already indexed, so Gallery shows it at once). API 26–28: the
     * legacy public Downloads directory (needs WRITE_EXTERNAL_STORAGE,
     * maxSdkVersion 28) followed by a media scan so Gallery shows it.
     * The temp file is left for the caller to delete. Throws IOException
     * when the save fails (a partial MediaStore entry is removed).
     */
    fun saveToDownloads(context: Context, tempFile: File, fileName: String): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, tempFile, fileName)
        } else {
            saveViaLegacy(context, tempFile, fileName)
        }
    }

    private fun saveViaMediaStore(context: Context, tempFile: File, fileName: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create the Downloads entry for $fileName")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                tempFile.inputStream().use { input -> input.copyTo(out) }
            } ?: throw IOException("Could not open the Downloads entry for $fileName")
            val done = ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            resolver.update(uri, done, null, null)
        } catch (e: IOException) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    private fun saveViaLegacy(context: Context, tempFile: File, fileName: String): Uri {
        @Suppress("DEPRECATION")
        val downloadsDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        if (!downloadsDir.exists()) downloadsDir.mkdirs()
        val destFile = File(downloadsDir, fileName)
        tempFile.inputStream().use { input ->
            destFile.outputStream().use { output -> input.copyTo(output) }
        }
        // Legacy files are not indexed automatically — scan so Gallery
        // shows the MP4 immediately, without moving any file.
        MediaScannerConnection.scanFile(
            context,
            arrayOf(destFile.absolutePath),
            arrayOf("video/mp4"),
            null
        )
        return Uri.fromFile(destFile)
    }
}
