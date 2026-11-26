package com.toolbari.videorecorder.data

import android.content.Context
import android.net.Uri

/**
 * Persists the last 5 picked HTML files in SharedPreferences so the Pick
 * screen can offer them as "Recent files". Each entry is stored as one
 * line: `encodedUri|encodedName|sizeBytes` — the fields are Uri-encoded so
 * the separators can never appear inside a value. Order is most-recent
 * first. The stored URI is the original content URI (the app holds a
 * persistable read permission for it), not the cache copy, which is
 * transient.
 */
class RecentFilesStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Last 5 files, most recent first. Returns an empty list when none. */
    fun get(): List<PickedFile> {
        val raw = prefs.getString(KEY_FILES, null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return raw.split('\n').mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size != 3) return@mapNotNull null
            val uriString = Uri.decode(parts[0])
            val name = Uri.decode(parts[1])
            val sizeBytes = parts[2].toLongOrNull() ?: return@mapNotNull null
            PickedFile(Uri.parse(uriString), name, sizeBytes)
        }
    }

    /** Adds a file to the front, de-duplicating by URI, keeping at most 5. */
    fun add(file: PickedFile) {
        val current = get().filter { it.uri.toString() != file.uri.toString() }
        val updated = (listOf(file) + current).take(MAX_RECENT)
        val raw = updated.joinToString("\n") { entry ->
            "${Uri.encode(entry.uri.toString())}|${Uri.encode(entry.name)}|${entry.sizeBytes}"
        }
        prefs.edit().putString(KEY_FILES, raw).apply()
    }

    companion object {
        private const val PREFS_NAME = "video_recorder_recent_files"
        private const val KEY_FILES = "files"
        private const val MAX_RECENT = 5
    }
}
