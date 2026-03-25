package com.adhelper.helper

import android.content.Context
import org.json.JSONObject

data class HelperRuntimeSnapshot(
    val port: Int = HelperAccessibilityService.SERVER_PORT,
    val helperRunning: Boolean = false,
    val foregroundServiceRunning: Boolean = false,
    val httpServerListening: Boolean = false,
    val transportMode: String = TransportMode.LOCAL.wireValue,
    val remoteServerUrl: String? = null,
    val remoteDeviceId: String? = null,
    val remoteHasToken: Boolean = false,
    val remoteConnecting: Boolean = false,
    val remoteConnected: Boolean = false,
    val accessibilityConnected: Boolean = false,
    val currentForegroundPackage: String? = null,
    val activeCommand: String? = null,
    val activeRequestId: String? = null,
    val activeCommandStartedAt: Long = 0L,
    val lastCommand: String? = null,
    val lastSuccessAt: Long = 0L,
    val lastErrorCode: String? = null,
    val lastErrorMessage: String? = null,
    val startedAt: Long = 0L,
) {
    fun uptimeMs(nowMs: Long = System.currentTimeMillis()): Long = if (startedAt > 0L) {
        (nowMs - startedAt).coerceAtLeast(0L)
    } else {
        0L
    }

    fun toJson(nowMs: Long = System.currentTimeMillis()): JSONObject = JSONObject()
        .put("port", port)
        .put("helperRunning", helperRunning)
        .put("foregroundServiceRunning", foregroundServiceRunning)
        .put("httpServerListening", httpServerListening)
        .put("transportMode", transportMode)
        .put("remoteServerUrl", remoteServerUrl)
        .put("remoteDeviceId", remoteDeviceId)
        .put("remoteHasToken", remoteHasToken)
        .put("remoteConnecting", remoteConnecting)
        .put("remoteConnected", remoteConnected)
        .put("accessibilityConnected", accessibilityConnected)
        .put("currentForegroundPackage", currentForegroundPackage)
        .put("activeCommand", activeCommand)
        .put("activeRequestId", activeRequestId)
        .put("activeCommandStartedAt", nullableLong(activeCommandStartedAt))
        .put("lastCommand", lastCommand)
        .put("lastSuccessAt", nullableLong(lastSuccessAt))
        .put("lastErrorCode", lastErrorCode)
        .put("lastErrorMessage", lastErrorMessage)
        .put("startedAt", nullableLong(startedAt))
        .put("uptimeMs", uptimeMs(nowMs))

    private fun nullableLong(value: Long): Any? = value.takeIf { it > 0L }
}

object HelperRuntimeStateStore {
    private const val PREFS_NAME = "helper_runtime_state"
    private const val KEY_PORT = "port"
    private const val KEY_HELPER_RUNNING = "helper_running"
    private const val KEY_FOREGROUND_SERVICE_RUNNING = "foreground_service_running"
    private const val KEY_HTTP_SERVER_LISTENING = "http_server_listening"
    private const val KEY_TRANSPORT_MODE = "transport_mode"
    private const val KEY_REMOTE_SERVER_URL = "remote_server_url"
    private const val KEY_REMOTE_DEVICE_ID = "remote_device_id"
    private const val KEY_REMOTE_SHARED_TOKEN = "remote_shared_token"
    private const val KEY_REMOTE_CONNECTING = "remote_connecting"
    private const val KEY_REMOTE_CONNECTED = "remote_connected"
    private const val KEY_ACCESSIBILITY_CONNECTED = "accessibility_connected"
    private const val KEY_CURRENT_FOREGROUND_PACKAGE = "current_foreground_package"
    private const val KEY_ACTIVE_COMMAND = "active_command"
    private const val KEY_ACTIVE_REQUEST_ID = "active_request_id"
    private const val KEY_ACTIVE_COMMAND_STARTED_AT = "active_command_started_at"
    private const val KEY_LAST_COMMAND = "last_command"
    private const val KEY_LAST_SUCCESS_AT = "last_success_at"
    private const val KEY_LAST_ERROR_CODE = "last_error_code"
    private const val KEY_LAST_ERROR_MESSAGE = "last_error_message"
    private const val KEY_STARTED_AT = "started_at"

