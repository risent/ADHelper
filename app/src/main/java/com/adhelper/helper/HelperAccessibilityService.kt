package com.adhelper.helper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class HelperAccessibilityService : AccessibilityService() {
    data class StatusSnapshot(
        val serviceConnected: Boolean,
        val serverRunning: Boolean,
        val port: Int,
        val lastError: String?,
    )

    private data class TreeSnapshot(
        val rootJson: JSONObject,
        val nodeCount: Int,
        val truncated: Boolean,
        val packageName: String?,
    )

    private data class ClickPlan(
        val matchedNode: JSONObject,
        val actionClickSucceeded: Boolean,
        val tapX: Int,
        val tapY: Int,
    )

    private data class ScrollPlan(
        val actionScrollSucceeded: Boolean,
        val swipeStartX: Float,
        val swipeStartY: Float,
        val swipeEndX: Float,
        val swipeEndY: Float,
    )

    private data class ClickableSnapshot(
        val packageName: String?,
        val clickables: JSONArray,
    )

    private data class ScreenshotPayload(
        val base64: String,
        val mimeType: String,
        val width: Int,
        val height: Int,
    )

    companion object {
        const val SERVER_PORT = 7912
        private const val MAX_TREE_NODES = 1500
        private const val TAG = "ADHelper"
        private const val PREFS_NAME = "helper_status"
        private const val KEY_SERVICE_CONNECTED = "service_connected"
        private const val KEY_SERVER_RUNNING = "server_running"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEEP_LAST_ERROR = "\u0000KEEP\u0000"

        fun statusSnapshot(context: Context): StatusSnapshot {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return StatusSnapshot(
                serviceConnected = prefs.getBoolean(KEY_SERVICE_CONNECTED, false),
                serverRunning = prefs.getBoolean(KEY_SERVER_RUNNING, false),
                port = SERVER_PORT,
                lastError = prefs.getString(KEY_LAST_ERROR, null),
            )
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    private val serverExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var commandHttpServer: CommandHttpServer? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Accessibility service onCreate")
        updateStatus(serviceConnected = false, serverRunning = false, lastError = null)
        startCommandServerAsync()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility service onServiceConnected")
        updateStatus(serviceConnected = true, lastError = null)
        startCommandServerAsync()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally no-op. Commands are pulled over HTTP.
    }

    override fun onInterrupt() {
        // Intentionally no-op.
    }

    override fun onDestroy() {
        Log.d(TAG, "Accessibility service onDestroy")
        stopCommandServer()
        screenshotExecutor.shutdownNow()
        serverExecutor.shutdownNow()
        updateStatus(serviceConnected = false, serverRunning = false, lastError = null)
        super.onDestroy()
    }

    private fun startCommandServerAsync() {
        serverExecutor.execute {
            Log.d(TAG, "startCommandServerAsync invoked")
            if (commandHttpServer?.isRunning() == true) {
                Log.d(TAG, "Command server already marked running")
                updateStatus(serverRunning = true)
                return@execute
            }

            try {
                val server = CommandHttpServer(SERVER_PORT) { method, path, body ->
                    handleHttpRequest(method, path, body)
                }
                server.start()
                commandHttpServer = server
                Log.d(TAG, "Command server start() returned successfully")
                updateStatus(serverRunning = true, lastError = null)
            } catch (exception: Exception) {
                Log.e(TAG, "Failed to start command server", exception)
                updateStatus(
                    serverRunning = false,
                    lastError = exception.message ?: exception.javaClass.simpleName,
                )
            }
        }
    }

    private fun stopCommandServer() {
        Log.d(TAG, "Stopping command server")
        commandHttpServer?.stop()
        commandHttpServer = null
        updateStatus(serverRunning = false)
    }

    private fun handleHttpRequest(
        method: String,
        path: String,
        body: String,
    ): CommandHttpServer.HttpResponse {
        return try {
            when {
                method == "GET" && path == "/health" -> jsonResponse(200, buildHealthResponse())
                method == "POST" && path == "/command" -> {
                    val request = if (body.isBlank()) JSONObject() else JSONObject(body)
                    jsonResponse(200, executeCommand(request))
                }

                method != "GET" && method != "POST" -> jsonResponse(
                    405,
                    errorPayload("Method $method is not supported"),
                )

                else -> jsonResponse(404, errorPayload("Unknown route: $path"))
            }
        } catch (exception: IllegalArgumentException) {
            jsonResponse(400, errorPayload(exception.message ?: "Bad request"))
        } catch (exception: Exception) {
            Log.e(TAG, "Command handling failed", exception)
            updateStatus(lastError = exception.message ?: exception.javaClass.simpleName)
            jsonResponse(500, errorPayload(exception.message ?: "Internal error"))
        }
    }

    private fun buildHealthResponse(): JSONObject = JSONObject()
        .put("ok", true)
        .put("packageName", packageName)
        .put("serviceConnected", statusSnapshot(this).serviceConnected)
        .put("serverRunning", statusSnapshot(this).serverRunning)
        .put("port", SERVER_PORT)
        .put("sdkInt", Build.VERSION.SDK_INT)
        .put("deviceModel", Build.MODEL)
        .put("lastError", statusSnapshot(this).lastError)

    private fun executeCommand(request: JSONObject): JSONObject {
        val command = request.optString("command").trim()
        if (command.isEmpty()) {
            throw IllegalArgumentException("Missing command")
        }

        val result = when (command) {
            "dump_tree" -> dumpTree()
            "list_clickables" -> listClickables(
                visibleOnly = request.optBoolean("visibleOnly", true),
            )
            "click_text" -> clickText(
                text = request.optString("text").trim(),
                exact = request.optBoolean("exact", false),
            )

            "click_point" -> clickPoint(
                x = request.requiredInt("x"),
                y = request.requiredInt("y"),
            )

            "scroll" -> scroll(
                direction = request.optString("direction", "down").lowercase(),
                distanceRatio = request.optDoubleOrDefault("distanceRatio", 0.55).coerceIn(0.2, 0.85),
            )

            "back" -> goBack()
            "screenshot" -> screenshot()
            else -> throw IllegalArgumentException("Unknown command: $command")
        }

        return JSONObject()
            .put("ok", true)
            .put("command", command)
            .put("timestamp", System.currentTimeMillis())
            .put("result", result)
    }

    private fun dumpTree(): JSONObject {
        val snapshot = withActiveRoot { root ->
            captureTree(root)
        }

        return JSONObject()
            .put("packageName", snapshot.packageName)
            .put("nodeCount", snapshot.nodeCount)
            .put("truncated", snapshot.truncated)
            .put("tree", snapshot.rootJson)
    }

    private fun listClickables(visibleOnly: Boolean): JSONObject {
        val snapshot = withActiveRoot { root ->
            captureClickables(root, visibleOnly)
        }

        return JSONObject()
            .put("packageName", snapshot.packageName)
            .put("count", snapshot.clickables.length())
            .put("clickables", snapshot.clickables)
    }

    private fun clickText(
        text: String,
        exact: Boolean,
    ): JSONObject {
        if (text.isBlank()) {
            throw IllegalArgumentException("text is required")
        }

        val clickPlan = withActiveRoot { root ->
            val matchedNode = findMatchingNode(root, text, exact)
                ?: throw IllegalArgumentException("No node matched text: $text")
            val bounds = Rect().also { matchedNode.getBoundsInScreen(it) }
            ClickPlan(
                matchedNode = buildNodeSummary(matchedNode),
                actionClickSucceeded = tryPerformNodeClick(matchedNode),
                tapX = bounds.centerX(),
                tapY = bounds.centerY(),
            )
        }

        val clickMethod: String
        val clickSucceeded: Boolean

        if (clickPlan.actionClickSucceeded) {
            clickMethod = "accessibility_action"
            clickSucceeded = true
        } else {
            clickMethod = "gesture_tap"
            clickSucceeded = dispatchTap(clickPlan.tapX.toFloat(), clickPlan.tapY.toFloat())
        }

        return JSONObject()
            .put("matchedNode", clickPlan.matchedNode)
            .put("clickMethod", clickMethod)
            .put("clicked", clickSucceeded)
            .put("tapX", clickPlan.tapX)
            .put("tapY", clickPlan.tapY)
    }

    private fun clickPoint(
        x: Int,
        y: Int,
    ): JSONObject {
        val clicked = dispatchTap(x.toFloat(), y.toFloat())
        return JSONObject()
            .put("clicked", clicked)
            .put("x", x)
            .put("y", y)
    }

    private fun scroll(
        direction: String,
        distanceRatio: Double,
    ): JSONObject {
        val plan = withActiveRoot { root ->
            val actionScrollSucceeded = tryPerformScrollAction(root, direction)
            val displayBounds = getDisplayBounds()
            val width = displayBounds.width().toFloat()
            val height = displayBounds.height().toFloat()
            val insetX = width * 0.15f
            val insetY = height * 0.18f
            val travelX = width * distanceRatio.toFloat()
            val travelY = height * distanceRatio.toFloat()

            val swipe = when (direction) {
                "up" -> floatArrayOf(
                    width / 2f,
                    height - insetY,
                    width / 2f,
                    (height - insetY - travelY).coerceAtLeast(insetY),
                )

                "down" -> floatArrayOf(
                    width / 2f,
                    insetY,
                    width / 2f,
                    (insetY + travelY).coerceAtMost(height - insetY),
                )

                "left" -> floatArrayOf(
                    width - insetX,
                    height / 2f,
                    (width - insetX - travelX).coerceAtLeast(insetX),
                    height / 2f,
                )

                "right" -> floatArrayOf(
                    insetX,
                    height / 2f,
                    (insetX + travelX).coerceAtMost(width - insetX),
                    height / 2f,
                )

                else -> throw IllegalArgumentException("direction must be one of up/down/left/right")
            }

            ScrollPlan(
                actionScrollSucceeded = actionScrollSucceeded,
                swipeStartX = swipe[0],
                swipeStartY = swipe[1],
                swipeEndX = swipe[2],
                swipeEndY = swipe[3],
            )
        }

        val scrollMethod: String
        val scrollSucceeded: Boolean

        if (plan.actionScrollSucceeded) {
            scrollMethod = "accessibility_action"
            scrollSucceeded = true
        } else {
            scrollMethod = "gesture_swipe"
            scrollSucceeded = dispatchSwipe(
                startX = plan.swipeStartX,
                startY = plan.swipeStartY,
                endX = plan.swipeEndX,
                endY = plan.swipeEndY,
            )
        }

        return JSONObject()
            .put("scrolled", scrollSucceeded)
            .put("scrollMethod", scrollMethod)
            .put("startX", plan.swipeStartX)
            .put("startY", plan.swipeStartY)
            .put("endX", plan.swipeEndX)
            .put("endY", plan.swipeEndY)
    }

    private fun goBack(): JSONObject {
        val success = performGlobalAction(GLOBAL_ACTION_BACK)
        return JSONObject()
            .put("handled", success)
            .put("performed", success)
    }

    private fun screenshot(): JSONObject {
        val payload = takeScreenshotPayload()
        return JSONObject()
            .put("mimeType", payload.mimeType)
            .put("width", payload.width)
            .put("height", payload.height)
            .put("imageBase64", payload.base64)
    }

    private fun captureTree(root: AccessibilityNodeInfo): TreeSnapshot {
        val state = TreeBuildState()
        val json = captureNode(root, state)
        return TreeSnapshot(
            rootJson = json,
            nodeCount = state.nodeCount,
            truncated = state.truncated,
            packageName = root.packageName?.toString(),
        )
    }

    private fun captureClickables(
        root: AccessibilityNodeInfo,
        visibleOnly: Boolean,
    ): ClickableSnapshot {
        val clickables = JSONArray()
        collectClickables(root, mutableListOf(), clickables, visibleOnly)
        return ClickableSnapshot(
            packageName = root.packageName?.toString(),
            clickables = clickables,
        )
    }

    private fun captureNode(
        node: AccessibilityNodeInfo,
        state: TreeBuildState,
    ): JSONObject {
        state.nodeCount += 1
        val json = buildNodeSummary(node)

        if (state.nodeCount >= MAX_TREE_NODES) {
            state.truncated = true
            json.put("truncated", true)
            return json
        }

        val children = JSONArray()
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            children.put(captureNode(child, state))
            if (state.truncated) {
                break
            }
        }
        json.put("children", children)
        return json
    }

    private fun buildNodeSummary(node: AccessibilityNodeInfo): JSONObject {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        return JSONObject()
            .put("text", node.text?.toString())
            .put("contentDescription", node.contentDescription?.toString())
            .put("viewIdResourceName", node.viewIdResourceName)
            .put("className", node.className?.toString())
            .put("packageName", node.packageName?.toString())
            .put("clickable", node.isClickable)
            .put("scrollable", node.isScrollable)
            .put("enabled", node.isEnabled)
            .put("focused", node.isFocused)
            .put("selected", node.isSelected)
            .put("visibleToUser", node.isVisibleToUser)
            .put("bounds", JSONObject()
                .put("left", bounds.left)
                .put("top", bounds.top)
                .put("right", bounds.right)
                .put("bottom", bounds.bottom))
    }

    private fun collectClickables(
        node: AccessibilityNodeInfo,
        path: MutableList<Int>,
        output: JSONArray,
        visibleOnly: Boolean,
    ) {
        if ((!visibleOnly || node.isVisibleToUser) && (node.isClickable || node.isFocusable)) {
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            if (!bounds.isEmpty) {
                output.put(
                    buildNodeSummary(node)
                        .put("path", JSONArray(path))
                        .put("centerX", bounds.centerX())
                        .put("centerY", bounds.centerY()),
                )
            }
        }

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            path.add(index)
            collectClickables(child, path, output, visibleOnly)
            path.removeAt(path.lastIndex)
        }
    }

    private fun findMatchingNode(
        node: AccessibilityNodeInfo,
        query: String,
        exact: Boolean,
    ): AccessibilityNodeInfo? {
        if (matchesQuery(node, query, exact)) {
            return node
        }

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val match = findMatchingNode(child, query, exact)
            if (match != null) {
                return match
            }
        }

        return null
    }

    private fun matchesQuery(
        node: AccessibilityNodeInfo,
        query: String,
        exact: Boolean,
    ): Boolean {
        val candidates = listOf(
            node.text?.toString(),
            node.contentDescription?.toString(),
            node.viewIdResourceName,
        )

        return candidates.any { value ->
            if (value.isNullOrBlank()) {
                false
            } else if (exact) {
                value.equals(query, ignoreCase = true)
            } else {
                value.contains(query, ignoreCase = true)
            }
        }
    }

    private fun tryPerformNodeClick(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node

        while (current != null) {
            if (current.isEnabled && current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }

            current = current.parent
        }

        return false
    }

    private fun tryPerformScrollAction(
        root: AccessibilityNodeInfo,
        direction: String,
    ): Boolean {
        val scrollableNode = findFirstScrollableNode(root) ?: return false
        return when (direction) {
            "up" -> scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            "down" -> scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            "left" -> scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            "right" -> scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            else -> throw IllegalArgumentException("direction must be one of up/down/left/right")
        }
    }

    private fun findFirstScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) {
            return node
        }

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val match = findFirstScrollableNode(child)
            if (match != null) {
                return match
            }
        }

        return null
    }

    private fun dispatchTap(
        x: Float,
        y: Float,
    ): Boolean {
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 80L)
        return dispatchGestureAndAwait(GestureDescription.Builder().addStroke(stroke).build())
    }

    private fun dispatchSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
    ): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 320L)
        return dispatchGestureAndAwait(GestureDescription.Builder().addStroke(stroke).build())
    }

    private fun dispatchGestureAndAwait(gestureDescription: GestureDescription): Boolean {
        val latch = CountDownLatch(1)
        val completed = AtomicBoolean(false)

        mainHandler.post {
            val dispatched = dispatchGesture(
                gestureDescription,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        completed.set(true)
                        latch.countDown()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        completed.set(false)
                        latch.countDown()
                    }
                },
                null,
            )

            if (!dispatched) {
                completed.set(false)
                latch.countDown()
            }
        }

        if (!latch.await(4, TimeUnit.SECONDS)) {
            return false
        }

        return completed.get()
    }

    private fun takeScreenshotPayload(): ScreenshotPayload {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw IllegalStateException("Screenshot requires Android 11 or newer")
        }

        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<ScreenshotPayload?>()
        val errorRef = AtomicReference<Throwable?>()

        mainHandler.post {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                screenshotExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        try {
                            resultRef.set(screenshotResult.toPayload())
                        } catch (throwable: Throwable) {
                            errorRef.set(throwable)
                        } finally {
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        errorRef.set(IllegalStateException("takeScreenshot failed: $errorCode"))
                        latch.countDown()
                    }
                },
            )
        }

        if (!latch.await(8, TimeUnit.SECONDS)) {
            throw IllegalStateException("Timed out while waiting for screenshot")
        }

        errorRef.get()?.let { throw it }
        return resultRef.get() ?: throw IllegalStateException("Screenshot result was empty")
    }

    private fun ScreenshotResult.toPayload(): ScreenshotPayload {
        val buffer = hardwareBuffer
        val width = buffer.width
        val height = buffer.height
        val hardwareBitmap = try {
            Bitmap.wrapHardwareBuffer(buffer, colorSpace)
                ?: throw IllegalStateException("Could not wrap screenshot buffer")
        } finally {
            buffer.close()
        }

        val bitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
        hardwareBitmap.recycle()

        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)
        bitmap.recycle()

        return ScreenshotPayload(
            base64 = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP),
            mimeType = "image/jpeg",
            width = width,
            height = height,
        )
    }

    private fun getDisplayBounds(): Rect {
        val windowManager = getSystemService(WindowManager::class.java)
            ?: throw IllegalStateException("WindowManager unavailable")
        return windowManager.currentWindowMetrics.bounds
    }

    private fun <T> withActiveRoot(block: (AccessibilityNodeInfo) -> T): T {
        val root = runOnMainThread {
            val activeRoot = rootInActiveWindow ?: throw IllegalStateException("No active window")
            AccessibilityNodeInfo.obtain(activeRoot)
        }

        return try {
            block(root)
        } finally {
            root.recycle()
        }
    }

    private fun <T> runOnMainThread(
        timeoutMs: Long = 12000L,
        block: () -> T,
    ): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return block()
        }

        val latch = CountDownLatch(1)
        val valueRef = AtomicReference<Any?>()
        val hasValue = AtomicBoolean(false)
        val errorRef = AtomicReference<Throwable?>()

        mainHandler.post {
            try {
                valueRef.set(block())
                hasValue.set(true)
            } catch (throwable: Throwable) {
                errorRef.set(throwable)
            } finally {
                latch.countDown()
            }
        }

        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("Timed out waiting for main thread")
        }

        errorRef.get()?.let { throw it }
        if (!hasValue.get()) {
            throw IllegalStateException("Main thread returned no value")
        }

        @Suppress("UNCHECKED_CAST")
        return valueRef.get() as T
    }

    private fun jsonResponse(
        statusCode: Int,
        payload: JSONObject,
    ): CommandHttpServer.HttpResponse = CommandHttpServer.HttpResponse(
        statusCode = statusCode,
        body = payload.toString().toByteArray(StandardCharsets.UTF_8),
    )

    private fun errorPayload(message: String): JSONObject = JSONObject()
        .put("ok", false)
        .put("error", message)

    private fun updateStatus(
        serviceConnected: Boolean? = null,
        serverRunning: Boolean? = null,
        lastError: String? = KEEP_LAST_ERROR,
    ) {
        Log.d(
            TAG,
            "updateStatus serviceConnected=$serviceConnected serverRunning=$serverRunning lastError=$lastError",
        )
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            serviceConnected?.let { putBoolean(KEY_SERVICE_CONNECTED, it) }
            serverRunning?.let { putBoolean(KEY_SERVER_RUNNING, it) }
            when (lastError) {
                KEEP_LAST_ERROR -> Unit
                null -> remove(KEY_LAST_ERROR)
                else -> putString(KEY_LAST_ERROR, lastError)
            }
        }.apply()
    }

    private class TreeBuildState {
        var nodeCount: Int = 0
        var truncated: Boolean = false
    }
}

private fun JSONObject.requiredInt(key: String): Int {
    if (!has(key)) {
        throw IllegalArgumentException("Missing $key")
    }
    return try {
        getInt(key)
    } catch (_: Exception) {
        throw IllegalArgumentException("$key must be an integer")
    }
}

private fun JSONObject.optDoubleOrDefault(
    key: String,
    defaultValue: Double,
): Double {
    return if (has(key)) {
        optDouble(key, defaultValue)
    } else {
        defaultValue
    }
}
