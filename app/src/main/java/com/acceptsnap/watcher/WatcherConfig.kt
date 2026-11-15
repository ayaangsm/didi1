package com.acceptsnap.watcher

import android.content.Context

object WatcherConfig {
    private const val PREFS_NAME = "accept_snap_prefs"
    private const val KEY_KEYWORDS = "keywords"
    private const val KEY_COOLDOWN_SEC = "cooldown_sec"
    private const val KEY_SCAN_INTERVAL_MS = "scan_interval_ms"
    private const val KEY_FOLDER_ROOT = "folder_root"
    private const val KEY_CASE_SENSITIVE = "case_sensitive"
    private const val KEY_WHOLE_WORD = "whole_word_only"
    private const val KEY_DETECTION_MODE = "detection_mode"

    val DEFAULT_KEYWORDS = listOf("accept")
    const val DEFAULT_CASE_SENSITIVE = false
    const val DEFAULT_WHOLE_WORD_ONLY = false
    const val DEFAULT_DETECTION_MODE = "hybrid" // hybrid | ocr_only | accessibility_only
    const val DEFAULT_SCAN_INTERVAL_MS = 1200L
    const val DEFAULT_COOLDOWN_SECONDS = 5L
    const val DEFAULT_FOLDER_ROOT = "AcceptSnap"
    const val DATE_FOLDER_FORMAT = "yyyy-MM-dd"
    const val FILENAME_FORMAT = "yyyyMMdd_HHmmss_SSS"
    const val IMAGE_FORMAT = "png"
    const val JPEG_QUALITY = 92
    const val NOTIFICATION_TITLE = "AcceptSnap Watcher Active"
    const val NOTIFICATION_BODY = "Monitoring screen in background for \"accept\" text or images"

    fun getKeywords(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_KEYWORDS, null) ?: return DEFAULT_KEYWORDS
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { DEFAULT_KEYWORDS }
    }

    fun getCooldownMs(context: Context): Long {
        val sec = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_COOLDOWN_SEC, DEFAULT_COOLDOWN_SECONDS)
        return sec.coerceAtLeast(1L) * 1000L
    }

    fun getScanIntervalMs(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_SCAN_INTERVAL_MS, DEFAULT_SCAN_INTERVAL_MS)
            .coerceAtLeast(400L)
    }

    fun getFolderRoot(context: Context): String {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_FOLDER_ROOT, DEFAULT_FOLDER_ROOT)
            ?.trim()
            ?.replace(Regex("[/\\\\:*?\"<>|]"), "")
        return if (raw.isNullOrEmpty()) DEFAULT_FOLDER_ROOT else raw
    }

    fun isCaseSensitive(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CASE_SENSITIVE, DEFAULT_CASE_SENSITIVE)

    fun isWholeWordOnly(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_WHOLE_WORD, DEFAULT_WHOLE_WORD_ONLY)

    fun getDetectionMode(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DETECTION_MODE, DEFAULT_DETECTION_MODE) ?: DEFAULT_DETECTION_MODE

    fun saveSettings(
        context: Context,
        keywordsCsv: String,
        cooldownSeconds: Long,
        scanIntervalMs: Long,
        folderRoot: String,
        caseSensitive: Boolean,
        wholeWordOnly: Boolean,
        detectionMode: String
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_KEYWORDS, keywordsCsv)
            .putLong(KEY_COOLDOWN_SEC, cooldownSeconds.coerceAtLeast(1L))
            .putLong(KEY_SCAN_INTERVAL_MS, scanIntervalMs.coerceAtLeast(400L))
            .putString(KEY_FOLDER_ROOT, folderRoot.ifBlank { DEFAULT_FOLDER_ROOT })
            .putBoolean(KEY_CASE_SENSITIVE, caseSensitive)
            .putBoolean(KEY_WHOLE_WORD, wholeWordOnly)
            .putString(KEY_DETECTION_MODE, detectionMode)
            .apply()
    }

    fun matchesKeyword(context: Context, text: String, keywords: List<String>): String? {
        if (text.isBlank()) return null
        val caseSensitive = isCaseSensitive(context)
        val wholeWord = isWholeWordOnly(context)

        for (keyword in keywords) {
            if (keyword.isBlank()) continue
            if (wholeWord) {
                val regexFlags = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                val regex = Regex("\\b" + Regex.escape(keyword) + "\\b", regexFlags)
                if (regex.containsMatchIn(text)) return keyword
            } else {
                if (text.contains(keyword, ignoreCase = !caseSensitive)) {
                    return keyword
                }
            }
        }
        return null
    }
}
