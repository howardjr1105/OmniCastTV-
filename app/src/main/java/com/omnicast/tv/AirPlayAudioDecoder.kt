package com.omnicast.tv

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer

/**
 * Decodifica el audio AAC (ELD en mirroring, LC en otros casos) que llega por RTP y lo reproduce a 44.1 kHz.
 * Todo corre en el hilo que llama a decode(): sin hilos extra.
 */
class AirPlayAudioDecoder {
    private var codec: MediaCodec? = null
    private var player: LowLatencyAudioPlayer? = null
    private val info = MediaCodec.BufferInfo()
    private var pts = 0L
    private var pcm = ByteArray(8192)

    /** @return null si arrancó bien, o el motivo del fallo para el log. */
    @Synchronized
    fun start(ct: Int): String? {
        stop()
        // ponytail: solo AAC-ELD (ct=8) y AAC-LC (ct=4); ALAC (ct=2) no tiene decoder en Android
        val csd = when (ct) {
            8 -> byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50, 0x00)
            4 -> byteArrayOf(0x12, 0x10)
            else -> return "ct=$ct no soportado"
        }
        return try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2).apply {
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    if (ct == 8) MediaCodecInfo.CodecProfileLevel.AACObjectELD
                    else MediaCodecInfo.CodecProfileLevel.AACObjectLC
                )
                setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            }
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(format, null, null, 0)
                start()
            }
            player = LowLatencyAudioPlayer().also { it.start(44100) }
            pts = 0L
            null
        } catch (e: Throwable) {
            Log.e("AirPlayAudio", "Error iniciando decoder AAC: ${e.message}")
            stop()
            "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    @Synchronized
    fun decode(au: ByteArray, offset: Int, len: Int) {
        val c = codec ?: return
        try {
            val inIdx = c.dequeueInputBuffer(5000)
            if (inIdx >= 0) {
                val buf = c.getInputBuffer(inIdx) ?: return
                buf.clear()
                val n = minOf(len, buf.remaining())
                buf.put(au, offset, n)
                c.queueInputBuffer(inIdx, 0, n, pts, 0)
                pts += 10_000L // 480 muestras @ 44.1 kHz ≈ 10.9 ms; el pts no afecta a la salida PCM
            }
            while (true) {
                val outIdx = c.dequeueOutputBuffer(info, 0)
                if (outIdx < 0) break
                val out = c.getOutputBuffer(outIdx)
                if (out != null && info.size > 0) {
                    if (pcm.size < info.size) pcm = ByteArray(info.size)
                    out.position(info.offset)
                    out.get(pcm, 0, info.size)
                    player?.writePcm(pcm, 0, info.size)
                }
                c.releaseOutputBuffer(outIdx, false)
            }
        } catch (e: Throwable) {
            Log.e("AirPlayAudio", "Error decodificando audio: ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        try { codec?.stop() } catch (_: Throwable) {}
        try { codec?.release() } catch (_: Throwable) {}
        codec = null
        player?.stop()
        player = null
    }
}
