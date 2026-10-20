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
 * Decodificador H.264/AVC por Hardware directo a SurfaceView (Igual que AirScreen y Moonlight).
 * Elimina por completo el WebView y decodifica NAL units Annex-B en < 4 ms usando el VPU del Google TV.
 */
class HardwareH264Decoder(private val surface: Surface) {

    private var codec: MediaCodec? = null
    @Volatile private var isRunning = false
    private val nalQueue = LinkedBlockingDeque<ByteArray>(30)
    private var inputThread: Thread? = null
    private var outputThread: Thread? = null

    @Synchronized
    fun start(width: Int = 1920, height: Int = 1080, fps: Int = 60) {
        stop()
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height)
                // Prioridad en tiempo real (0 = Realtime)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                // Flags de baja latencia específicos de chipsets de Google TV (Amlogic, MediaTek, Qualcomm, Realtek)
                setInteger("low-latency", 1)
                setInteger("vendor.low-latency.enable", 1)
                setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
                setInteger("vendor.mtk.vdec.lowlatency", 1)
                setInteger("vendor.rtc-ext-dec-low-latency.enable", 1)
                setInteger("vendor.amlogic.vdec.lowlatency", 1)
            }

            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, surface, null, 0)
                start()
            }
            isRunning = true

            // Hilo de inyección de NAL units al hardware
            inputThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
                val localCodec = codec ?: return@Thread
                var pts = 0L
                while (isRunning) {
                    try {
                        val nal = nalQueue.poll(15, TimeUnit.MILLISECONDS) ?: continue
                        val inIdx = localCodec.dequeueInputBuffer(4000)
                        if (inIdx >= 0) {
                            val buf = localCodec.getInputBuffer(inIdx)
                            if (buf != null) {
                                buf.clear()
                                val len = minOf(nal.size, buf.remaining())
                                buf.put(nal, 0, len)
                                // Detectar si el paquete contiene SPS/PPS (NAL type 7 u 8)
                                val nalType = findFirstNalType(nal)
                                val flags = if (nalType == 7 || nalType == 8) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                                localCodec.queueInputBuffer(inIdx, 0, len, pts, flags)
                                pts += 16666L
                            }
                        }
                    } catch (e: Exception) {
                        if (!isRunning) break
                    }
                }
            }, "OmniCast-H264-In").apply { start() }

            // Hilo de drenado inmediato a SurfaceView (0 ms presentación)
            outputThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
                val localCodec = codec ?: return@Thread
                val info = MediaCodec.BufferInfo()
                while (isRunning) {
                    try {
                        val outIdx = localCodec.dequeueOutputBuffer(info, 4000)
                        if (outIdx >= 0) {
                            // Renderizar inmediatamente en el próximo VSYNC del televisor
                            localCodec.releaseOutputBuffer(outIdx, true)
                        }
                    } catch (e: Exception) {
                        if (!isRunning) break
                    }
                }
            }, "OmniCast-H264-Out").apply { start() }

            Log.i("HardwareH264Decoder", "MediaCodec H.264 Low-Latency iniciado ($width x $height @ $fps fps)")
        } catch (e: Exception) {
            Log.e("HardwareH264Decoder", "Error al iniciar MediaCodec: ${e.message}")
        }
    }

    fun pushNalUnit(nalData: ByteArray) {
        if (!isRunning) return
        val nalType = findFirstNalType(nalData)
        // Si la cola se llena por una ráfaga Wi-Fi y no es SPS/PPS/IDR, descartar cuadros viejos para mantener 0 lag
        if (nalQueue.remainingCapacity() == 0 && nalType != 7 && nalType != 8 && nalType != 5) {
            nalQueue.pollFirst()
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
