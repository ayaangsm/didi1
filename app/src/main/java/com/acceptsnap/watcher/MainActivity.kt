package com.acceptsnap.watcher

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var lastSavedText: TextView
    private lateinit var todayCountText: TextView
    private lateinit var keywordInput: EditText
    private lateinit var cooldownInput: EditText
    private lateinit var scanIntervalInput: EditText
    private lateinit var folderRootInput: EditText
    private lateinit var caseSensitiveBox: CheckBox
    private lateinit var wholeWordBox: CheckBox
    private lateinit var modeSpinner: Spinner

    private val modes = listOf("hybrid", "ocr_only", "accessibility_only")

    private val captureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val savedPath = intent?.getStringExtra(ScreenWatcherService.EXTRA_SAVED_PATH) ?: return
            lastSavedText.text = "Last saved: $savedPath"
            refreshStats()
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ScreenWatcherService::class.java).apply {
                action = ScreenWatcherService.ACTION_START
                putExtra(ScreenWatcherService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenWatcherService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            statusText.text = "Background watcher running — you can open other apps now."
        } else {
            statusText.text = "Screen capture permission cancelled."
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        requestScreenCapture()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
        }

        val title = TextView(this).apply {
            text = "AcceptSnap"
            textSize = 24f
        }
        val subtitle = TextView(this).apply {
            text = "Watches open apps in background and saves screenshots into daily Pictures/<folder>/YYYY-MM-DD folders."
            setPadding(0, 12, 0, 24)
        }

        statusText = TextView(this).apply {
            text = if (ScreenWatcherService.isRunning) {
                "Background watcher running."
            } else {
                "Watcher stopped."
            }
            setPadding(0, 8, 0, 8)
        }

        todayCountText = TextView(this).apply {
            setPadding(0, 4, 0, 8)
        }

        lastSavedText = TextView(this).apply {
            text = if (ScreenWatcherService.lastSavedPath.isNotEmpty()) {
                "Last saved: ${ScreenWatcherService.lastSavedPath}"
            } else {
                "No screenshots captured yet in this session."
            }
            setPadding(0, 4, 0, 20)
        }

        val startBtn = Button(this).apply {
            text = "Start Background Watcher"
            setOnClickListener { startWatcherFlow() }
        }

        val stopBtn = Button(this).apply {
            text = "Stop Watcher"
            setOnClickListener {
                startService(Intent(this@MainActivity, ScreenWatcherService::class.java).apply {
                    action = ScreenWatcherService.ACTION_STOP
                })
                statusText.text = "Watcher stopped."
            }
        }

        val accessibilityBtn = Button(this).apply {
            text = "Open Accessibility Settings"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val batteryBtn = Button(this).apply {
            text = "Disable Battery Optimization"
            setOnClickListener {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }

        val keywordLabel = TextView(this).apply {
            text = "Trigger words (comma-separated):"
            setPadding(0, 28, 0, 0)
        }
        keywordInput = EditText(this).apply {
            setText(WatcherConfig.getKeywords(this@MainActivity).joinToString(", "))
        }

        val folderLabel = TextView(this).apply {
            text = "Pictures folder name:"
            setPadding(0, 16, 0, 0)
        }
        folderRootInput = EditText(this).apply {
            setText(WatcherConfig.getFolderRoot(this@MainActivity))
        }

        val cooldownLabel = TextView(this).apply {
            text = "Cooldown between captures (seconds):"
            setPadding(0, 16, 0, 0)
        }
        cooldownInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText((WatcherConfig.getCooldownMs(this@MainActivity) / 1000L).toString())
        }

        val intervalLabel = TextView(this).apply {
            text = "Image OCR scan interval (ms):"
            setPadding(0, 16, 0, 0)
        }
        scanIntervalInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(WatcherConfig.getScanIntervalMs(this@MainActivity).toString())
        }

        val modeLabel = TextView(this).apply {
            text = "Detection mode:"
            setPadding(0, 16, 0, 8)
        }
        modeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Hybrid (Text + Image OCR)", "Image OCR Only", "Accessibility Text Only")
            )
            val currentIdx = modes.indexOf(WatcherConfig.getDetectionMode(this@MainActivity))
            if (currentIdx >= 0) setSelection(currentIdx)
        }

        caseSensitiveBox = CheckBox(this).apply {
            text = "Case-sensitive match"
            isChecked = WatcherConfig.isCaseSensitive(this@MainActivity)
        }

        wholeWordBox = CheckBox(this).apply {
            text = "Whole word only"
            isChecked = WatcherConfig.isWholeWordOnly(this@MainActivity)
        }

        val saveConfigBtn = Button(this).apply {
            text = "Save Settings"
            setOnClickListener {
                WatcherConfig.saveSettings(
                    context = this@MainActivity,
                    keywordsCsv = keywordInput.text.toString(),
                    cooldownSeconds = cooldownInput.text.toString().toLongOrNull()
                        ?: WatcherConfig.DEFAULT_COOLDOWN_SECONDS,
                    scanIntervalMs = scanIntervalInput.text.toString().toLongOrNull()
                        ?: WatcherConfig.DEFAULT_SCAN_INTERVAL_MS,
                    folderRoot = folderRootInput.text.toString(),
                    caseSensitive = caseSensitiveBox.isChecked,
                    wholeWordOnly = wholeWordBox.isChecked,
                    detectionMode = modes.getOrElse(modeSpinner.selectedItemPosition) { "hybrid" }
                )
                statusText.text = "Settings saved."
                refreshStats()
            }
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(statusText)
        root.addView(todayCountText)
        root.addView(lastSavedText)
        root.addView(startBtn)
        root.addView(stopBtn)
        root.addView(accessibilityBtn)
        root.addView(batteryBtn)
        root.addView(keywordLabel)
        root.addView(keywordInput)
        root.addView(folderLabel)
        root.addView(folderRootInput)
        root.addView(cooldownLabel)
        root.addView(cooldownInput)
        root.addView(intervalLabel)
        root.addView(scanIntervalInput)
        root.addView(modeLabel)
        root.addView(modeSpinner)
        root.addView(caseSensitiveBox)
        root.addView(wholeWordBox)
        root.addView(saveConfigBtn)

        val scroll = ScrollView(this).apply {
            gravity = Gravity.TOP
            addView(root)
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refreshStats()
        val filter = IntentFilter(ScreenWatcherService.ACTION_CAPTURE_EVENT)
        ContextCompat.registerReceiver(this, captureReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onPause() {
        try {
            unregisterReceiver(captureReceiver)
        } catch (_: Exception) {
        }
        super.onPause()
    }

    private fun refreshStats() {
        val count = ScreenshotStorage.countTodayScreenshots(this)
        val a11yEnabled = isAccessibilityEnabled()
        todayCountText.text = "Today's screenshots: $count  |  Accessibility: ${if (a11yEnabled) "Enabled" else "Off"}"
    }

    private fun startWatcherFlow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        requestScreenCapture()
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        val expected = "$packageName/${AcceptAccessibilityService::class.java.name}"
        while (colonSplitter.hasNext()) {
            if (colonSplitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }
}
