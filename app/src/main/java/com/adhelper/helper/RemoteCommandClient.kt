package com.adhelper.helper

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RemoteCommandClient(
    private val configProvider: () -> RemoteRuntimeConfig,
    private val helloPayloadProvider: () -> JSONObject,
    private val statusPayloadProvider: () -> JSONObject,
    private val commandHandler: (requestId: String, payload: JSONObject) -> DispatchResult,
    private val stateListener: (connecting: Boolean, connected: Boolean, errorMessage: String?) -> Unit,
) {
    data class DispatchResult(
        val statusCode: Int,
        val payload: JSONObject,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val sendExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val reconnectDelaysMs = longArrayOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)
    private val reconnectRunnable = Runnable { connectInternal() }

    @Volatile
    private var stopped = false
    private var reconnectAttempt = 0
    private var webSocket: WebSocket? = null
    private var httpClient: OkHttpClient? = null

    fun start() {
        stopped = false
        reconnectAttempt = 0
        connectInternal()
    }

    fun stop() {
        stopped = true
        mainHandler.removeCallbacks(reconnectRunnable)
        webSocket?.close(1000, "stopped")
        webSocket = null
        httpClient?.dispatcher?.executorService?.shutdown()
        httpClient?.connectionPool?.evictAll()
        httpClient = null
        sendExecutor.shutdownNow()
        stateListener(false, false, null)
    }

    fun sendStatusUpdate(reason: String = "runtime_update") {
        sendEvent(
            JSONObject()
                .put("type", "client.status.update")
                .put("deviceId", configProvider().deviceId)
                .put("reason", reason)
                .put("status", statusPayloadProvider()),
        )
    }

    private fun connectInternal() {
        val config = configProvider()
        if (stopped) {
            return
        }
        if (config.transportMode != TransportMode.REMOTE) {
            stateListener(false, false, null)
            return
        }
        if (config.serverUrl.isNullOrBlank() || config.sharedToken.isNullOrBlank()) {
            stateListener(false, false, "Remote mode requires server URL and token")
            return
        }

        stateListener(true, false, null)
        val request = Request.Builder()
            .url(toWebSocketUrl(config.serverUrl) + "/ws/client?token=" + config.sharedToken)
            .build()

        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        httpClient = client
        webSocket = client.newWebSocket(request, Listener())
    }

    private fun scheduleReconnect(errorMessage: String?) {
        if (stopped) {
            return
        }
        stateListener(false, false, errorMessage)
        mainHandler.removeCallbacks(reconnectRunnable)
        val index = reconnectAttempt.coerceAtMost(reconnectDelaysMs.lastIndex)
        val delayMs = reconnectDelaysMs[index]
        reconnectAttempt += 1
        mainHandler.postDelayed(reconnectRunnable, delayMs)
    }

    private fun sendEvent(payload: JSONObject) {
        val socket = webSocket ?: return
        sendExecutor.execute {
            socket.send(payload.toString())
        }
    }

    private fun toWebSocketUrl(serverUrl: String): String {
        val normalized = serverUrl.trim().removeSuffix("/")
        return when {
            normalized.startsWith("https://") -> "wss://" + normalized.removePrefix("https://")
            normalized.startsWith("http://") -> "ws://" + normalized.removePrefix("http://")
            normalized.startsWith("wss://") || normalized.startsWith("ws://") -> normalized
            else -> "ws://$normalized"
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectAttempt = 0
            stateListener(false, true, null)
            sendEvent(
                JSONObject()
                    .put("type", "client.hello")
                    .put("deviceId", configProvider().deviceId)
                    .put("hello", helloPayloadProvider()),
            )
            sendStatusUpdate("connected")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = JSONObject(text)
            when (message.optString("type")) {
                "server.command.dispatch" -> {
                    val requestId = message.optString("requestId").ifBlank {
                        java.util.UUID.randomUUID().toString()
                    }
                    val payload = message.optJSONObject("payload") ?: JSONObject()
                    sendExecutor.execute {
                        val response = commandHandler(requestId, payload)
                        sendEvent(
                            JSONObject()
                                .put("type", "client.command.result")
                                .put("deviceId", configProvider().deviceId)
                                .put("requestId", requestId)
                                .put("statusCode", response.statusCode)
                                .put("response", response.payload),
                        )
                        sendStatusUpdate("command_complete")
                    }
                }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            scheduleReconnect(if (reason.isBlank()) "Remote socket closed" else reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            scheduleReconnect(t.message ?: "Remote socket failure")
        }
    }
}
