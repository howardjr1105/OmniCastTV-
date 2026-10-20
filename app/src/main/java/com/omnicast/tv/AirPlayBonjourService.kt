package com.omnicast.tv

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * Demonio AirPlay (_airplay._tcp.local. puerto 7000 y _raop._tcp.local. puerto 5000).
 * Anuncia el Google TV en la red local mediante mDNS con los registros TXT de Apple TV.
 */
class AirPlayBonjourService(
    private val localIp: String,
    private val deviceName: String = "OmniCast TV",
    private val onPlayUrl: (String) -> Unit,
    private val onPhotoBytes: (ByteArray) -> Unit,
    private val onStop: () -> Unit
) {
    private val executor = Executors.newCachedThreadPool()
    @Volatile private var isRunning = false
    private var jmdns: JmDNS? = null
    private var airplaySocket: ServerSocket? = null
    private val macAddress = "AA:BB:CC:11:22:33"
    private val macCompact = "AABBCC112233"

    fun start() {
        if (isRunning) return
        isRunning = true

        // 1. Levantar mDNS / Bonjour con JmDNS en la interfaz Wi-Fi/LAN del TV
        executor.execute {
            try {
                val addr = InetAddress.getByName(localIp)
                jmdns = JmDNS.create(addr, "OmniCastTV")

                val airplayProps = mapOf(
                    "deviceid" to macAddress,
                    "features" to "0x5A7FFFF7,0x1E",
                    "flags" to "0x4",
                    "model" to "AppleTV3,2",
                    "srcvers" to "220.68",
                    "vv" to "2",
                    "pk" to "b07727d6f6cd6e08b58ede525ec3cdeaa252ad9f683feb212ef8a205246554e7"
                )
                val airplayInfo = ServiceInfo.create(
                    "_airplay._tcp.local.",
                    deviceName,
                    7000,
                    0,
                    0,
                    airplayProps
                )
                jmdns?.registerService(airplayInfo)

                val raopProps = mapOf(
                    "ch" to "2",
                    "cn" to "0,1,2,3",
                    "da" to "true",
                    "et" to "0,3,5",
                    "vv" to "2",
                    "ft" to "0x5A7FFFF7,0x1E",
                    "am" to "AppleTV3,2",
                    "md" to "0,1,2",
                    "rhd" to "5.6.0.0",
                    "pw" to "false",
                    "sr" to "44100",
                    "ss" to "16",
                    "sv" to "false",
                    "tp" to "UDP",
                    "txtvers" to "1",
                    "sf" to "0x4",
                    "vs" to "220.68",
                    "vn" to "65537",
                    "pk" to "b07727d6f6cd6e08b58ede525ec3cdeaa252ad9f683feb212ef8a205246554e7"
                )
                val raopInfo = ServiceInfo.create(
                    "_raop._tcp.local.",
                    "${macCompact}@${deviceName}",
                    5000,
                    0,
                    0,
                    raopProps
                )
                jmdns?.registerService(raopInfo)
                Log.i("AirPlayService", "Servicios _airplay._tcp y _raop._tcp registrados en $localIp")
            } catch (e: Exception) {
                Log.e("AirPlayService", "Error mDNS: ${e.message}")
            }
        }

        // 2. Servidor HTTP/RTSP AirPlay en puerto 7000
        executor.execute {
            try {
                airplaySocket = ServerSocket(7000).apply { reuseAddress = true }
                while (isRunning) {
                    val client = airplaySocket?.accept() ?: break
                    executor.execute { handleAirPlayClient(client) }
                }
            } catch (e: Exception) {
                Log.e("AirPlayService", "Error puerto 7000: ${e.message}")
            }
        }
    }

    private fun handleAirPlayClient(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            while (isRunning && !socket.isClosed) {
                val requestLine = readLine(input) ?: break
                if (requestLine.isEmpty()) continue
                val parts = requestLine.split(" ")
                if (parts.size < 2) break
                val method = parts[0]
                val path = parts[1]

                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(":")
                    if (idx > 0) {
                        headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                    }
                }

                val contentLen = headers["content-length"]?.toIntOrNull() ?: 0
                val body = ByteArray(contentLen)
                var read = 0
                while (read < contentLen) {
                    val r = input.read(body, read, contentLen - read)
                    if (r == -1) break
                    read += r
                }

                val cseq = headers["cseq"]
                when {
                    path == "/server-info" || path == "/info" -> {
                        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>deviceid</key><string>$macAddress</string>
  <key>features</key><integer>1518338039</integer>
  <key>model</key><string>AppleTV3,2</string>
  <key>protovers</key><string>1.0</string>
  <key>srcvers</key><string>220.68</string>
</dict>
</plist>"""
                        sendResponse(output, "200 OK", "text/x-apple-plist+xml", xml.toByteArray(), cseq)
                    }
                    path.startsWith("/play") -> {
                        val bodyStr = String(body, Charsets.UTF_8)
                        val urlMatch = Regex("(https?://[^\\s\\x00-\\x1f<>\"]+)").find(bodyStr)
                        if (urlMatch != null) {
                            onPlayUrl(urlMatch.value)
                        }
                        sendResponse(output, "200 OK", "text/parameters", ByteArray(0), cseq)
                    }
                    path.startsWith("/photo") && body.isNotEmpty() -> {
                        onPhotoBytes(body)
                        sendResponse(output, "200 OK", "text/plain", ByteArray(0), cseq)
                    }
                    path.startsWith("/stop") || method == "TEARDOWN" -> {
                        onStop()
                        sendResponse(output, "200 OK", "text/plain", ByteArray(0), cseq)
                    }
                    path.startsWith("/scrub") -> {
                        val resp = "duration: 3600.000000\nposition: 1.000000\n"
                        sendResponse(output, "200 OK", "text/parameters", resp.toByteArray(), cseq)
                    }
                    else -> {
                        sendResponse(output, "200 OK", "text/plain", ByteArray(0), cseq)
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun sendResponse(output: OutputStream, status: String, contentType: String, body: ByteArray, cseq: String?) {
        val proto = if (cseq != null) "RTSP/1.0" else "HTTP/1.1"
        val sb = StringBuilder()
        sb.append("$proto $status\r\n")
        if (cseq != null) sb.append("CSeq: $cseq\r\n")
        sb.append("Content-Type: $contentType\r\n")
        sb.append("Content-Length: ${body.size}\r\n")
        sb.append("Server: AirTunes/220.68\r\n\r\n")
        output.write(sb.toString().toByteArray())
        if (body.isNotEmpty()) output.write(body)
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
        try { airplaySocket?.close() } catch (_: Exception) {}
        try {
            jmdns?.unregisterAllServices()
            jmdns?.close()
        } catch (_: Exception) {}
    }
}