    private val lock = Any()
    @Volatile
    private var cachedSnapshot: HelperRuntimeSnapshot? = null

    fun snapshot(context: Context): HelperRuntimeSnapshot = synchronized(lock) {
        cachedSnapshot ?: readFromPrefs(context.applicationContext).also { cachedSnapshot = it }
    }

    fun reconcile(context: Context): HelperRuntimeSnapshot = update(context) { current ->
        val runtimeRunning = HelperRuntimeService.isRunning(context.applicationContext)
        val accessibilityConnected = HelperAccessibilityService.activeInstance() != null
        current.copy(
            helperRunning = runtimeRunning,
            foregroundServiceRunning = runtimeRunning,
            httpServerListening = if (runtimeRunning) current.httpServerListening else false,
            remoteConnecting = if (runtimeRunning) current.remoteConnecting else false,
            remoteConnected = if (runtimeRunning) current.remoteConnected else false,
            accessibilityConnected = accessibilityConnected,
            activeCommand = if (runtimeRunning) current.activeCommand else null,
            activeRequestId = if (runtimeRunning) current.activeRequestId else null,
            activeCommandStartedAt = if (runtimeRunning) current.activeCommandStartedAt else 0L,
            startedAt = if (runtimeRunning) current.startedAt else 0L,
        )
    }

    fun update(
        context: Context,
        transform: (HelperRuntimeSnapshot) -> HelperRuntimeSnapshot,
    ): HelperRuntimeSnapshot = synchronized(lock) {
        val current = cachedSnapshot ?: readFromPrefs(context.applicationContext)
        val next = transform(current)
        cachedSnapshot = next
        writeToPrefs(context.applicationContext, next)
        next
    }

    fun reset(context: Context) {
        synchronized(lock) {
            val config = remoteConfig(context)
            cachedSnapshot = HelperRuntimeSnapshot(
                transportMode = config.transportMode.wireValue,
                remoteServerUrl = config.serverUrl,
                remoteDeviceId = config.deviceId,
                remoteHasToken = !config.sharedToken.isNullOrBlank(),
            )
            writeToPrefs(context.applicationContext, cachedSnapshot!!)
        }
    }

