package com.adhelper.helper

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateUtils
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.adhelper.helper.databinding.ActivityMainBinding
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val mainHandler = Handler(Looper.getMainLooper())

    private val statusRefresh = object : Runnable {
        override fun run() {
            renderStatus()
            mainHandler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.startHelperButton.setOnClickListener {
            HelperRuntimeService.start(this)
            renderStatus()
        }

        binding.stopHelperButton.setOnClickListener {
            HelperRuntimeService.stop(this)
            renderStatus()
        }

        binding.openAccessibilitySettingsButton.setOnClickListener {
            startActivity(android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.copyAdbCommandButton.setOnClickListener {
            copyToClipboard("adb-forward", binding.adbCommandText.text.toString())
        }

        binding.saveRemoteConfigButton.setOnClickListener {
            val nextMode = if (binding.remoteModeRadio.isChecked) TransportMode.REMOTE else TransportMode.LOCAL
            HelperRuntimeStateStore.updateRemoteConfig(this) { current ->
                current.copy(
                    transportMode = nextMode,
                    serverUrl = binding.serverUrlInput.text?.toString()?.trim(),
                    deviceId = binding.deviceIdInput.text?.toString()?.trim().orEmpty().ifBlank { current.deviceId },
                    sharedToken = binding.sharedTokenInput.text?.toString()?.trim(),
                )
            }
            HelperRuntimeService.reloadTransport(this)
            renderStatus()
        }

        binding.copyDiagnosticsButton.setOnClickListener {
            val snapshot = HelperRuntimeStateStore.snapshot(this).toJson()
            val config = HelperRuntimeStateStore.remoteConfig(this)
            snapshot.put(
                "remoteConfig",
                JSONObject()
                    .put("transportMode", config.transportMode.wireValue)
                    .put("serverUrl", config.serverUrl)
                    .put("deviceId", config.deviceId)
                    .put("hasToken", !config.sharedToken.isNullOrBlank()),
            )
            copyToClipboard("adhelper-diagnostics", snapshot.toString(2))
        }

        populateConfigFields()
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

    private fun populateConfigFields() {
        val config = HelperRuntimeStateStore.remoteConfig(this)
        binding.localModeRadio.isChecked = config.transportMode == TransportMode.LOCAL
        binding.remoteModeRadio.isChecked = config.transportMode == TransportMode.REMOTE
        binding.serverUrlInput.setText(config.serverUrl.orEmpty())
        binding.deviceIdInput.setText(config.deviceId)
        binding.sharedTokenInput.setText(config.sharedToken.orEmpty())
    }

    private fun renderStatus() {
        val snapshot = HelperRuntimeStateStore.reconcile(this)
        binding.adbCommandText.text =
            "adb forward tcp:${HelperAccessibilityService.SERVER_PORT} tcp:${HelperAccessibilityService.SERVER_PORT}"

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
            append("本地 HTTP ${snapshot.port}: ")
            append(if (snapshot.httpServerListening) getString(R.string.http_listening) else getString(R.string.http_not_listening))
        }

        binding.remoteStatusText.text = buildString {
            append("远程模式: ")
            append(snapshot.transportMode)
            append("\n设备 ID: ")
            append(snapshot.remoteDeviceId ?: "未配置")
            append("\n连接状态: ")
            append(
                when {
                    snapshot.remoteConnected -> "已连接"
                    snapshot.remoteConnecting -> "连接中"
                    else -> "未连接"
                },
            )
            snapshot.remoteServerUrl?.let {
                append("\nServer: ")
                append(it)
            }
        }

        binding.currentAppText.text = buildString {
            append("当前前台应用: ")
            append(snapshot.currentForegroundPackage ?: "未知")
        }

        binding.commandStatusText.text = buildString {
            append("执行中命令: ")
            append(snapshot.activeCommand ?: "无")
            snapshot.lastCommand?.let {
                append("\n上次命令: ")
                append(it)
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
            snapshot.lastErrorCode?.let {
                append("\n错误码: ")
                append(it)
            }
        }

        binding.copyAdbCommandButton.isEnabled = snapshot.transportMode == TransportMode.LOCAL.wireValue
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

    private fun copyToClipboard(label: String, text: String) {
        val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
    }
}
