package com.adhelper.helper

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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

        binding.openAccessibilitySettingsButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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
        val snapshot = HelperAccessibilityService.statusSnapshot(this)
        binding.serviceStatusText.text = if (snapshot.serviceConnected) {
            "无障碍服务已连接"
        } else {
            "无障碍服务未连接"
        }

        binding.serverStatusText.text = buildString {
            append("HTTP 端口: ${snapshot.port}  ")
            append(if (snapshot.serverRunning) "已监听" else "未监听")
            snapshot.lastError?.takeIf { it.isNotBlank() }?.let { error ->
                append("\n最近错误: $error")
            }
        }
    }
}
