package com.omnicast.tv

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Process
import android.util.Log
import android.view.Surface
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/**
 * Decodificador H.264/AVC por Hardware directo a SurfaceView.
 * Garantiza que el paquete SPS/PPS (Codec Config) nunca se pierda al iniciar la sesión AirPlay.
 */
class HardwareH264Decoder(private val surface: Surface, private val onError: (String) -> Unit = {}) {

    private var codec: MediaCodec? = null
    @Volatile private var isRunning = false
    private val nalQueue = LinkedBlockingDeque<ByteArray>(180)
    @Volatile private var cachedConfigNal: ByteArray? = null
    private var inputThread: Thread? = null
    private var outputThread: Thread? = null

    @Synchronized
    fun start(width: Int = 1920, height: Int = 1080, fps: Int = 60) {
        stop()
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(width * height, 4 * 1024 * 1024))
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
            }

            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, surface, null, 0)
                start()
            }
            isRunning = true

            // Si ya había llegado un paquete SPS/PPS mientras arrancaba el codec, encolarlo de primero
            cachedConfigNal?.let { nalQueue.offerFirst(it) }

            // Hilo de inyección de NAL units al hardware
            inputThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
                val localCodec = codec ?: return@Thread
                var pts = 0L
                while (isRunning) {
                    try {
                        val nal = nalQueue.poll(15, TimeUnit.MILLISECONDS) ?: continue
                        // Nunca descartar un cuadro: perder un P-frame corrompe la imagen hasta el siguiente IDR
                        var inIdx = -1
                        while (isRunning && inIdx < 0) inIdx = localCodec.dequeueInputBuffer(10000)
                        if (inIdx >= 0) {
                            val buf = localCodec.getInputBuffer(inIdx)
                            if (buf != null) {
                                buf.clear()
                                val len = minOf(nal.size, buf.remaining())
                                if (len < nal.size) onError("Cuadro H.264 truncado (${nal.size}B > ${buf.capacity()}B)")
                                buf.put(nal, 0, len)
                                val nalType = findFirstNalType(nal)
                                val flags = when (nalType) {
                                    7, 8 -> MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                                    5 -> MediaCodec.BUFFER_FLAG_KEY_FRAME
                                    else -> 0
                                }
                                localCodec.queueInputBuffer(inIdx, 0, len, pts, flags)
                                if (nalType != 7 && nalType != 8) {
                                    pts += 16666L
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (!isRunning) break
                        onError("Decoder entrada: ${e.javaClass.simpleName} ${e.message}")
                        if (e is IllegalStateException) break
                    }
                }
            }, "OmniCast-H264-In").apply { start() }

            // Hilo de renderizado inmediato a SurfaceView
            outputThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
                val localCodec = codec ?: return@Thread
                val info = MediaCodec.BufferInfo()
                var delayMs = DEFAULT_VIDEO_DELAY_MS
                var lastReadMs = 0L
                while (isRunning) {
                    try {
                        val outIdx = localCodec.dequeueOutputBuffer(info, 10000)
                        if (outIdx >= 0) {
                            // El audio llega por la TV con más latencia (colchón + mezclador + HDMI): se retrasa el video para igualarlo
                            val now = System.nanoTime() / 1_000_000
                            if (now - lastReadMs > 1000) {
                                lastReadMs = now
                                delayMs = readDelayMs() ?: delayMs
                            }
                            if (delayMs > 0) localCodec.releaseOutputBuffer(outIdx, System.nanoTime() + delayMs * 1_000_000L)
                            else localCodec.releaseOutputBuffer(outIdx, true)
                        }
                    } catch (e: Exception) {
                        if (!isRunning) break
                        onError("Decoder salida: ${e.javaClass.simpleName} ${e.message}")
                        if (e is IllegalStateException) break
                    }
                }
            }, "OmniCast-H264-Out").apply { start() }

            Log.i("HardwareH264Decoder", "MediaCodec H.264 iniciado ($width x $height @ $fps fps)")
        } catch (e: Exception) {
            Log.e("HardwareH264Decoder", "Error al iniciar MediaCodec: ${e.message}")
            onError("Error iniciando MediaCodec: ${e.message}")
        }
    }

    // Calibración sin recompilar: adb shell "echo 150 > /sdcard/Android/data/com.omnicast.tv/files/av_delay_ms" (se relee cada 1 s)
    private fun readDelayMs(): Long? = try {
        java.io.File(DELAY_FILE).readText().trim().toLong().coerceIn(0, 600)
    } catch (_: Exception) { null }

    private companion object {
        const val DELAY_FILE = "/sdcard/Android/data/com.omnicast.tv/files/av_delay_ms"
        const val DEFAULT_VIDEO_DELAY_MS = 120L // ponytail: estimación (preroll 80 ms + mezclador TV); calibrar con el archivo de arriba
    }

    fun pushNalUnit(nalData: ByteArray) {
        val nalType = findFirstNalType(nalData)
        // Guardar siempre en caché el último SPS/PPS (NAL 7 u 8) aunque el codec aún esté arrancando
        if (nalType == 7 || nalType == 8) {
            cachedConfigNal = nalData
        }
        if (!isRunning) return

        if (nalQueue.remainingCapacity() == 0) {
            if (nalType == 7 || nalType == 8 || nalType == 5) {
                nalQueue.pollLast()
            } else {
                // Descartar solo este P-frame entrante sin borrar jamás el SPS/PPS ni el IDR del frente
                return
            }
        }
        nalQueue.offerLast(nalData)
    }

    private fun findFirstNalType(data: ByteArray): Int {
        var i = 0
        while (i < data.size - 4) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                if (data[i + 2].toInt() == 1) {
                    return data[i + 3].toInt() and 0x1F
                } else if (data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1 && i + 4 < data.size) {
                    return data[i + 4].toInt() and 0x1F
                }
            }
            i++
        }
        return 1
    }

    @Synchronized
    fun stop() {
        isRunning = false
        nalQueue.clear()
        try { inputThread?.interrupt() } catch (_: Exception) {}
        try { outputThread?.interrupt() } catch (_: Exception) {}
        try {
            codec?.stop()
            codec?.release()
        } catch (_: Exception) {}
        codec = null
    }
}