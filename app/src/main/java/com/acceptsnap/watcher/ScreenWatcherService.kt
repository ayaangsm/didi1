package com.acceptsnap.watcher

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class ScreenWatcherService : Service() {

    companion object {
        const val ACTION_START = "com.acceptsnap.watcher.ACTION_START"
        const val ACTION_STOP = "com.acceptsnap.watcher.ACTION_STOP"
        const val ACTION_TRIGGER_IMMEDIATE = "com.acceptsnap.watcher.ACTION_TRIGGER_IMMEDIATE"
        const val ACTION_CAPTURE_EVENT = "com.acceptsnap.watcher.ACTION_CAPTURE_EVENT"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_TRIGGER_KEYWORD = "trigger_keyword"
        const val EXTRA_TRIGGER_SOURCE = "trigger_source"
        const val EXTRA_SAVED_PATH = "saved_path"

        private const val CHANNEL_ID = "accept_snap_watcher_channel"
        private const val NOTIFICATION_ID = 4041

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var lastSavedPath: String = ""
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchJob: Job? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopWatcher()
            stopSelf()
        }
    }

    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private var lastCaptureTimestamp = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopWatcher()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_TRIGGER_IMMEDIATE -> {
                val keyword = intent.getStringExtra(EXTRA_TRIGGER_KEYWORD) ?: "accept"
                val source = intent.getStringExtra(EXTRA_TRIGGER_SOURCE) ?: "accessibility_node"
                serviceScope.launch {
                    captureCurrentFrameIfCooldownElapsed(keyword, source)
                }
                return START_STICKY
            }

            ACTION_START -> {
                val notification = buildNotification("Watching screen in background…")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    setupMediaProjection(resultCode, resultData)
                    startOcrWatchLoop()
                }
            }
        }
        return START_STICKY
    }

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        tearDownProjection()
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = manager.getMediaProjection(resultCode, data) ?: return
        mediaProjection = projection
        projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection.createVirtualDisplay(
            "AcceptSnapVirtualDisplay",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
        isRunning = true
    }

    private fun startOcrWatchLoop() {
        watchJob?.cancel()
        watchJob = serviceScope.launch {
            while (isActive) {
                val interval = WatcherConfig.getScanIntervalMs(this@ScreenWatcherService)
                if (WatcherConfig.getDetectionMode(this@ScreenWatcherService) != "accessibility_only") {
                    try {
                        val bitmap = grabLatestBitmap()
                        if (bitmap != null) {
                            val recognizedText = runMlKitOcr(bitmap)
                            val keywords = WatcherConfig.getKeywords(this@ScreenWatcherService)
                            val matched = WatcherConfig.matchesKeyword(
                                this@ScreenWatcherService,
                                recognizedText,
                                keywords
                            )
                            if (matched != null) {
                                saveIfCooldownElapsed(bitmap, matched, "ocr_image")
                            }
                            bitmap.recycle()
                        }
                    } catch (_: Exception) {
                        // Continue watching even if a single frame is busy during app transitions
                    }
                }
                delay(interval)
            }
        }
    }

    private suspend fun captureCurrentFrameIfCooldownElapsed(keyword: String, source: String) {
        val now = System.currentTimeMillis()
        if (now - lastCaptureTimestamp < WatcherConfig.getCooldownMs(this)) return

        val bitmap = grabLatestBitmap() ?: return
        try {
            saveIfCooldownElapsed(bitmap, keyword, source)
        } finally {
            bitmap.recycle()
        }
    }

    private fun saveIfCooldownElapsed(bitmap: Bitmap, keyword: String, source: String) {
        val now = System.currentTimeMillis()
        val cooldown = WatcherConfig.getCooldownMs(this)
        if (now - lastCaptureTimestamp < cooldown) return

        val saved = ScreenshotStorage.saveBitmap(this, bitmap) ?: return
        lastCaptureTimestamp = now
        lastSavedPath = saved.relativePath

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(
            NOTIFICATION_ID,
            buildNotification("Saved ${saved.displayName} (matched '$keyword')")
        )

        val eventIntent = Intent(ACTION_CAPTURE_EVENT).apply {
            setPackage(packageName)
            putExtra(EXTRA_SAVED_PATH, saved.relativePath)
            putExtra(EXTRA_TRIGGER_KEYWORD, keyword)
            putExtra(EXTRA_TRIGGER_SOURCE, source)
        }
        sendBroadcast(eventIntent)
    }

    private fun grabLatestBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        var image: Image? = null
        return try {
            image = reader.acquireLatestImage() ?: return null
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val rawBitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            rawBitmap.copyPixelsFromBuffer(buffer)
            if (rowPadding == 0) {
                rawBitmap
            } else {
                val cropped = Bitmap.createBitmap(rawBitmap, 0, 0, image.width, image.height)
                rawBitmap.recycle()
                cropped
            }
        } catch (_: Exception) {
            null
        } finally {
            image?.close()
        }
    }

    private suspend fun runMlKitOcr(bitmap: Bitmap): String =
        suspendCancellableCoroutine { continuation ->
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            textRecognizer.process(inputImage)
                .addOnSuccessListener { result ->
                    if (continuation.isActive) continuation.resume(result.text)
                }
                .addOnFailureListener {
                    if (continuation.isActive) continuation.resume("")
                }
        }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            WatcherConfig.NOTIFICATION_TITLE,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = WatcherConfig.NOTIFICATION_BODY
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(statusText: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ScreenWatcherService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(WatcherConfig.NOTIFICATION_TITLE)
            .setContentText(statusText)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }

    private fun tearDownProjection() {
        isRunning = false
        watchJob?.cancel()
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        mediaProjection = null
    }

    private fun stopWatcher() {
        tearDownProjection()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopWatcher()
        textRecognizer.close()
        serviceScope.cancel()
        super.onDestroy()
    }
}
