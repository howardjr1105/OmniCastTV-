package com.omnicast.tv

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Process
import android.os.SystemClock
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
    /** atMs = llegada local; ntpMs = hora de captura en el emisor (0 si no la hay: SPS/PPS o emisor web). */
    private class Nal(val data: ByteArray, val atMs: Long, val ntpMs: Long = 0)
    private val nalQueue = LinkedBlockingDeque<Nal>(180)
    @Volatile private var framesIn = 0
    @Volatile private var framesOut = 0
    @Volatile private var framesDropped = 0
    @Volatile private var bytesIn = 0L
    @Volatile private var appliedMs = 0L
    private val handedAt = LongArray(64) // instante en que cada cuadro entró al decodificador, indexado por pts
    @Volatile private var cachedConfigNal: ByteArray? = null
    private var inputThread: Thread? = null
    private var outputThread: Thread? = null

    @Synchronized
    fun start(width: Int = 1920, height: Int = 1080, fps: Int = 60) {
        stop()
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024) // 4 MB × N buffers es mucho en una TV de <1 GB de RAM
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
            AvSync.resetVideo()

            // Si ya había llegado un paquete SPS/PPS mientras arrancaba el codec, encolarlo de primero
            cachedConfigNal?.let { nalQueue.offerFirst(Nal(it, 0)) }

            // Hilo de inyección de NAL units al hardware
            inputThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
                val localCodec = codec ?: return@Thread
                var pts = 0L
                var trimMs = 0L
                var lastReadMs = 0L
                var offMs = Long.MIN_VALUE // instante local de salida − hora de captura, ya suavizado
                appliedMs = 0
                while (isRunning) {
                    try {
                        val item = nalQueue.poll(15, TimeUnit.MILLISECONDS) ?: continue
                        val nal = item.data
                        // Línea de retardo ANTES del decodificador (datos comprimidos, casi gratis): retener cuadros ya
                        // decodificados agota los pocos buffers de salida del OMX de esta TV y la imagen se arrastra.
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastReadMs > 1000) {
                            lastReadMs = now
                            trimMs = readTrimMs() ?: trimMs
                        }
                        // Retardo extra medido por AvSync (o el valor por defecto si aún no hay datos) + ajuste manual.
                        val extra = (AvSync.targetDelayMs() ?: DEFAULT_VIDEO_DELAY_MS) + trimMs
                        val release: Long
                        if (item.ntpMs > 0) {
                            // Cada cuadro sale a (hora de captura + tránsito mínimo + extra): el jitter del Wi-Fi no llega a la
                            // pantalla y el video se ve fluido. Un cuadro tardío sale de inmediato (wait ≤ 0).
                            val targetOff = (AvSync.videoBaseMs() ?: (item.atMs - item.ntpMs)) + extra.coerceIn(MIN_JITTER_MS, MAX_DELAY_MS)
                            offMs = if (offMs == Long.MIN_VALUE) (item.atMs - item.ntpMs) + extra.coerceIn(MIN_JITTER_MS, MAX_DELAY_MS)
                                    else offMs + (targetOff - offMs).coerceIn(-SLEW_MS, SLEW_MS)
                            release = item.ntpMs + offMs
                            appliedMs = release - item.atMs
                        } else {
                            // Sin hora de captura (emisor web): retardo fijo desde la llegada
                            appliedMs += (extra.coerceIn(0, MAX_DELAY_MS) - appliedMs).coerceIn(-SLEW_MS, SLEW_MS)
                            release = item.atMs + appliedMs
                        }
                        val wait = (release - now).coerceAtMost(MAX_DELAY_MS + 200)
                        if (item.atMs > 0 && wait > 0) Thread.sleep(wait)
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
                                handedAt[((pts / 16666) % 64).toInt()] = SystemClock.elapsedRealtime()
                                if (nalType != 7 && nalType != 8) {
                                    pts += 16666L
                                    framesIn++
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
                var lastStatsMs = SystemClock.elapsedRealtime()
                while (isRunning) {
                    try {
                        val outIdx = localCodec.dequeueOutputBuffer(info, 10000)
                        if (outIdx >= 0) {
                            val d = SystemClock.elapsedRealtime() - handedAt[((info.presentationTimeUs / 16666) % 64).toInt()]
                            if (d in 0..400) AvSync.decodeMs = (AvSync.decodeMs * 7 + d) / 8
                            localCodec.releaseOutputBuffer(outIdx, true)
                            framesOut++
                        }
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastStatsMs >= 5000) {
                            Log.i("HardwareH264Decoder", "5s: in=$framesIn out=$framesOut dropped=$framesDropped queue=${nalQueue.size} " +
                                "kbps=${bytesIn * 8 / 5000} applied=${appliedMs}ms ${AvSync.debug()}")
                            bytesIn = 0
                            framesIn = 0; framesOut = 0; framesDropped = 0
                            lastStatsMs = now
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

    // Ajuste fino sin recompilar, en ms con signo, se SUMA al retraso medido:
    //   adb shell "echo 40 > /sdcard/Android/data/com.omnicast.tv/files/av_delay_ms"   (se relee cada 1 s)
    private fun readTrimMs(): Long? = try {
        java.io.File(DELAY_FILE).readText().trim().toLong().coerceIn(-300, 600)
    } catch (_: Exception) { null }

    private companion object {
        const val DELAY_FILE = "/sdcard/Android/data/com.omnicast.tv/files/av_delay_ms"
        const val DEFAULT_VIDEO_DELAY_MS = 50L // solo mientras AvSync no tiene datos (p. ej. antes de que empiece el audio)
        const val MAX_DELAY_MS = 800L
        const val MIN_JITTER_MS = 60L // colchón mínimo para absorber el jitter del Wi-Fi (ping en reposo hasta ~66 ms)
        const val SLEW_MS = 3L
    }

    fun pushNalUnit(nalData: ByteArray) {
        val nalType = findFirstNalType(nalData)
        // Guardar siempre en caché el último SPS/PPS (NAL 7 u 8) aunque el codec aún esté arrancando
        if (nalType == 7 || nalType == 8) {
            cachedConfigNal = nalData
        }
        if (!isRunning) return
        bytesIn += nalData.size

        if (nalQueue.remainingCapacity() == 0) {
            if (nalType == 7 || nalType == 8 || nalType == 5) {
                nalQueue.pollLast()
            } else {
                // Descartar solo este P-frame entrante sin borrar jamás el SPS/PPS ni el IDR del frente
                framesDropped++
                return
            }
        }
        // onVideo() (hilo de red, justo antes de esta llamada) dejó la hora de captura de este cuadro; SPS/PPS no la llevan
        val ntp = if (nalType == 7 || nalType == 8) 0L else AvSync.lastVideoNtpMs
        nalQueue.offerLast(Nal(nalData, SystemClock.elapsedRealtime(), ntp))
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