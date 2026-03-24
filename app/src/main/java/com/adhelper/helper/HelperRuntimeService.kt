package com.adhelper.helper

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

class HelperRuntimeService : Service() {
    companion object {
        private const val TAG = "ADHelperRuntime"
        private const val CHANNEL_ID = "adhelper-runtime"
        private const val NOTIFICATION_ID = 7912
        private const val ACTION_START = "com.adhelper.helper.action.START"
        private const val ACTION_STOP = "com.adhelper.helper.action.STOP"
        private const val ACTION_REFRESH_NOTIFICATION = "com.adhelper.helper.action.REFRESH_NOTIFICATION"
        private const val SCHEMA_VERSION = 2

        fun start(context: Context) {
            val intent = Intent(context, HelperRuntimeService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun isRunning(context: Context): Boolean {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return false
            @Suppress("DEPRECATION")
            return activityManager.getRunningServices(Int.MAX_VALUE).any { serviceInfo ->
                serviceInfo.service.className == HelperRuntimeService::class.java.name
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, HelperRuntimeService::class.java).setAction(ACTION_STOP))
        }
    }

    private data class ActiveCommand(
        val requestId: String,
        val command: String,
        val startedAt: Long,
    )

    private data class CommandFailure(
        val statusCode: Int,
        val errorCode: String,
        val message: String,
    )

    private class HelperCommandException(
        val statusCode: Int,
        val errorCode: String,
        override val message: String,
    ) : RuntimeException(message)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val commandExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val activeCommand = AtomicReference<ActiveCommand?>()
    private val notificationRefresh = object : Runnable {
        override fun run() {
            updateNotification()
            mainHandler.postDelayed(this, 2000L)
        }
    }

    private var commandHttpServer: CommandHttpServer? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Runtime service onCreate")
        HelperRuntimeStateStore.update(applicationContext) { current ->
            current.copy(
                helperRunning = true,
                foregroundServiceRunning = true,
                httpServerListening = false,
                activeCommand = null,
                activeRequestId = null,
                activeCommandStartedAt = 0L,
                startedAt = System.currentTimeMillis(),
            )
        }
        createNotificationChannel()
        val notification = buildNotification(HelperRuntimeStateStore.snapshot(applicationContext))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        startCommandServer()
        mainHandler.post(notificationRefresh)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Runtime service onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_REFRESH_NOTIFICATION -> updateNotification()
            ACTION_START, null -> {
                HelperRuntimeStateStore.update(applicationContext) { current ->
                    current.copy(
                        helperRunning = true,
                        foregroundServiceRunning = true,
                    )
                }
                if (commandHttpServer?.isRunning() != true) {
                    startCommandServer()
                } else {
                    updateNotification()
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "Runtime service onDestroy")
        mainHandler.removeCallbacks(notificationRefresh)
        commandHttpServer?.stop()
        commandHttpServer = null
        commandExecutor.shutdownNow()
        HelperRuntimeStateStore.update(applicationContext) { current ->
            current.copy(
                helperRunning = false,
                foregroundServiceRunning = false,
                httpServerListening = false,
                activeCommand = null,
                activeRequestId = null,
                activeCommandStartedAt = 0L,
            )
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startCommandServer() {
        if (commandHttpServer?.isRunning() == true) {
            return
        }

        try {
            val server = CommandHttpServer(HelperAccessibilityService.SERVER_PORT) { method, path, body ->
                handleHttpRequest(method, path, body)
            }
            server.start()
            commandHttpServer = server
            HelperRuntimeStateStore.update(applicationContext) { current ->
                current.copy(
                    httpServerListening = true,
                    lastErrorCode = null,
                    lastErrorMessage = null,
                )
            }
        } catch (exception: Exception) {
            Log.e(TAG, "Failed to start runtime HTTP server", exception)
            HelperRuntimeStateStore.update(applicationContext) { current ->
                current.copy(
                    httpServerListening = false,
                    lastErrorCode = "SERVER_START_FAILED",
                    lastErrorMessage = exception.message ?: exception.javaClass.simpleName,
                )
            }
        }
        updateNotification()
    }

    private fun handleHttpRequest(
        method: String,
        path: String,
        body: String,
    ): CommandHttpServer.HttpResponse {
        return try {
            when {
                method == "GET" && path == "/health" -> successResponse(
                    command = "health",
                    requestId = "health",
                    startedAt = System.currentTimeMillis(),
                    result = buildHealthPayload(),
                )

                method == "POST" && path == "/command" -> handleCommandRequest(body)

                method != "GET" && method != "POST" -> errorResponse(
                    statusCode = 405,
                    requestId = "n/a",
                    errorCode = "METHOD_NOT_ALLOWED",
                    message = "Method $method is not supported",
                )

                else -> errorResponse(
                    statusCode = 404,
                    requestId = "n/a",
                    errorCode = "NOT_FOUND",
                    message = "Unknown route: $path",
                )
            }
        } catch (exception: Exception) {
            Log.e(TAG, "HTTP request failed", exception)
            errorResponse(
                statusCode = 500,
                requestId = "n/a",
                errorCode = "INTERNAL_ERROR",
                message = exception.message ?: "Internal error",
            )
        }
    }

    private fun handleCommandRequest(body: String): CommandHttpServer.HttpResponse {
        val request = if (body.isBlank()) JSONObject() else JSONObject(body)
        val command = request.optString("command").trim()
        val requestId = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()

        if (command.isBlank()) {
            return errorResponse(
                statusCode = 400,
                requestId = requestId,
                errorCode = "BAD_REQUEST",
                message = "Missing command",
            )
        }

        if (command == "current_app") {
            val snapshot = HelperRuntimeStateStore.snapshot(applicationContext)
            val result = JSONObject()
                .put("packageName", snapshot.currentForegroundPackage)
                .put("helperPackageName", packageName)
            return successResponse(command, requestId, startedAt, result)
        }

        val active = ActiveCommand(requestId = requestId, command = command, startedAt = startedAt)
        if (!activeCommand.compareAndSet(null, active)) {
            val currentActive = activeCommand.get()
            val busyPayload = JSONObject()
                .put("activeCommand", currentActive?.command)
                .put("activeRequestId", currentActive?.requestId)
                .put(
                    "elapsedMs",
                    currentActive?.let { System.currentTimeMillis() - it.startedAt } ?: 0L,
                )
            return errorResponse(
                statusCode = 409,
                requestId = requestId,
                errorCode = "BUSY",
                message = "Helper is already executing ${currentActive?.command ?: "another"} command",
                extra = busyPayload,
            )
        }

        HelperRuntimeStateStore.update(applicationContext) { current ->
            current.copy(
                activeCommand = command,
                activeRequestId = requestId,
                activeCommandStartedAt = startedAt,
                lastErrorCode = null,
                lastErrorMessage = null,
            )
        }
        updateNotification()

        val future: Future<JSONObject> = commandExecutor.submit<JSONObject> {
            try {
                executeCommand(request)
            } finally {
                activeCommand.compareAndSet(active, null)
                HelperRuntimeStateStore.update(applicationContext) { current ->
                    if (current.activeRequestId == requestId) {
                        current.copy(
                            activeCommand = null,
                            activeRequestId = null,
                            activeCommandStartedAt = 0L,
                        )
                    } else {
                        current
                    }
                }
                updateNotification()
            }
        }

        return try {
            val result = future.get(commandTimeoutMs(command, request), TimeUnit.MILLISECONDS)
            HelperRuntimeStateStore.update(applicationContext) { current ->
                current.copy(
                    lastCommand = command,
                    lastSuccessAt = System.currentTimeMillis(),
                    lastErrorCode = null,
                    lastErrorMessage = null,
                )
            }
            updateNotification()
            successResponse(command, requestId, startedAt, result)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            HelperRuntimeStateStore.update(applicationContext) { current ->
                current.copy(
                    lastCommand = command,
                    lastErrorCode = "COMMAND_INTERRUPTED",
                    lastErrorMessage = "Command $command was interrupted",
                )
            }
            updateNotification()
            errorResponse(
                statusCode = 500,
                requestId = requestId,
                errorCode = "COMMAND_INTERRUPTED",
                message = "Command $command was interrupted",
            )
        } catch (_: TimeoutException) {
            future.cancel(true)
            HelperRuntimeStateStore.update(applicationContext) { current ->
                current.copy(
                    lastCommand = command,
                    lastErrorCode = "COMMAND_TIMEOUT",
                    lastErrorMessage = "Command $command timed out",
                )
            }
            updateNotification()
            errorResponse(
                statusCode = 504,
                requestId = requestId,
                errorCode = "COMMAND_TIMEOUT",
                message = "Command $command timed out",
            )
        } catch (exception: ExecutionException) {
            val cause = exception.cause ?: exception
            val failure = classifyFailure(cause)
            HelperRuntimeStateStore.update(applicationContext) { current ->
                current.copy(
                    lastCommand = command,
                    lastErrorCode = failure.errorCode,
                    lastErrorMessage = failure.message,
                )
            }
            updateNotification()
            errorResponse(
                statusCode = failure.statusCode,
                requestId = requestId,
                errorCode = failure.errorCode,
                message = failure.message,
            )
        }
    }

    private fun executeCommand(request: JSONObject): JSONObject {
        val service = HelperAccessibilityService.activeInstance()
            ?: throw HelperCommandException(
                statusCode = 503,
                errorCode = "ACCESSIBILITY_NOT_READY",
                message = "Accessibility service is not connected",
            )
        return service.executeRuntimeCommand(request)
    }

    private fun commandTimeoutMs(
        command: String,
        request: JSONObject,
    ): Long = when (command.lowercase(Locale.US)) {
        "click_text", "click_node", "click_point", "back" -> 5_000L
        "scroll" -> 8_000L
        "screenshot" -> 12_000L
        "dump_tree", "list_clickables", "snapshot" -> 15_000L
        "wait_for_stable_tree" -> request.optLong("timeoutMs", 10_000L).coerceIn(500L, 60_000L) + 2_000L
        else -> 10_000L
    }

    private fun classifyFailure(throwable: Throwable): CommandFailure {
        if (throwable is HelperCommandException) {
            return CommandFailure(
                statusCode = throwable.statusCode,
                errorCode = throwable.errorCode,
                message = throwable.message,
            )
        }

        val message = throwable.message ?: throwable.javaClass.simpleName
        return when {
            throwable is IllegalArgumentException && message.startsWith("No node matched") -> CommandFailure(
                statusCode = 400,
                errorCode = "NODE_NOT_FOUND",
                message = message,
            )

            message.contains("No active window", ignoreCase = true) -> CommandFailure(
                statusCode = 503,
                errorCode = "NO_ACTIVE_WINDOW",
                message = message,
            )

            message.contains("Timed out while waiting for screenshot", ignoreCase = true) ||
                message.contains("takeScreenshot failed", ignoreCase = true) -> CommandFailure(
                    statusCode = 503,
                    errorCode = "SCREENSHOT_FAILED",
                    message = message,
                )

            message.contains("Timed out waiting for main thread", ignoreCase = true) -> CommandFailure(
                statusCode = 503,
                errorCode = "MAIN_THREAD_TIMEOUT",
                message = message,
            )

            message.contains("Missing command", ignoreCase = true) -> CommandFailure(
                statusCode = 400,
                errorCode = "BAD_REQUEST",
                message = message,
            )

            else -> CommandFailure(
                statusCode = 500,
                errorCode = "INTERNAL_ERROR",
                message = message,
            )
        }
    }

    private fun buildHealthPayload(): JSONObject {
        val snapshot = HelperRuntimeStateStore.snapshot(applicationContext)
        return snapshot.toJson()
            .put("helperPackageName", packageName)
            .put("schemaVersion", SCHEMA_VERSION)
            .put("packageName", packageName)
            .put("serviceConnected", snapshot.accessibilityConnected)
            .put("serverRunning", snapshot.httpServerListening)
            .put("port", snapshot.port)
            .put("lastError", snapshot.lastErrorMessage)
            .put("accessibilityReady", snapshot.accessibilityConnected)
            .put("busy", snapshot.activeCommand != null)
            .put("sdkInt", Build.VERSION.SDK_INT)
            .put("deviceModel", Build.MODEL)
    }

    private fun successResponse(
        command: String,
        requestId: String,
        startedAt: Long,
        result: JSONObject,
    ): CommandHttpServer.HttpResponse {
        val payload = JSONObject()
            .put("ok", true)
            .put("schemaVersion", SCHEMA_VERSION)
            .put("requestId", requestId)
            .put("command", command)
            .put("timestamp", System.currentTimeMillis())
            .put("durationMs", System.currentTimeMillis() - startedAt)
            .put("result", result)
        return jsonResponse(200, payload)
    }

    private fun errorResponse(
        statusCode: Int,
        requestId: String,
        errorCode: String,
        message: String,
        extra: JSONObject? = null,
    ): CommandHttpServer.HttpResponse {
        val payload = JSONObject()
            .put("ok", false)
            .put("schemaVersion", SCHEMA_VERSION)
            .put("requestId", requestId)
            .put("errorCode", errorCode)
            .put("error", message)
        extra?.let { extraJson ->
            extraJson.keys().forEach { key ->
                payload.put(key, extraJson.get(key))
            }
        }
        return jsonResponse(statusCode, payload)
    }

    private fun jsonResponse(
        statusCode: Int,
        payload: JSONObject,
    ): CommandHttpServer.HttpResponse = CommandHttpServer.HttpResponse(
        statusCode = statusCode,
        body = payload.toString().toByteArray(StandardCharsets.UTF_8),
    )

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AD Helper Runtime",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps the AD Helper runtime available in the background."
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(snapshot: HelperRuntimeSnapshot) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
        .setContentTitle("AD Helper 正在运行")
        .setContentText(
            "HTTP ${snapshot.port} · ${if (snapshot.accessibilityConnected) "无障碍已连接" else "无障碍未连接"}",
        )
        .setStyle(
            NotificationCompat.BigTextStyle().bigText(
                buildString {
                    append("HTTP ${snapshot.port}: ")
                    append(if (snapshot.httpServerListening) "已监听" else "未监听")
                    append('\n')
                    append("无障碍: ")
                    append(if (snapshot.accessibilityConnected) "已连接" else "未连接")
                    snapshot.currentForegroundPackage?.let {
                        append('\n')
                        append("当前前台: ")
                        append(it)
                    }
                    snapshot.activeCommand?.let {
                        append('\n')
                        append("执行中: ")
                        append(it)
                    }
                    snapshot.lastErrorMessage?.takeIf { it.isNotBlank() }?.let {
                        append('\n')
                        append("最近错误: ")
                        append(it)
                    }
                },
            ),
        )
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                1,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .addAction(
            0,
            "停止 helper",
            PendingIntent.getService(
                this,
                2,
                Intent(this, HelperRuntimeService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(HelperRuntimeStateStore.snapshot(applicationContext)))
    }
}
