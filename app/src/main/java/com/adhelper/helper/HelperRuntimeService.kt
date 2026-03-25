package com.adhelper.helper

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
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
        private const val ACTION_RELOAD_TRANSPORT = "com.adhelper.helper.action.RELOAD_TRANSPORT"
        private const val SCHEMA_VERSION = 2

        fun start(context: Context) {
            val intent = Intent(context, HelperRuntimeService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun reloadTransport(context: Context) {
            val intent = Intent(context, HelperRuntimeService::class.java).setAction(ACTION_RELOAD_TRANSPORT)
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

    private data class RuntimeResponse(
        val statusCode: Int,
        val payload: JSONObject,
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
            mainHandler.postDelayed(this, 2_000L)
        }
    }

    private var commandHttpServer: CommandHttpServer? = null
    private var remoteCommandClient: RemoteCommandClient? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Runtime service onCreate")
        val config = HelperRuntimeStateStore.remoteConfig(applicationContext)
        HelperRuntimeStateStore.update(applicationContext) { current ->
            current.copy(
                helperRunning = true,
                foregroundServiceRunning = true,
                httpServerListening = false,
                transportMode = config.transportMode.wireValue,
                remoteServerUrl = config.serverUrl,
                remoteDeviceId = config.deviceId,
                remoteHasToken = !config.sharedToken.isNullOrBlank(),
                remoteConnecting = false,
                remoteConnected = false,
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
        applyTransportMode()
        mainHandler.post(notificationRefresh)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_REFRESH_NOTIFICATION -> updateNotification()
            ACTION_RELOAD_TRANSPORT -> applyTransportMode()
            ACTION_START, null -> {
                HelperRuntimeStateStore.update(applicationContext) { current ->
                    current.copy(
                        helperRunning = true,
                        foregroundServiceRunning = true,
                    )
                }
                applyTransportMode()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(notificationRefresh)
        stopLocalHttpServer()
        remoteCommandClient?.stop()
        remoteCommandClient = null
        commandExecutor.shutdownNow()
        HelperRuntimeStateStore.update(applicationContext) { current ->
            current.copy(
                helperRunning = false,
                foregroundServiceRunning = false,
                httpServerListening = false,
                remoteConnecting = false,
                remoteConnected = false,
                activeCommand = null,
                activeRequestId = null,
                activeCommandStartedAt = 0L,
            )
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun applyTransportMode() {
        val config = HelperRuntimeStateStore.remoteConfig(applicationContext)
        HelperRuntimeStateStore.update(applicationContext) { current ->
            current.copy(
                transportMode = config.transportMode.wireValue,
                remoteServerUrl = config.serverUrl,
                remoteDeviceId = config.deviceId,
                remoteHasToken = !config.sharedToken.isNullOrBlank(),
                lastErrorCode = null,
                lastErrorMessage = null,
            )
        }

        if (config.transportMode == TransportMode.LOCAL) {
            remoteCommandClient?.stop()
            remoteCommandClient = null
            setRemoteState(connecting = false, connected = false, errorMessage = null)
            startLocalHttpServer()
        } else {
            stopLocalHttpServer()
            startRemoteClient()
        }
        updateNotification()
    }

    private fun startLocalHttpServer() {
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
            Log.e(TAG, "Failed to start local HTTP server", exception)
            HelperRuntimeStateStore.update(applicationContext) { current ->
                current.copy(
                    httpServerListening = false,
                    lastErrorCode = "SERVER_START_FAILED",
                    lastErrorMessage = exception.message ?: exception.javaClass.simpleName,
                )
            }
        }
    }

    private fun stopLocalHttpServer() {
        commandHttpServer?.stop()
        commandHttpServer = null
        HelperRuntimeStateStore.update(applicationContext) { current ->
            current.copy(httpServerListening = false)
        }
    }

    private fun startRemoteClient() {
        if (remoteCommandClient == null) {
            remoteCommandClient = RemoteCommandClient(
                configProvider = { HelperRuntimeStateStore.remoteConfig(applicationContext) },
                helloPayloadProvider = { buildHelloPayload() },
                statusPayloadProvider = { buildHealthPayload() },
                commandHandler = { requestId, payload ->
                    val response = executeCommandRequest(payload, requestId)
                    RemoteCommandClient.DispatchResult(response.statusCode, response.payload)
                },
                stateListener = { connecting, connected, errorMessage ->
                    setRemoteState(connecting, connected, errorMessage)
                },
            )
        }
        remoteCommandClient?.start()
    }

    private fun setRemoteState(connecting: Boolean, connected: Boolean, errorMessage: String?) {
        HelperRuntimeStateStore.update(applicationContext) { current ->
            current.copy(
                remoteConnecting = connecting,
                remoteConnected = connected,
                lastErrorCode = if (errorMessage.isNullOrBlank()) current.lastErrorCode else "REMOTE_CONNECTION",
                lastErrorMessage = errorMessage ?: current.lastErrorMessage,
            )
        }
        updateNotification()
    }

    private fun buildHelloPayload(): JSONObject {
        val snapshot = HelperRuntimeStateStore.snapshot(applicationContext)
        return JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("deviceId", snapshot.remoteDeviceId)
            .put("transportMode", snapshot.transportMode)
            .put("deviceModel", Build.MODEL)
            .put("sdkInt", Build.VERSION.SDK_INT)
            .put("packageName", packageName)
            .put("health", buildHealthPayload())
            .put(
                "supportedCommands",
                listOf(
                    "current_app",
                    "dump_tree",
                    "snapshot",
                    "list_clickables",
                    "click_text",
                    "click_node",
                    "click_point",
                    "scroll",
                    "back",
                    "wait_for_stable_tree",
                    "screenshot",
                ),
            )
    }

    private fun handleHttpRequest(
        method: String,
        path: String,
        body: String,
    ): CommandHttpServer.HttpResponse {
        val response = when {
            method == "GET" && path == "/health" -> buildHealthResponse()
            method == "POST" && path == "/command" -> executeCommandRequest(if (body.isBlank()) JSONObject() else JSONObject(body))
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
        return CommandHttpServer.HttpResponse(
            statusCode = response.statusCode,
            body = response.payload.toString().toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun buildHealthResponse(): RuntimeResponse {
        val payload = JSONObject()
            .put("ok", true)
            .put("schemaVersion", SCHEMA_VERSION)
            .put("requestId", "health")
            .put("command", "health")
            .put("timestamp", System.currentTimeMillis())
            .put("durationMs", 0L)
            .put("result", buildHealthPayload())
        return RuntimeResponse(statusCode = 200, payload = payload)
    }

    private fun executeCommandRequest(
        request: JSONObject,
        requestId: String = UUID.randomUUID().toString(),
    ): RuntimeResponse {
        val command = request.optString("command").trim()
        val startedAt = System.currentTimeMillis()

        if (command.isBlank()) {
            return errorResponse(400, requestId, "BAD_REQUEST", "Missing command")
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
                .put("elapsedMs", currentActive?.let { System.currentTimeMillis() - it.startedAt } ?: 0L)
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

        val response = try {
            val result = future.get(commandTimeoutMs(command, request), TimeUnit.MILLISECONDS)
            HelperRuntimeStateStore.update(applicationContext) { current ->
                current.copy(
                    lastCommand = command,
                    lastSuccessAt = System.currentTimeMillis(),
                    lastErrorCode = null,
                    lastErrorMessage = null,
                )
            }
            successResponse(command, requestId, startedAt, result)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            errorResponse(500, requestId, "COMMAND_INTERRUPTED", "Command $command was interrupted")
        } catch (_: TimeoutException) {
            future.cancel(true)
            errorResponse(504, requestId, "COMMAND_TIMEOUT", "Command $command timed out")
        } catch (exception: ExecutionException) {
            val failure = classifyFailure(exception.cause ?: exception)
            errorResponse(failure.statusCode, requestId, failure.errorCode, failure.message)
        }

        HelperRuntimeStateStore.update(applicationContext) { current ->
            current.copy(
                lastCommand = command,
                lastErrorCode = if (response.payload.optBoolean("ok")) null else response.payload.optString("errorCode"),
                lastErrorMessage = if (response.payload.optBoolean("ok")) null else response.payload.optString("error"),
            )
        }
        remoteCommandClient?.sendStatusUpdate("command_result")
        updateNotification()
        return response
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

    private fun commandTimeoutMs(command: String, request: JSONObject): Long = when (command.lowercase(Locale.US)) {
        "click_text", "click_node", "click_point", "back" -> 5_000L
        "scroll" -> 8_000L
        "screenshot" -> 12_000L
        "dump_tree", "list_clickables", "snapshot" -> 15_000L
        "wait_for_stable_tree" -> request.optLong("timeoutMs", 10_000L).coerceIn(500L, 60_000L) + 2_000L
        else -> 10_000L
    }

    private fun classifyFailure(throwable: Throwable): CommandFailure {
        if (throwable is HelperCommandException) {
            return CommandFailure(throwable.statusCode, throwable.errorCode, throwable.message)
        }

        val message = throwable.message ?: throwable.javaClass.simpleName
        return when {
            throwable is IllegalArgumentException && message.startsWith("No node matched") -> CommandFailure(400, "NODE_NOT_FOUND", message)
            message.contains("No active window", ignoreCase = true) -> CommandFailure(503, "NO_ACTIVE_WINDOW", message)
            message.contains("Timed out while waiting for screenshot", ignoreCase = true) ||
                message.contains("takeScreenshot failed", ignoreCase = true) -> CommandFailure(503, "SCREENSHOT_FAILED", message)

            message.contains("Timed out waiting for main thread", ignoreCase = true) -> CommandFailure(503, "MAIN_THREAD_TIMEOUT", message)
            message.contains("Missing command", ignoreCase = true) -> CommandFailure(400, "BAD_REQUEST", message)
            else -> CommandFailure(500, "INTERNAL_ERROR", message)
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
    ): RuntimeResponse {
        val payload = JSONObject()
            .put("ok", true)
            .put("schemaVersion", SCHEMA_VERSION)
            .put("requestId", requestId)
            .put("command", command)
            .put("timestamp", System.currentTimeMillis())
            .put("durationMs", System.currentTimeMillis() - startedAt)
            .put("result", result)
        return RuntimeResponse(200, payload)
    }

    private fun errorResponse(
        statusCode: Int,
        requestId: String,
        errorCode: String,
        message: String,
        extra: JSONObject? = null,
    ): RuntimeResponse {
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
        return RuntimeResponse(statusCode, payload)
    }

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
            if (snapshot.transportMode == TransportMode.REMOTE.wireValue) {
                "Remote ${snapshot.remoteDeviceId ?: "未配置"} · ${if (snapshot.remoteConnected) "已连接" else "未连接"}"
            } else {
                "HTTP ${snapshot.port} · ${if (snapshot.accessibilityConnected) "无障碍已连接" else "无障碍未连接"}"
            },
        )
        .setStyle(
            NotificationCompat.BigTextStyle().bigText(
                buildString {
                    append("模式: ")
                    append(snapshot.transportMode)
                    append('\n')
                    if (snapshot.transportMode == TransportMode.REMOTE.wireValue) {
                        append("Remote: ")
                        append(if (snapshot.remoteConnected) "已连接" else if (snapshot.remoteConnecting) "连接中" else "未连接")
                        snapshot.remoteServerUrl?.let {
                            append('\n')
                            append("Server: ")
                            append(it)
                        }
                    } else {
                        append("HTTP ${snapshot.port}: ")
                        append(if (snapshot.httpServerListening) "已监听" else "未监听")
                    }
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
