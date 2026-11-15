package com.acceptsnap.watcher

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SavedShotInfo(
    val displayName: String,
    val relativePath: String,
    val dateFolder: String
)

object ScreenshotStorage {

    fun saveBitmap(context: Context, bitmap: Bitmap): SavedShotInfo? {
        val now = Date()
        val dateFolder = SimpleDateFormat(WatcherConfig.DATE_FOLDER_FORMAT, Locale.US).format(now)
        val timestamp = SimpleDateFormat(WatcherConfig.FILENAME_FORMAT, Locale.US).format(now)
        val folderRoot = WatcherConfig.getFolderRoot(context)
        val ext = if (WatcherConfig.IMAGE_FORMAT == "jpeg") "jpg" else "png"
        val mimeType = if (WatcherConfig.IMAGE_FORMAT == "jpeg") "image/jpeg" else "image/png"
        val compressFormat = if (WatcherConfig.IMAGE_FORMAT == "jpeg") {
            Bitmap.CompressFormat.JPEG
        } else {
            Bitmap.CompressFormat.PNG
        }
        val quality = if (WatcherConfig.IMAGE_FORMAT == "jpeg") WatcherConfig.JPEG_QUALITY else 100
        val fileName = "$timestamp.$ext"
        val relativeFolder = "${Environment.DIRECTORY_PICTURES}/$folderRoot/$dateFolder"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                put(MediaStore.Images.Media.RELATIVE_PATH, relativeFolder)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null

            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(compressFormat, quality, out)
            }

            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)

            SavedShotInfo(
                displayName = fileName,
                relativePath = "$relativeFolder/$fileName",
                dateFolder = dateFolder
            )
        } else {
            @Suppress("DEPRECATION")
            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val targetDir = File(picturesDir, "$folderRoot/$dateFolder")
            if (!targetDir.exists()) targetDir.mkdirs()
            val outFile = File(targetDir, fileName)
            FileOutputStream(outFile).use { out ->
                bitmap.compress(compressFormat, quality, out)
            }
            SavedShotInfo(
                displayName = fileName,
                relativePath = outFile.absolutePath,
                dateFolder = dateFolder
            )
        }
    }

    fun countTodayScreenshots(context: Context): Int {
        val today = SimpleDateFormat(WatcherConfig.DATE_FOLDER_FORMAT, Locale.US).format(Date())
        val folderRoot = WatcherConfig.getFolderRoot(context)
        val folderMatch = "%$folderRoot/$today%"
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        } else {
            "${MediaStore.Images.Media.DATA} LIKE ?"
        }
        return try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                arrayOf(folderMatch),
                null
            )?.use { cursor -> cursor.count } ?: 0
        } catch (_: Exception) {
            0
        }
    }
}
