package com.omnicast.tv

import android.content.Context
import android.util.Log
import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSArray
import com.dd.plist.NSData
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.NSObject
import com.dd.plist.NSString
import com.dd.plist.PropertyListParser
import com.omnicast.tv.playfair.FairPlay
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import org.whispersystems.curve25519.Curve25519
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Arrays
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

class AirPlayBonjourService(
    private val context: Context,
    private val localIp: String,
    private val deviceName: String = "OmniCast Google TV",
    private val onLogEvent: (String) -> Unit,
    private val onStartMirroring: (Int, Int, Int) -> Unit,
    private val onH264Nal: (ByteArray) -> Unit,
    private val onPlayUrl: (String) -> Unit,
    private val onPhotoBytes: (ByteArray) -> Unit,
    private val onStop: () -> Unit
) {
    private val executor = Executors.newCachedThreadPool()
    @Volatile private var isRunning = false
    private var jmdns: JmDNS? = null

    private var airplaySocket7000: ServerSocket? = null
    private var raopSocket5000: ServerSocket? = null
    private var videoDataSocket7100: ServerSocket? = null
    private var videoEventSocket7101: ServerSocket? = null
    private var timingUdp7102: DatagramSocket? = null
    private var audioDataUdp7103: DatagramSocket? = null
    private var audioCtrlUdp7104: DatagramSocket? = null

    private val edCurveSpec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
    private val edPrivateKey: EdDSAPrivateKey
    private val edPublicKey: EdDSAPublicKey
    private val edPublicKeyBytes: ByteArray
    private val edPublicKeyHex: String

    init {
        val seed = MessageDigest.getInstance("SHA-256").digest("OmniCastGoogleTV-Ed25519-Seed".toByteArray())
        val privSpec = EdDSAPrivateKeySpec(seed, edCurveSpec)
        edPrivateKey = EdDSAPrivateKey(privSpec)
        val pubSpec = EdDSAPublicKeySpec(privSpec.a, edCurveSpec)
        edPublicKey = EdDSAPublicKey(pubSpec)
        edPublicKeyBytes = edPublicKey.abyte
        edPublicKeyHex = edPublicKeyBytes.joinToString("") { "%02x".format(it) }
    }

    private val macAddress = "AA:BB:CC:11:22:33"
    private val macCompact = "AABBCC112233"

    // Instancia directa de FairPlay SAP v2.5 (mantiene el estado de fp-setup para descifrar ekey)
    @Volatile private var fairPlay = FairPlay()
    @Volatile private var sharedSecret: ByteArray? = null
    @Volatile private var pairCipher: Cipher? = null
    @Volatile private var rawFairPlayAesKey: ByteArray? = null
    @Volatile private var setupEiv: ByteArray? = null
    @Volatile private var activeVideoCipher: Cipher? = null
    private val ogBlock = ByteArray(16)
    @Volatile private var nextDecryptCount = 0

    private fun logTv(msg: String) {
        Log.i("AirPlayService", msg)
        onLogEvent(msg)
    }

    fun start() {
        if (isRunning) return
        isRunning = true

        executor.execute {
            try {
                val addr = InetAddress.getByName(localIp)
                jmdns = JmDNS.create(addr, "OmniCastTV")

                val airplayProps = mapOf(
                    "deviceid" to macAddress,
                    "features" to "0x5A7FFFF7,0x1E",
                    "flags" to "0x4",
                    "model" to "AppleTV3,2",
                    "pi" to "b08f5a79-db29-4384-b456-a4784d9e6055",
                    "pk" to edPublicKeyHex,
                    "srcvers" to "220.68",
                    "vv" to "2"
                )
                jmdns?.registerService(
                    ServiceInfo.create("_airplay._tcp.local.", deviceName, 7000, 0, 0, airplayProps)
                )

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
                    "pk" to edPublicKeyHex
                )
                jmdns?.registerService(
                    ServiceInfo.create("_raop._tcp.local.", "${macCompact}@${deviceName}", 5000, 0, 0, raopProps)
                )
                logTv("Bonjour activo en $localIp (:7000 AirPlay / :5000 RAOP)")
            } catch (e: Throwable) {
                logTv("Error mDNS: ${e.message}")
            }
        }

        startRtspListener(7000) { airplaySocket7000 = it }
        startRtspListener(5000) { raopSocket5000 = it }

        executor.execute {
            try {
                videoEventSocket7101 = ServerSocket(7101).apply { reuseAddress = true }
                while (isRunning) {
                    val evClient = videoEventSocket7101?.accept() ?: break
                    executor.execute {
                        try {
                            val buf = ByteArray(1024)
                            while (isRunning && !evClient.isClosed) {
                                if (evClient.getInputStream().read(buf) == -1) break
                            }
                        } catch (_: Throwable) {
                        } finally {
                            try { evClient.close() } catch (_: Throwable) {}
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

        executor.execute {
            try {
                timingUdp7102 = DatagramSocket(7102).apply { reuseAddress = true }
                val buf = ByteArray(64)
                while (isRunning) {
                    val pkt = DatagramPacket(buf, buf.size)
                    timingUdp7102?.receive(pkt) ?: break
                    if (pkt.length >= 32 && (buf[0].toInt() and 0xFF) == 0x80 && (buf[1].toInt() and 0xFF) == 0xD2) {
                        val reply = buildNtpReplyPacket(buf)
                        timingUdp7102?.send(DatagramPacket(reply, reply.size, pkt.address, pkt.port))
                    }
                }
            } catch (_: Throwable) {}
        }

        executor.execute {
            try {
                audioDataUdp7103 = DatagramSocket(7103).apply { reuseAddress = true }
                audioCtrlUdp7104 = DatagramSocket(7104).apply { reuseAddress = true }
            } catch (_: Throwable) {}
        }

        executor.execute {
            try {
                videoDataSocket7100 = ServerSocket(7100).apply {
                    reuseAddress = true
                    receiveBufferSize = 1048576
                }
                while (isRunning) {
                    val mirrorClient = videoDataSocket7100?.accept() ?: break
                    executor.execute { handleMirroringVideoStream(mirrorClient) }
                }
            } catch (e: Throwable) {
                logTv("Error puerto video 7100: ${e.message}")
            }
        }
    }

    private fun buildNtpReplyPacket(req: ByteArray): ByteArray {
        val reply = ByteArray(32)
        reply[0] = 0x80.toByte()
        reply[1] = 0xD3.toByte()
        reply[2] = req[2]
        reply[3] = 0x07
        System.arraycopy(req, 24, reply, 8, 8)
        val ntpNow = currentNtpTimestamp()
        ByteBuffer.wrap(reply, 16, 8).order(ByteOrder.BIG_ENDIAN).putLong(ntpNow)
        ByteBuffer.wrap(reply, 24, 8).order(ByteOrder.BIG_ENDIAN).putLong(ntpNow)
        return reply
    }

    private fun sendNtpRequestToClient(clientIp: InetAddress, clientTimingPort: Int) {
        if (clientTimingPort <= 0) return
        executor.execute {
            try {
                val pkt = ByteArray(32)
                pkt[0] = 0x80.toByte()
                pkt[1] = 0xD2.toByte()
                pkt[2] = 0x00
                pkt[3] = 0x07
                val ntpNow = currentNtpTimestamp()
                ByteBuffer.wrap(pkt, 24, 8).order(ByteOrder.BIG_ENDIAN).putLong(ntpNow)
                timingUdp7102?.send(DatagramPacket(pkt, pkt.size, clientIp, clientTimingPort))
            } catch (_: Throwable) {}
        }
    }

    private fun currentNtpTimestamp(): Long {
        val millis = System.currentTimeMillis()
        val seconds = (millis / 1000L) + 2208988800L
        val fraction = ((millis % 1000L) * 0x100000000L) / 1000L
        return (seconds shl 32) or (fraction and 0xFFFFFFFFL)
    }

    private fun startRtspListener(port: Int, onSocketCreated: (ServerSocket) -> Unit) {
        executor.execute {
            try {
                val server = ServerSocket(port).apply { reuseAddress = true }
                onSocketCreated(server)
                while (isRunning) {
                    val client = server.accept() ?: break
                    executor.execute { handleAirPlayRtspClient(client, port) }
                }
            } catch (e: Throwable) {
                logTv("Error en puerto RTSP $port: ${e.message}")
            }
        }
    }

    private fun handleAirPlayRtspClient(socket: Socket, localPort: Int) {
        val remoteIp = socket.inetAddress.hostAddress ?: "Mac"
        logTv("Conexión entrante de $remoteIp en puerto :$localPort")

        try {
            socket.tcpNoDelay = true
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            while (isRunning && !socket.isClosed) {
                val requestLine = readLine(input) ?: break
                if (requestLine.isEmpty()) continue
                val parts = requestLine.split(" ")
                if (parts.size < 2) break
                val method = parts[0].uppercase()
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
                if (path != "/feedback") {
                    logTv("RTSP :$localPort -> $method $path (len=$contentLen, CSeq=${cseq ?: "-"})")
                }

                when {
                    path.startsWith("/info") || path == "/server-info" -> {
                        val bplist = buildServerInfoBinaryPlist()
                        sendResponse(output, "200 OK", "application/x-apple-binary-plist", bplist, cseq)
                    }

                    path == "/pair-setup" -> {
                        sendResponse(output, "200 OK", "application/octet-stream", edPublicKeyBytes, cseq)
                    }

                    path == "/pair-verify" && body.size >= 68 -> {
                        val flag = body[0].toInt()
                        if (flag > 0) {
                            fairPlay = FairPlay()
                            val clientCurvePub = body.copyOfRange(4, 36)
                            val curve25519 = Curve25519.getInstance(Curve25519.JAVA)
                            val curveKeyPair = curve25519.generateKeyPair()
                            val serverCurvePub = curveKeyPair.publicKey
                            val secret = curve25519.calculateAgreement(clientCurvePub, curveKeyPair.privateKey)
                            sharedSecret = secret

                            val edEngine = EdDSAEngine(MessageDigest.getInstance(edCurveSpec.hashAlgorithm))
                            edEngine.initSign(edPrivateKey)
                            edEngine.update(serverCurvePub)
                            edEngine.update(clientCurvePub)
                            val signature = edEngine.sign()

                            val sha512 = MessageDigest.getInstance("SHA-512")
                            sha512.update("Pair-Verify-AES-Key".toByteArray(Charsets.UTF_8))
                            sha512.update(secret)
                            val aesKey = sha512.digest().copyOfRange(0, 16)

                            sha512.reset()
                            sha512.update("Pair-Verify-AES-IV".toByteArray(Charsets.UTF_8))
                            sha512.update(secret)
                            val aesIv = sha512.digest().copyOfRange(0, 16)

                            val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
                                init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(aesIv))
                            }
                            pairCipher = cipher
                            val encryptedSig = cipher.update(signature)

                            val resp = ByteArray(96)
                            System.arraycopy(serverCurvePub, 0, resp, 0, 32)
                            System.arraycopy(encryptedSig, 0, resp, 32, 64)
                            logTv("pair-verify [1/2] OK")
                            sendResponse(output, "200 OK", "application/octet-stream", resp, cseq)
                        } else {
                            val clientEncSig = body.copyOfRange(4, 68)
                            pairCipher?.doFinal(clientEncSig)
                            logTv("pair-verify [2/2] OK")
                            sendResponse(output, "200 OK", "application/octet-stream", ByteArray(0), cseq)
                        }
                    }

                    path == "/fp-setup" && body.isNotEmpty() -> {
                        val out = ByteArrayOutputStream()
                        fairPlay.fairPlaySetup(ByteArrayInputStream(body), out)
                        val replyBytes = out.toByteArray()
                        logTv("fp-setup OK (in=${body.size}B -> out=${replyBytes.size}B)")
                        sendResponse(output, "200 OK", "application/octet-stream", replyBytes, cseq)
                    }

                    method == "SETUP" -> {
                        val respDict = NSDictionary()
                        if (body.isNotEmpty()) {
                            try {
                                val reqPlist = PropertyListParser.parse(body) as? NSDictionary
                                if (reqPlist != null) {
                                    val clientTimingPort = (reqPlist["timingPort"] as? NSNumber)?.intValue() ?: 0
                                    if (clientTimingPort > 0) {
                                        sendNtpRequestToClient(socket.inetAddress, clientTimingPort)
                                    }

                                    if (reqPlist.containsKey("ekey")) {
                                        val ekeyData = (reqPlist["ekey"] as? NSData)?.bytes()
                                        val eivData = (reqPlist["eiv"] as? NSData)?.bytes()
                                        setupEiv = eivData
                                        if (ekeyData != null) {
                                            rawFairPlayAesKey = fairPlay.decryptAesKey(ekeyData)
                                            logTv("Llave FairPlay ekey descifrada (${rawFairPlayAesKey?.size ?: 0}B)")
                                            if (rawFairPlayAesKey != null && sharedSecret != null && eivData != null) {
                                                initVideoCipher(rawFairPlayAesKey!!, sharedSecret!!, eivData, null)
                                            }
                                        }
                                    }

                                    val streamsObj = reqPlist["streams"] as? NSArray
                                    if (streamsObj != null) {
                                        val outList = mutableListOf<NSObject>()
                                        for (i in 0 until streamsObj.count()) {
                                            val streamDict = streamsObj.objectAtIndex(i) as? NSDictionary ?: continue
                                            val type = (streamDict["type"] as? NSNumber)?.intValue() ?: 0
                                            when (type) {
                                                110 -> {
                                                    val streamConnId = (streamDict["streamConnectionID"] as? NSNumber)?.longValue()
                                                    if (rawFairPlayAesKey != null && sharedSecret != null) {
                                                        initVideoCipher(rawFairPlayAesKey!!, sharedSecret!!, setupEiv, streamConnId)
                                                    }
                                                    // Pre-iniciar el decodificador MediaCodec ANTES de responder al SETUP para tener 0ms de espera cuando el Mac abra el puerto 7100
                                                    onStartMirroring(1920, 1080, 60)

                                                    val vStream = NSDictionary()
                                                    vStream["type"] = NSNumber(110)
                                                    vStream["dataPort"] = NSNumber(7100)
                                                    outList.add(vStream)
                                                    logTv("SETUP Video Stream (type=110) -> puerto TCP 7100")
                                                }
                                                96 -> {
                                                    val aStream = NSDictionary()
                                                    aStream["type"] = NSNumber(96)
                                                    aStream["dataPort"] = NSNumber(7103)
                                                    aStream["controlPort"] = NSNumber(7104)
                                                    outList.add(aStream)
                                                    logTv("SETUP Audio Stream (type=96) -> puertos UDP 7103/7104")
                                                }
                                                else -> {
                                                    val genericStream = NSDictionary()
                                                    genericStream["type"] = NSNumber(type)
                                                    genericStream["dataPort"] = NSNumber(7103)
                                                    outList.add(genericStream)
                                                }
                                            }
                                        }
                                        if (outList.isNotEmpty()) {
                                            respDict["streams"] = NSArray(*outList.toTypedArray())
                                        }
                                    }
                                }
                            } catch (e: Throwable) {
                                logTv("Error en SETUP: ${e.message}")
                            }
                        }
                        respDict["eventPort"] = NSNumber(7101)
                        respDict["timingPort"] = NSNumber(7102)
                        val respBytes = BinaryPropertyListWriter.writeToArray(respDict)
                        sendResponse(output, "200 OK", "application/x-apple-binary-plist", respBytes, cseq)
                    }

                    method == "GET_PARAMETER" -> {
                        val resp = "volume: 0.000000\r\n".toByteArray()
                        sendResponse(output, "200 OK", "text/parameters", resp, cseq)
                    }
                    method == "RECORD" || method == "SET_PARAMETER" || method == "OPTIONS" ||
                    method == "FLUSH" || path == "/feedback" || path == "/audioMode" -> {
                        sendResponse(output, "200 OK", "text/plain", ByteArray(0), cseq)
                    }

                    path.startsWith("/play") -> {
                        val bodyStr = String(body, Charsets.UTF_8)
                        val urlMatch = Regex("(https?://[^\\s\\x00-\\x1f<>\"]+)").find(bodyStr)
                        if (urlMatch != null) {
                            onPlayUrl(urlMatch.value)
                        } else {
                            try {
                                val plist = PropertyListParser.parse(body) as? NSDictionary
                                val contentLoc = plist?.get("Content-Location")?.toString()
                                if (!contentLoc.isNullOrEmpty()) onPlayUrl(contentLoc)
                            } catch (_: Throwable) {}
                        }
                        sendResponse(output, "200 OK", "text/parameters", ByteArray(0), cseq)
                    }

                    path.startsWith("/photo") && body.isNotEmpty() -> {
                        onPhotoBytes(body)
                        sendResponse(output, "200 OK", "text/plain", ByteArray(0), cseq)
                    }

                    path.startsWith("/stop") || method == "TEARDOWN" -> {
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
        } catch (e: Throwable) {
            logTv("Sesión RTSP :$localPort finalizada: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Throwable) {}
        }
    }

    private fun initVideoCipher(rawAesKey: ByteArray, secret: ByteArray, eiv: ByteArray?, streamConnectionID: Long?) {
        try {
            val sha512 = MessageDigest.getInstance("SHA-512")
            sha512.update(rawAesKey)
            sha512.update(secret)
            val eadc = sha512.digest().copyOfRange(0, 16)

            val finalKey: ByteArray
            val finalIv: ByteArray

            if (streamConnectionID != null) {
                val unsignedId = java.lang.Long.toUnsignedString(streamConnectionID)
                sha512.reset()
                sha512.update(("AirPlayStreamKey$unsignedId").toByteArray(Charsets.UTF_8))
                sha512.update(eadc)
                finalKey = sha512.digest().copyOfRange(0, 16)

                sha512.reset()
                sha512.update(("AirPlayStreamIV$unsignedId").toByteArray(Charsets.UTF_8))
                sha512.update(eadc)
                finalIv = sha512.digest().copyOfRange(0, 16)
            } else {
                finalKey = eadc
                finalIv = eiv ?: ByteArray(16)
            }

            nextDecryptCount = 0
            Arrays.fill(ogBlock, 0.toByte())
            activeVideoCipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(finalKey, "AES"), IvParameterSpec(finalIv))
            }
            logTv("AES-CTR de video listo (streamID=${streamConnectionID ?: 0L})")
        } catch (e: Throwable) {
            logTv("Error iniciando AES-CTR: ${e.message}")
        }
    }

    private fun decryptVideoPacket(video: ByteArray) {
        val cipher = activeVideoCipher ?: return
        if (nextDecryptCount > 0) {
            for (i in 0 until minOf(nextDecryptCount, video.size)) {
                video[i] = (video[i].toInt() xor ogBlock[(16 - nextDecryptCount) + i].toInt()).toByte()
            }
        }
        val encryptLen = ((video.size - nextDecryptCount) / 16) * 16
        if (encryptLen > 0) {
            cipher.update(video, nextDecryptCount, encryptLen, video, nextDecryptCount)
        }
        val restLen = (video.size - nextDecryptCount) % 16
        val restStart = video.size - restLen
        nextDecryptCount = 0
        if (restLen > 0) {
            Arrays.fill(ogBlock, 0.toByte())
            System.arraycopy(video, restStart, ogBlock, 0, restLen)
            cipher.update(ogBlock, 0, 16, ogBlock, 0)
            System.arraycopy(ogBlock, 0, video, restStart, restLen)
            nextDecryptCount = 16 - restLen
        }
    }

    private fun handleMirroringVideoStream(socket: Socket) {
        logTv("¡Stream de pantalla H.264 conectado en puerto 7100!")
        try {
            socket.tcpNoDelay = true
            socket.receiveBufferSize = 1048576
            val dis = DataInputStream(socket.getInputStream())

            val header = ByteArray(128)
            var frameCounter = 0

            while (isRunning && !socket.isClosed) {
                dis.readFully(header)

                val payloadSize = ByteBuffer.wrap(header, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
                val payloadType = header[4].toInt() and 0xFF

                if (payloadSize < 0 || payloadSize > 15_000_000) {
                    logTv("Tamaño de paquete inválido en :7100 ($payloadSize)")
                    break
                }
                val payload = ByteArray(payloadSize)
                if (payloadSize > 0) {
                    dis.readFully(payload)
                }

                when (payloadType) {
                    1 -> {
                        val annexBConfig = convertAvcCToAnnexB(payload)
                        if (annexBConfig.isNotEmpty()) {
                            logTv("Recibido SPS/PPS avcC (${annexBConfig.size}B)")
                            onH264Nal(annexBConfig)
                        }
                    }
                    0 -> {
                        decryptVideoPacket(payload)
                        var offset = 0
                        var validNals = 0
                        while (offset + 4 <= payload.size) {
                            val nalLen = ((payload[offset].toInt() and 0xFF) shl 24) or
                                    ((payload[offset + 1].toInt() and 0xFF) shl 16) or
                                    ((payload[offset + 2].toInt() and 0xFF) shl 8) or
                                    (payload[offset + 3].toInt() and 0xFF)
                            if (nalLen <= 0 || offset + 4 + nalLen > payload.size) break
                            payload[offset] = 0
                            payload[offset + 1] = 0
                            payload[offset + 2] = 0
                            payload[offset + 3] = 1
                            offset += 4 + nalLen
                            validNals++
                        }
                        if (validNals > 0) {
                            onH264Nal(payload)
                            frameCounter++
                            if (frameCounter == 1) {
                                val firstNalType = payload[4].toInt() and 0x1F
                                logTv("Primer cuadro H.264 decodificado (NAL=$firstNalType, ${payload.size}B)")
                            }
                        } else if (frameCounter == 0) {
                            logTv("Error: Paquete H.264 sin cabeceras AVCC válidas (${payload.size}B)")
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            logTv("Stream de pantalla finalizado: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Throwable) {}
            onStop()
        }
    }

    private fun convertAvcCToAnnexB(avcC: ByteArray): ByteArray {
        return try {
            if (avcC.size < 8) return ByteArray(0)
            val out = ByteArrayOutputStream()
            val startCode = byteArrayOf(0, 0, 0, 1)
            val numSps = avcC[5].toInt() and 0x1F
            var idx = 6
            for (i in 0 until numSps) {
                if (idx + 2 > avcC.size) break
                val spsLen = ((avcC[idx].toInt() and 0xFF) shl 8) or (avcC[idx + 1].toInt() and 0xFF)
                idx += 2
                if (idx + spsLen > avcC.size) break
                out.write(startCode)
                out.write(avcC, idx, spsLen)
                idx += spsLen
            }
            if (idx < avcC.size) {
                val numPps = avcC[idx].toInt() and 0xFF
                idx += 1
                for (i in 0 until numPps) {
                    if (idx + 2 > avcC.size) break
                    val ppsLen = ((avcC[idx].toInt() and 0xFF) shl 8) or (avcC[idx + 1].toInt() and 0xFF)
                    idx += 2
                    if (idx + ppsLen > avcC.size) break
                    out.write(startCode)
                    out.write(avcC, idx, ppsLen)
                    idx += ppsLen
                }
            }
            out.toByteArray()
        } catch (_: Throwable) {
            ByteArray(0)
        }
    }

    private fun buildServerInfoBinaryPlist(): ByteArray {
        try {
            val rawBytes = context.assets.open("airplay_info.plist").use { it.readBytes() }
            val dict = PropertyListParser.parse(rawBytes) as? NSDictionary
            if (dict != null) {
                dict["deviceid"] = NSString(macAddress)
                dict["pk"] = NSData(edPublicKeyBytes)
                return BinaryPropertyListWriter.writeToArray(dict)
            }
        } catch (_: Throwable) {}

        val root = NSDictionary()
        root["deviceid"] = NSString(macAddress)
        root["features"] = NSNumber(130367487991L)
        root["model"] = NSString("AppleTV3,2")
        root["protovers"] = NSString("1.0")
        root["srcvers"] = NSString("220.68")
        root["vv"] = NSNumber(2)
        root["pi"] = NSString("b08f5a79-db29-4384-b456-a4784d9e6055")
        root["pk"] = NSData(edPublicKeyBytes)
        root["statusFlags"] = NSNumber(68)
        root["keepAliveLowPower"] = NSNumber(1)
        root["keepAliveSendStatsAsBody"] = NSNumber(1)

        val display = NSDictionary()
        display["uuid"] = NSString("e5f7a68d-7b0f-4305-984b-974f677a150b")
        display["widthPhysical"] = NSNumber(0)
        display["heightPhysical"] = NSNumber(0)
        display["width"] = NSNumber(1920)
        display["height"] = NSNumber(1080)
        display["widthPixels"] = NSNumber(1920)
        display["heightPixels"] = NSNumber(1080)
        display["refreshRate"] = NSNumber(60)
        display["features"] = NSNumber(14)
        display["maxFPS"] = NSNumber(60)
        display["overscanned"] = NSNumber(false)
        display["rotation"] = NSNumber(false)
        root["displays"] = NSArray(display)

        return BinaryPropertyListWriter.writeToArray(root)
    }

    private fun sendResponse(output: OutputStream, status: String, contentType: String, body: ByteArray, cseq: String?) {
        val sb = StringBuilder()
        sb.append("RTSP/1.0 $status\r\n")
        if (cseq != null) sb.append("CSeq: $cseq\r\n")
        sb.append("Content-Type: $contentType\r\n")
        sb.append("Content-Length: ${body.size}\r\n")
        sb.append("Audio-Jack-Status: connected; type=digital\r\n")
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
        try { airplaySocket7000?.close() } catch (_: Throwable) {}
        try { raopSocket5000?.close() } catch (_: Throwable) {}
        try { videoDataSocket7100?.close() } catch (_: Throwable) {}
        try { videoEventSocket7101?.close() } catch (_: Throwable) {}
        try { timingUdp7102?.close() } catch (_: Throwable) {}
        try { audioDataUdp7103?.close() } catch (_: Throwable) {}
        try { audioCtrlUdp7104?.close() } catch (_: Throwable) {}
        try {
            jmdns?.unregisterAllServices()
            jmdns?.close()
        } catch (_: Throwable) {}
    }
}