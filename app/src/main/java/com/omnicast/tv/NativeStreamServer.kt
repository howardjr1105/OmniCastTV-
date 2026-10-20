package com.omnicast.tv

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.*
import java.math.BigInteger
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Collections
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.security.auth.x500.X500Principal

class NativeStreamServer(
    private val context: Context,
    private val dlnaService: DlnaSsdpService,
    private val httpPort: Int = 8080,
    private val httpsPort: Int = 8444,
    private val onStartH264Mirror: (Int, Int, Int) -> Unit,
    private val onH264NalPacket: (ByteArray) -> Unit,
    private val onPcmAudioPacket: (ByteArray, Int, Int) -> Unit,
    private val onPlayMediaUrl: (String, String) -> Unit,
    private val onStopStream: () -> Unit,
    private val onWebRtcSignalFromSender: (String) -> Unit
) {
    private val executor = Executors.newCachedThreadPool()
    @Volatile private var isRunning = false
    private var httpServerSocket: ServerSocket? = null
    private var httpsServerSocket: SSLServerSocket? = null
    private val uploadedMedia = ConcurrentHashMap<String, File>()

    fun start() {
        if (isRunning) return
        isRunning = true

        // 1. Servidor HTTP + Binary WebSocket (Puerto 8080)
        executor.execute {
            try {
                httpServerSocket = ServerSocket(httpPort).apply {
                    reuseAddress = true
                    receiveBufferSize = 524288
                }
                while (isRunning) {
                    val client = httpServerSocket?.accept() ?: break
                    executor.execute { handleSocket(client) }
                }
            } catch (e: Exception) {
                Log.e("NativeStreamServer", "HTTP error: ${e.message}")
            }
        }

        // 2. Servidor HTTPS + Binary WebSocket (Puerto 8444)
        executor.execute {
            try {
                val sslContext = createSslContext()
                httpsServerSocket = (sslContext.serverSocketFactory.createServerSocket(httpsPort) as SSLServerSocket).apply {
                    reuseAddress = true
                    receiveBufferSize = 524288
                }
                while (isRunning) {
                    val client = httpsServerSocket?.accept() ?: break
                    executor.execute { handleSocket(client) }
                }
            } catch (e: Exception) {
                Log.e("NativeStreamServer", "HTTPS error: ${e.message}")
            }
        }
    }

    private fun createSslContext(): SSLContext {
        try {
            val keyStore = KeyStore.getInstance("PKCS12")
            context.resources.openRawResource(R.raw.keystore).use { input ->
                keyStore.load(input, "omnicast123".toCharArray())
            }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(keyStore, "omnicast123".toCharArray())
            return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        } catch (e: Exception) {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val alias = "OmniCastNativeKey"
            if (!ks.containsAlias(alias)) {
                val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
                val spec = KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_DECRYPT
                )
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateSubject(X500Principal("CN=omnicast.local"))
                    .setCertificateSerialNumber(BigInteger.ONE)
                    .setCertificateNotBefore(Date(System.currentTimeMillis() - 86400000L))
                    .setCertificateNotAfter(Date(System.currentTimeMillis() + 315360000000L))
                    .build()
                kpg.initialize(spec)
                kpg.generateKeyPair()
            }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, null)
            return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        }
    }

    private fun handleSocket(socket: Socket) {
        try {
            // Prioridad máxima en red Wi-Fi (TCP_NODELAY + IPTOS_LOWDELAY DSCP)
            socket.tcpNoDelay = true
            try { socket.trafficClass = 0x10 } catch (_: Exception) {}
            socket.receiveBufferSize = 524288

            val input = BufferedInputStream(socket.getInputStream(), 65536)
            val output = BufferedOutputStream(socket.getOutputStream(), 65536)

            val requestLine = readLine(input) ?: run { socket.close(); return }
            val parts = requestLine.split(" ")
            if (parts.size < 2) { socket.close(); return }
            val method = parts[0]
            val fullPath = parts[1]
            val path = fullPath.substringBefore("?")

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(":")
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
            }

            // 1. WebSocket Binario de Baja Latencia (H.264 NAL units directo a MediaCodec!)
            if (headers["upgrade"]?.lowercase() == "websocket" && headers["sec-websocket-key"] != null) {
                handleBinaryWebSocket(socket, input, output, headers["sec-websocket-key"]!!)
                return
            }

            // 2. Endpoints DLNA / UPnP MediaRenderer
            if (path == "/dlna/device.xml") {
                val xml = dlnaService.getDeviceDescriptorXml().toByteArray()
                sendHttpResponse(output, "200 OK", "text/xml; charset=utf-8", xml)
                socket.close()
                return
            }
            if (path.startsWith("/dlna/control/AVTransport") && method == "POST") {
                val len = headers["content-length"]?.toIntOrNull() ?: 0
                val body = ByteArray(len)
                var read = 0
                while (read < len) {
                    val r = input.read(body, read, len - read)
                    if (r == -1) break
                    read += r
                }
                val soap = String(body, Charsets.UTF_8)
                val uriMatch = Regex("<CurrentURI>(.*?)</CurrentURI>").find(soap)
                if (uriMatch != null) {
                    val uri = uriMatch.groupValues[1]
                        .replace("&amp;", "&")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">")
                    onPlayMediaUrl(uri, "video/mp4")
                } else if (soap.contains("Stop")) {
                    onStopStream()
                }
                val soapResp = """<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body><u:SetAVTransportURIResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"/></s:Body>
</s:Envelope>"""
                sendHttpResponse(output, "200 OK", "text/xml; charset=utf-8", soapResp.toByteArray())
                socket.close()
                return
            }

            // 3. Subida de archivo 4K original desde iOS / Android (POST /upload)
            if (method == "POST" && path == "/upload") {
                val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L
                val ext = headers["x-file-ext"] ?: "mp4"
                val mime = headers["x-file-mime"] ?: "video/mp4"
                val tempFile = File(context.cacheDir, "native_media_${System.currentTimeMillis()}.$ext")
                FileOutputStream(tempFile).use { fos ->
                    val buf = ByteArray(65536)
                    var remaining = contentLength
                    while (remaining > 0) {
                        val r = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                        if (r == -1) break
                        fos.write(buf, 0, r)
                        remaining -= r
                    }
                }
                uploadedMedia.values.forEach { if (it != tempFile) it.delete() }
                uploadedMedia["current"] = tempFile

                // Reproducir directamente en el ExoPlayer Nativo del Google TV!
                onPlayMediaUrl(tempFile.absolutePath, mime)

                val respJson = "{\"status\":\"ok\"}"
                sendHttpResponse(output, "200 OK", "application/json", respJson.toByteArray())
                socket.close()
                return
            }

            if (path == "/favicon.ico") {
                sendHttpResponse(output, "204 No Content", "image/x-icon", ByteArray(0))
                socket.close()
                return
            }

            // 4. Servir Panel Emisor WebCodecs H.264 Hardware (para Mac, Windows, Android e iOS)
            val htmlBytes = context.assets.open("sender.html").use { it.readBytes() }
            sendHttpResponse(output, "200 OK", "text/html; charset=utf-8", htmlBytes)
            socket.close()
        } catch (_: Exception) {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun handleBinaryWebSocket(socket: Socket, input: InputStream, output: OutputStream, key: String) {
        val magic = key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        val sha1 = MessageDigest.getInstance("SHA-1").digest(magic.toByteArray())
        val accept = Base64.encodeToString(sha1, Base64.NO_WRAP)

        val handshake = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n\r\n"
        output.write(handshake.toByteArray())
        output.flush()

        val maskKey = ByteArray(4)

        try {
            while (isRunning && !socket.isClosed) {
                val b1 = input.read()
                if (b1 == -1) break
                val opcode = b1 and 0x0F
                if (opcode == 8) break // Close

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

                if (masked) {
                    var readMask = 0
                    while (readMask < 4) {
                        val r = input.read(maskKey, readMask, 4 - readMask)
                        if (r == -1) break
                        readMask += r
                    }
                }

                val size = payloadLen.toInt()
                val payload = ByteArray(size)
                var readTotal = 0
                while (readTotal < size) {
                    val r = input.read(payload, readTotal, size - readTotal)
                    if (r == -1) break
                    readTotal += r
                }

                if (masked) {
                    val m0 = maskKey[0].toInt()
                    val m1 = maskKey[1].toInt()
                    val m2 = maskKey[2].toInt()
                    val m3 = maskKey[3].toInt()
                    var i = 0
                    val limit = size - 3
                    while (i < limit) {
                        payload[i] = (payload[i].toInt() xor m0).toByte()
                        payload[i + 1] = (payload[i + 1].toInt() xor m1).toByte()
                        payload[i + 2] = (payload[i + 2].toInt() xor m2).toByte()
                        payload[i + 3] = (payload[i + 3].toInt() xor m3).toByte()
                        i += 4
                    }
                    while (i < size) {
                        payload[i] = (payload[i].toInt() xor maskKey[i and 3].toInt()).toByte()
                        i++
                    }
                }

                when (opcode) {
                    2 -> { // Frame Binario: [Tipo 1 byte][Datos]
                        if (size > 1) {
                            val packetType = payload[0].toInt()
                            if (packetType == 1) {
                                // Paquete 0x01: Bitstream H.264 Annex-B directo a MediaCodec Hardware!
                                val nalBytes = payload.copyOfRange(1, size)
                                onH264NalPacket(nalBytes)
                            } else if (packetType == 2) {
                                // Paquete 0x02: Audio PCM 16-bit directo a AudioTrack Low-Latency!
                                onPcmAudioPacket(payload, 1, size - 1)
                            }
                        }
                    }
                    1 -> { // Frame de Control JSON
                        val text = String(payload, Charsets.UTF_8)
                        val json = JSONObject(text)
                        when (json.optString("type")) {
                            "init_h264" -> {
                                val w = json.optInt("width", 1920)
                                val h = json.optInt("height", 1080)
                                val fps = json.optInt("fps", 60)
                                onStartH264Mirror(w, h, fps)
                            }
                            "stop" -> onStopStream()
                            else -> onWebRtcSignalFromSender(text)
                        }
                    }
                    9 -> { // Ping -> Pong
                        synchronized(output) {
                            output.write(0x8A)
                            output.write(0)
                            output.flush()
                        }
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
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

    fun stop() {
        isRunning = false
        try { httpServerSocket?.close() } catch (_: Exception) {}
        try { httpsServerSocket?.close() } catch (_: Exception) {}
    }
}
