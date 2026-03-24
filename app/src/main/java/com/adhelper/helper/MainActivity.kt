package com.adhelper.helper

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateUtils
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.adhelper.helper.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val mainHandler = Handler(Looper.getMainLooper())

    private val statusRefresh = object : Runnable {
        override fun run() {
            renderStatus()
            mainHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.adbCommandText.text =
            "adb forward tcp:${HelperAccessibilityService.SERVER_PORT} tcp:${HelperAccessibilityService.SERVER_PORT}"

        binding.startHelperButton.setOnClickListener {
            HelperRuntimeService.start(this)
            renderStatus()
        }

        binding.stopHelperButton.setOnClickListener {
            stopService(Intent(this, HelperRuntimeService::class.java))
            renderStatus()
        }

        binding.openAccessibilitySettingsButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.copyAdbCommandButton.setOnClickListener {
            copyToClipboard("adb-forward", binding.adbCommandText.text.toString())
        }

        binding.copyDiagnosticsButton.setOnClickListener {
            val diagnostics = HelperRuntimeStateStore.snapshot(this).toJson().toString(2)
            copyToClipboard("adhelper-diagnostics", diagnostics)
        }

        renderStatus()
    }

    override fun onResume() {
        super.onResume()
        mainHandler.post(statusRefresh)
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(statusRefresh)
    }

    private fun renderStatus() {
        val snapshot = HelperRuntimeStateStore.reconcile(this)
        binding.runtimeStatusText.text = if (snapshot.foregroundServiceRunning) {
            getString(R.string.runtime_running)
        } else {
            getString(R.string.runtime_stopped)
        }

        binding.accessibilityStatusText.text = if (snapshot.accessibilityConnected) {
            getString(R.string.accessibility_connected)
        } else {
            getString(R.string.accessibility_disconnected)
        }

        binding.httpStatusText.text = buildString {
            append("HTTP ${snapshot.port}: ")
            append(if (snapshot.httpServerListening) {
                getString(R.string.http_listening)
            } else {
                getString(R.string.http_not_listening)
            })
        }

        binding.currentAppText.text = buildString {
            append("当前前台应用: ")
            append(snapshot.currentForegroundPackage ?: "未知")
        }

        binding.commandStatusText.text = buildString {
            append("执行中命令: ")
            append(snapshot.activeCommand ?: "无")
            snapshot.lastCommand?.let { lastCommand ->
                append("\n上次命令: ")
                append(lastCommand)
            }
            if (snapshot.lastSuccessAt > 0L) {
                append("\n最近成功: ")
                append(formatTimestamp(snapshot.lastSuccessAt))
            }
            if (snapshot.startedAt > 0L) {
                append("\n运行时长: ")
                append(DateUtils.formatElapsedTime(snapshot.uptimeMs() / 1000L))
            }
        }

        binding.errorStatusText.text = buildString {
            append("最近错误: ")
            append(snapshot.lastErrorMessage ?: "无")
            snapshot.lastErrorCode?.let { code ->
                append("\n错误码: ")
                append(code)
            }
        }

        binding.startHelperButton.isEnabled = !snapshot.foregroundServiceRunning
        binding.stopHelperButton.isEnabled = snapshot.foregroundServiceRunning
    }

    private fun formatTimestamp(timestampMs: Long): CharSequence {
        return DateUtils.getRelativeTimeSpanString(
            timestampMs,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        )
    }

    private fun copyToClipboard(
        label: String,
        text: String,
    ) {
        val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
    }
}