    fun remoteConfig(context: Context): RemoteRuntimeConfig = synchronized(lock) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existingDeviceId = prefs.getString(KEY_REMOTE_DEVICE_ID, null)?.trim().orEmpty()
        val deviceId = existingDeviceId.ifBlank { java.util.UUID.randomUUID().toString() }
        if (existingDeviceId != deviceId) {
            prefs.edit().putString(KEY_REMOTE_DEVICE_ID, deviceId).apply()
            cachedSnapshot = cachedSnapshot?.copy(remoteDeviceId = deviceId)
        }
        RemoteRuntimeConfig(
            transportMode = TransportMode.fromWireValue(prefs.getString(KEY_TRANSPORT_MODE, null)),
            serverUrl = prefs.getString(KEY_REMOTE_SERVER_URL, null),
            deviceId = deviceId,
            sharedToken = prefs.getString(KEY_REMOTE_SHARED_TOKEN, null),
        )
    }

    fun updateRemoteConfig(
        context: Context,
        transform: (RemoteRuntimeConfig) -> RemoteRuntimeConfig,
    ): RemoteRuntimeConfig = synchronized(lock) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = remoteConfig(context)
        val next = transform(current)
        prefs.edit()
            .putString(KEY_TRANSPORT_MODE, next.transportMode.wireValue)
            .putString(KEY_REMOTE_SERVER_URL, next.serverUrl?.trim()?.ifBlank { null })
            .putString(KEY_REMOTE_DEVICE_ID, next.deviceId.trim().ifBlank { current.deviceId })
            .putString(KEY_REMOTE_SHARED_TOKEN, next.sharedToken?.trim()?.ifBlank { null })
            .apply()

        cachedSnapshot = (cachedSnapshot ?: readFromPrefs(context.applicationContext)).copy(
            transportMode = next.transportMode.wireValue,
            remoteServerUrl = next.serverUrl?.trim()?.ifBlank { null },
            remoteDeviceId = next.deviceId.trim().ifBlank { current.deviceId },
            remoteHasToken = !next.sharedToken.isNullOrBlank(),
        )
        writeToPrefs(context.applicationContext, cachedSnapshot!!)
        return next
    }

    private fun readFromPrefs(context: Context): HelperRuntimeSnapshot {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return HelperRuntimeSnapshot(
            port = prefs.getInt(KEY_PORT, HelperAccessibilityService.SERVER_PORT),
            helperRunning = prefs.getBoolean(KEY_HELPER_RUNNING, false),
            foregroundServiceRunning = prefs.getBoolean(KEY_FOREGROUND_SERVICE_RUNNING, false),
            httpServerListening = prefs.getBoolean(KEY_HTTP_SERVER_LISTENING, false),
            transportMode = prefs.getString(KEY_TRANSPORT_MODE, TransportMode.LOCAL.wireValue)
                ?: TransportMode.LOCAL.wireValue,
            remoteServerUrl = prefs.getString(KEY_REMOTE_SERVER_URL, null),
            remoteDeviceId = prefs.getString(KEY_REMOTE_DEVICE_ID, null),
            remoteHasToken = !prefs.getString(KEY_REMOTE_SHARED_TOKEN, null).isNullOrBlank(),
            remoteConnecting = prefs.getBoolean(KEY_REMOTE_CONNECTING, false),
            remoteConnected = prefs.getBoolean(KEY_REMOTE_CONNECTED, false),
            accessibilityConnected = prefs.getBoolean(KEY_ACCESSIBILITY_CONNECTED, false),
            currentForegroundPackage = prefs.getString(KEY_CURRENT_FOREGROUND_PACKAGE, null),
            activeCommand = prefs.getString(KEY_ACTIVE_COMMAND, null),
            activeRequestId = prefs.getString(KEY_ACTIVE_REQUEST_ID, null),
            activeCommandStartedAt = prefs.getLong(KEY_ACTIVE_COMMAND_STARTED_AT, 0L),
            lastCommand = prefs.getString(KEY_LAST_COMMAND, null),
            lastSuccessAt = prefs.getLong(KEY_LAST_SUCCESS_AT, 0L),
            lastErrorCode = prefs.getString(KEY_LAST_ERROR_CODE, null),
            lastErrorMessage = prefs.getString(KEY_LAST_ERROR_MESSAGE, null),
            startedAt = prefs.getLong(KEY_STARTED_AT, 0L),
        )
    }

    private fun writeToPrefs(
        context: Context,
        snapshot: HelperRuntimeSnapshot,
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_PORT, snapshot.port)
            .putBoolean(KEY_HELPER_RUNNING, snapshot.helperRunning)
            .putBoolean(KEY_FOREGROUND_SERVICE_RUNNING, snapshot.foregroundServiceRunning)
            .putBoolean(KEY_HTTP_SERVER_LISTENING, snapshot.httpServerListening)
            .putString(KEY_TRANSPORT_MODE, snapshot.transportMode)
            .putString(KEY_REMOTE_SERVER_URL, snapshot.remoteServerUrl)
            .putString(KEY_REMOTE_DEVICE_ID, snapshot.remoteDeviceId)
            .putBoolean(KEY_REMOTE_CONNECTING, snapshot.remoteConnecting)
            .putBoolean(KEY_REMOTE_CONNECTED, snapshot.remoteConnected)
            .putBoolean(KEY_ACCESSIBILITY_CONNECTED, snapshot.accessibilityConnected)
            .putString(KEY_CURRENT_FOREGROUND_PACKAGE, snapshot.currentForegroundPackage)
            .putString(KEY_ACTIVE_COMMAND, snapshot.activeCommand)
            .putString(KEY_ACTIVE_REQUEST_ID, snapshot.activeRequestId)
            .putLong(KEY_ACTIVE_COMMAND_STARTED_AT, snapshot.activeCommandStartedAt)
            .putString(KEY_LAST_COMMAND, snapshot.lastCommand)
            .putLong(KEY_LAST_SUCCESS_AT, snapshot.lastSuccessAt)
            .putString(KEY_LAST_ERROR_CODE, snapshot.lastErrorCode)
            .putString(KEY_LAST_ERROR_MESSAGE, snapshot.lastErrorMessage)
            .putLong(KEY_STARTED_AT, snapshot.startedAt)
            .apply()
    }
}
