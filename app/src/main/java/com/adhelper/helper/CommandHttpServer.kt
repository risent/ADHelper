package com.adhelper.helper

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CommandHttpServer(
    private val port: Int,
    private val requestHandler: (method: String, path: String, body: String) -> HttpResponse,
) {
    companion object {
        private const val TAG = "ADHelper"
    }

    data class HttpResponse(
        val statusCode: Int,
        val contentType: String = "application/json; charset=utf-8",
        val body: ByteArray = ByteArray(0),
    )

    private val running = AtomicBoolean(false)
    private val clientExecutor: ExecutorService = Executors.newCachedThreadPool()
    private var acceptThread: Thread? = null
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) {
            Log.d(TAG, "HTTP server already running on port $port")
            return
        }

        Log.d(TAG, "Binding HTTP server to 127.0.0.1:$port")
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
        serverSocket = socket
        Log.d(TAG, "HTTP server bound to ${socket.inetAddress.hostAddress}:${socket.localPort}")

        acceptThread = Thread {
            Log.d(TAG, "HTTP accept thread started on port $port")
            while (running.get()) {
                val client = try {
                    socket.accept()
                } catch (exception: Exception) {
                    Log.w(TAG, "HTTP accept loop stopped", exception)
                    break
                }
                Log.d(TAG, "Accepted HTTP client from ${client.inetAddress.hostAddress}:${client.port}")
                clientExecutor.execute { handleClient(client) }
            }
        }.apply {
            isDaemon = true
            name = "adhelper-http-accept"
            start()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) {
            return
        }

        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        acceptThread?.interrupt()
        clientExecutor.shutdownNow()
    }

    fun isRunning(): Boolean = running.get()

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())

            val response = try {
                parseRequest(input)
            } catch (_: EOFException) {
                Log.w(TAG, "HTTP client disconnected before sending a request")
                null
            } catch (exception: Exception) {
                Log.e(TAG, "HTTP request parsing failed", exception)
                HttpResponse(
                    statusCode = 500,
                    body = """{"ok":false,"error":"${escapeJson(exception.message ?: "Request parse failure")}"}"""
                        .toByteArray(StandardCharsets.UTF_8),
                )
            }

            if (response != null) {
                writeResponse(output, response)
            }
        }
    }

    private fun parseRequest(input: BufferedInputStream): HttpResponse? {
        val requestLine = input.readAsciiLine() ?: return null
        if (requestLine.isBlank()) {
            return null
        }

        val parts = requestLine.split(" ")
        if (parts.size < 2) {
            return HttpResponse(
                statusCode = 400,
                body = """{"ok":false,"error":"Malformed request line"}""".toByteArray(StandardCharsets.UTF_8),
            )
        }

        val method = parts[0].uppercase(Locale.US)
        val path = parts[1].substringBefore("?")
        Log.d(TAG, "HTTP request: $method $path")
        var contentLength = 0

        while (true) {
            val headerLine = input.readAsciiLine() ?: break
            if (headerLine.isEmpty()) {
                break
            }

            val separator = headerLine.indexOf(':')
            if (separator <= 0) {
                continue
            }

            val headerName = headerLine.substring(0, separator).trim().lowercase(Locale.US)
            val headerValue = headerLine.substring(separator + 1).trim()
            if (headerName == "content-length") {
                contentLength = headerValue.toIntOrNull() ?: 0
            }
        }

        val body = if (contentLength > 0) {
            val bytes = input.readExactly(contentLength)
            String(bytes, StandardCharsets.UTF_8)
        } else {
            ""
        }

        return requestHandler(method, path, body)
    }

    private fun writeResponse(
        output: BufferedOutputStream,
        response: HttpResponse,
    ) {
        val reason = statusReason(response.statusCode)
        val headers = buildString {
            append("HTTP/1.1 ${response.statusCode} $reason\r\n")
            append("Content-Type: ${response.contentType}\r\n")
            append("Content-Length: ${response.body.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.UTF_8)

        output.write(headers)
        output.write(response.body)
        output.flush()
    }

    private fun statusReason(statusCode: Int): String = when (statusCode) {
        200 -> "OK"
        400 -> "Bad Request"
        409 -> "Conflict"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        504 -> "Gateway Timeout"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        else -> "OK"
    }

    private fun escapeJson(value: String): String = buildString(value.length + 8) {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
    }
}

private fun BufferedInputStream.readAsciiLine(): String? {
    val bytes = ArrayList<Byte>(64)
    while (true) {
        val next = read()
        if (next == -1) {
            return if (bytes.isEmpty()) {
                null
            } else {
                String(bytes.toByteArrayUnsafe(), StandardCharsets.US_ASCII).trimEnd('\r')
            }
        }

        if (next == '\n'.code) {
            break
        }

        bytes += next.toByte()
    }

    return String(bytes.toByteArrayUnsafe(), StandardCharsets.US_ASCII).trimEnd('\r')
}

private fun BufferedInputStream.readExactly(byteCount: Int): ByteArray {
    val buffer = ByteArray(byteCount)
    var offset = 0
    while (offset < byteCount) {
        val readCount = read(buffer, offset, byteCount - offset)
        if (readCount <= 0) {
            throw EOFException("Unexpected end of stream")
        }
        offset += readCount
    }
    return buffer
}

private fun ArrayList<Byte>.toByteArrayUnsafe(): ByteArray {
    val buffer = ByteArray(size)
    for (index in indices) {
        buffer[index] = this[index]
    }
    return buffer
}
