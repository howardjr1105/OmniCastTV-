package com.omnicast.tv

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

/**
 * Motor embebido que corre dentro del Google TV / Android TV:
 * 1. Servidor HTTP en puerto 8080 (Ideal para iOS/iPhone envío de video/fotos sin aviso SSL y TV local).
 * 2. Servidor HTTPS en puerto 8443 (Habilita Contexto Seguro para getDisplayMedia 60fps en Mac, Windows, Android y iPad).
 * 3. Servidor WebSocket RFC-6455 integrado en ambos puertos para señalización WebRTC LAN (<15ms) y control remoto.
 * 4. Streaming binario directo (/stream-media) con soporte HTTP Range (206 Partial Content) para videos 4K desde iOS/Android.
 */
class LanSignalingServer(
    private val context: Context,
    private val httpPort: Int = 8080,
    private val httpsPort: Int = 8443
) {
    private val executor = Executors.newCachedThreadPool()
    @Volatile private var isRunning = false
    private var httpServerSocket: ServerSocket? = null
    private var httpsServerSocket: SSLServerSocket? = null

    private val wsClients = Collections.synchronizedSet(mutableSetOf<WsConnection>())
    private val uploadedMedia = ConcurrentHashMap<String, File>()

    fun start() {
        if (isRunning) return
        isRunning = true

        // Iniciar servidor HTTP (8080)
        executor.execute {
            try {
                httpServerSocket = ServerSocket(httpPort).apply { reuseAddress = true }
                Log.i("OmniCastServer", "HTTP Server escuchando en puerto $httpPort")
                while (isRunning) {
                    val client = httpServerSocket?.accept() ?: break
                    executor.execute { handleSocket(client) }
                }
            } catch (e: Exception) {
                Log.e("OmniCastServer", "Error en HTTP Server: ${e.message}")
            }
        }

        // Iniciar servidor HTTPS (8443) usando el keystore PKCS12 incluido en res/raw/keystore.p12
        executor.execute {
            try {
                val keyStore = KeyStore.getInstance("PKCS12")
                context.resources.openRawResource(R.raw.keystore).use { input ->
                    keyStore.load(input, "omnicast123".toCharArray())
                }
                val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                kmf.init(keyStore, "omnicast123".toCharArray())
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(kmf.keyManagers, null, null)

                httpsServerSocket = (sslContext.serverSocketFactory.createServerSocket(httpsPort) as SSLServerSocket).apply {
                    reuseAddress = true
                }
                Log.i("OmniCastServer", "HTTPS Server escuchando en puerto $httpsPort")
                while (isRunning) {
                    val client = httpsServerSocket?.accept() ?: break
                    executor.execute { handleSocket(client) }
                }
            } catch (e: Exception) {
                Log.e("OmniCastServer", "Error en HTTPS Server: ${e.message}")
            }
        }
    }

    fun stop() {
        isRunning = false
        synchronized(wsClients) {
            wsClients.forEach { it.close() }
            wsClients.clear()
        }
        try { httpServerSocket?.close() } catch (_: Exception) {}
        try { httpsServerSocket?.close() } catch (_: Exception) {}
    }

    private fun handleSocket(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            val headers = mutableMapOf<String, String>()
            val requestLine = readLine(input) ?: run { socket.close(); return }
            val parts = requestLine.split(" ")
            if (parts.size < 2) { socket.close(); return }
            val method = parts[0]
            val fullPath = parts[1]
            val path = fullPath.substringBefore("?")

            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(":")
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
            }

            // 1. Verificar si es un Upgrade a WebSocket
            if (headers["upgrade"]?.lowercase() == "websocket" && headers["sec-websocket-key"] != null) {
                handleWebSocket(socket, input, output, headers["sec-websocket-key"]!!)
                return
            }

            // 2. Subida directa de archivo de video/foto desde iPhone/Android (POST /upload)
            if (method == "POST" && path == "/upload") {
                val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L
                val ext = headers["x-file-ext"] ?: "mp4"
                val mime = headers["x-file-mime"] ?: "video/mp4"
                val tempFile = File(context.cacheDir, "cast_media_${System.currentTimeMillis()}.$ext")
                FileOutputStream(tempFile).use { fos ->
                    val buf = ByteArray(65536)
                    var remaining = contentLength
                    while (remaining > 0) {
                        val read = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                        if (read == -1) break
                        fos.write(buf, 0, read)
                        remaining -= read
                    }
                }
                // Limpiar archivos viejos
                uploadedMedia.values.forEach { if (it != tempFile) it.delete() }
                uploadedMedia["current"] = tempFile

                val respJson = "{\"status\":\"ok\",\"url\":\"/media/current?t=${System.currentTimeMillis()}\",\"mime\":\"$mime\"}"
                sendHttpResponse(output, "200 OK", "application/json", respJson.toByteArray())
                socket.close()
                return
            }

            // 3. Servir el archivo multimedia al reproductor de hardware del TV con soporte HTTP Range (206 Partial Content)
            if (method == "GET" && path.startsWith("/media/")) {
                val file = uploadedMedia["current"]
                if (file == null || !file.exists()) {
                    sendHttpResponse(output, "404 Not Found", "text/plain", "Media not found".toByteArray())
                    socket.close()
                    return
                }
                serveFileWithRange(file, headers["range"], output)
                socket.close()
                return
            }

            // 4. Servir la aplicación Web (Receptor TV y Emisor Multiplataforma) desde assets/index.html
            val htmlBytes = context.assets.open("index.html").use { it.readBytes() }
            sendHttpResponse(output, "200 OK", "text/html; charset=utf-8", htmlBytes)
            socket.close()
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun serveFileWithRange(file: File, rangeHeader: String?, output: OutputStream) {
        val fileLength = file.length()
        val mime = when {
            file.name.endsWith(".mov", true) -> "video/quicktime"
            file.name.endsWith(".webm", true) -> "video/webm"
            file.name.endsWith(".jpg", true) || file.name.endsWith(".jpeg", true) -> "image/jpeg"
            file.name.endsWith(".png", true) -> "image/png"
            else -> "video/mp4"
        }

        var start = 0L
        var end = fileLength - 1
        val isPartial = rangeHeader != null && rangeHeader.startsWith("bytes=")

        if (isPartial) {
            val rangeParts = rangeHeader!!.removePrefix("bytes=").split("-")
            start = rangeParts[0].toLongOrNull() ?: 0L
            if (rangeParts.size > 1 && rangeParts[1].isNotEmpty()) {
                end = rangeParts[1].toLongOrNull() ?: (fileLength - 1)
            }
        }
        val contentLength = (end - start) + 1
        val header = buildString {
            if (isPartial) {
                append("HTTP/1.1 206 Partial Content\r\n")
                append("Content-Range: bytes $start-$end/$fileLength\r\n")
            } else {
                append("HTTP/1.1 200 OK\r\n")
            }
            append("Content-Type: $mime\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Content-Length: $contentLength\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray())

        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val buffer = ByteArray(65536)
            var remaining = contentLength
            while (remaining > 0) {
                val read = raf.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read == -1) break
                output.write(buffer, 0, read)
                remaining -= read
            }
        }
        output.flush()
    }

    private fun sendHttpResponse(output: OutputStream, status: String, contentType: String, body: ByteArray) {
        val header = "HTTP/1.1 $status\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Connection: close\r\n\r\n"
        output.write(header.toByteArray())
        output.write(body)
        output.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }

    // --- Implementación RFC 6455 WebSocket para Señalización WebRTC y Control en Tiempo Real ---
    private fun handleWebSocket(socket: Socket, input: InputStream, output: OutputStream, key: String) {
        val magic = key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        val sha1 = MessageDigest.getInstance("SHA-1").digest(magic.toByteArray())
        val accept = Base64.encodeToString(sha1, Base64.NO_WRAP)

        val handshake = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n\r\n"
        output.write(handshake.toByteArray())
        output.flush()

        val conn = WsConnection(socket, output)
        wsClients.add(conn)

        try {
            while (isRunning && !socket.isClosed) {
                val b1 = input.read()
                if (b1 == -1) break
                val opcode = b1 and 0x0F
                if (opcode == 8) break // Close frame

                val b2 = input.read()
                if (b2 == -1) break
                val masked = (b2 and 0x80) != 0
                var payloadLen = (b2 and 0x7F).toLong()

                if (payloadLen == 126L) {
                    payloadLen = ((input.read() shl 8) or input.read()).toLong()
                } else if (payloadLen == 127L) {
                    payloadLen = 0
                    for (i in 0 until 8) {
                        payloadLen = (payloadLen shl 8) or (input.read().toLong() and 0xFF)
                    }
                }

                val maskKey = ByteArray(4)
                if (masked) {
                    var readMask = 0
                    while (readMask < 4) {
                        val r = input.read(maskKey, readMask, 4 - readMask)
                        if (r == -1) break
                        readMask += r
                    }
                }

                val payload = ByteArray(payloadLen.toInt())
                var readTotal = 0
                while (readTotal < payload.size) {
                    val r = input.read(payload, readTotal, payload.size - readTotal)
                    if (r == -1) break
                    readTotal += r
                }

                if (masked) {
                    for (i in payload.indices) {
                        payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
                    }
                }

                if (opcode == 1) { // Text Frame
                    val message = String(payload, Charsets.UTF_8)
                    broadcastExcept(conn, message)
                } else if (opcode == 9) { // Ping -> Pong
                    conn.sendFrame(10, payload)
                }
            }
        } catch (_: Exception) {
        } finally {
            wsClients.remove(conn)
            conn.close()
        }
    }

    private fun broadcastExcept(sender: WsConnection, message: String) {
        val data = message.toByteArray(Charsets.UTF_8)
        synchronized(wsClients) {
            val iterator = wsClients.iterator()
            while (iterator.hasNext()) {
                val client = iterator.next()
                if (client != sender) {
                    if (!client.sendFrame(1, data)) {
                        iterator.remove()
                    }
                }
            }
        }
    }

    private class WsConnection(private val socket: Socket, private val output: OutputStream) {
        @Synchronized
        fun sendFrame(opcode: Int, data: ByteArray): Boolean {
            return try {
                output.write(0x80 or opcode)
                when {
                    data.size <= 125 -> output.write(data.size)
                    data.size <= 65535 -> {
                        output.write(126)
                        output.write((data.size shr 8) and 0xFF)
                        output.write(data.size and 0xFF)
                    }
                    else -> {
                        output.write(127)
                        val len = data.size.toLong()
                        for (i in 7 downTo 0) {
                            output.write(((len shr (i * 8)) and 0xFF).toInt())
                        }
                    }
                }
                output.write(data)
                output.flush()
                true
            } catch (e: Exception) {
                false
            }
        }

        fun close() {
            try { socket.close() } catch (_: Exception) {}
        }
    }
}
