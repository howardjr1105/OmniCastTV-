package com.omnicast.tv

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import android.util.Log

/** Cálculos puros del audio (sin Android) para poder probarlos en la JVM: ver AudioPacingTest. */
object AudioPacing {
    const val MAX_PPM = 5000 // ±0,5 % de velocidad: inaudible

    /** Corrección de velocidad en partes por millón: positiva (más rápido) si sobra cola, negativa si falta. 100 ppm por ms. */
    fun ppm(troughFrames: Int, targetFrames: Int, rate: Int): Int =
        ((troughFrames - targetFrames) * 1000L / rate * 100).toInt().coerceIn(-MAX_PPM, MAX_PPM)

    /** @return paquetes RTP perdidos entre `last` y `seq` (0 = en orden), o -1 si `seq` es duplicado o tardío. Secuencia de 16 bits. */
    fun lostBefore(seq: Int, last: Int): Int {
        val d = (seq - last) and 0xFFFF
        return if (d == 0 || d > 0x8000) -1 else d - 1
    }
}

/**
 * Reproduce PCM 16-bit estéreo manteniendo una cola de audio estable dentro del AudioTrack:
 *  - arranca (y se repone tras un underrun) rellenando con silencio hasta el objetivo, sin pausar la pista;
 *  - ajusta la velocidad ±0,5 % para que la cola vuelva al objetivo (absorbe la deriva de reloj con el emisor);
 *  - si una ráfaga deja la cola muy por encima, descarta lo que llega hasta volver al objetivo (latencia acotada).
 * Los umbrales salen del buffer mínimo de la TV (≈2 bloques de su mezclador), así se adaptan a cada modelo.
 * Se llama siempre desde un único hilo de audio, sin sincronización propia.
 */
class LowLatencyAudioPlayer {
    private var audioTrack: AudioTrack? = null
    @Volatile private var isRunning = false
    private var rate = 44100
    private var curRate = 44100
    private var writtenFrames = 0L
    private var started = false
    private var minFrames = 0
    private var targetFrames = 0
    private var trimming = false
    private var lastUnderruns = 0
    private var lastUnderrunMs = 0L
    private var trough = Int.MAX_VALUE
    private var lastTrough = 0
    private var lastPaceMs = 0L
    private var lastStatsMs = 0L
    private var lastLatMs = 0L
    private var underruns = 0
    private var fills = 0
    private var drops = 0
    private var silence = ByteArray(0)
    private var bufMult = 4 // múltiplo del buffer mínimo; baja si la pista se cuelga (no se reinicia entre start())
    private var lastHead = 0L
    private var lastHeadMs = 0L

    fun start(sampleRate: Int = 48000) {
        stop()
        try {
            val channelConfig = AudioFormat.CHANNEL_OUT_STEREO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBufSize = maxOf(AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding), sampleRate * 4 / 25)
            // Un buffer grande con modo estándar manda la pista a la salida "deep buffer" (Flags 0x008 en
            // dumpsys media.audio_flinger), que en estas TV nunca consume y deja el audio mudo. LOW_LATENCY la excluye;
            // los cortes los evita el colchón controlado de abajo, no el tamaño del buffer.
            val builder = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(minBufSize * bufMult)
                .setTransferMode(AudioTrack.MODE_STREAM)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            val t = builder.build()
            audioTrack = t
            rate = sampleRate
            curRate = sampleRate
            writtenFrames = 0
            started = false
            minFrames = minBufSize / 4
            targetFrames = minFrames
            lastHead = 0
            lastHeadMs = SystemClock.elapsedRealtime()
            trimming = false
            trough = Int.MAX_VALUE
            lastTrough = 0
            underruns = 0
            fills = 0
            drops = 0
            lastStatsMs = SystemClock.elapsedRealtime()
            isRunning = true
        } catch (e: Exception) {
            Log.e("LowLatencyAudio", "Error iniciando AudioTrack: ${e.message}")
        }
    }

    fun writePcm(pcmBytes: ByteArray, offset: Int, length: Int) {
        val t = audioTrack ?: return
        if (!isRunning) return
        try {
            val now = SystemClock.elapsedRealtime()
            // ponytail: playbackHeadPosition da la vuelta a los 2^32 frames (~27 h seguidas a 44,1 kHz); reiniciar la pista si importa
            val head = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            val queued = (writtenFrames - head).toInt()
            if (started) {
                if (head != lastHead) { lastHead = head; lastHeadMs = now }
                else if (queued > 0 && now - lastHeadMs > STALL_MS && bufMult > 1) {
                    // Hay audio en cola pero la pista no avanza (salida colgada): reconstruir con un buffer menor en vez de quedar mudo
                    bufMult /= 2
                    Log.w("LowLatencyAudio", "Pista detenida (head=$head, cola=$queued): reiniciando con buffer x$bufMult")
                    start(rate)
                    return
                }
            }
            if (!started) {
                fill(t, targetFrames)
                t.play()
                started = true
                lastUnderruns = t.underrunCount
                lastUnderrunMs = now
                lastPaceMs = now
            } else if (t.underrunCount != lastUnderruns) {
                // Se vació: el hueco ya sonó. Reponer el colchón con silencio y pedir más margen para la próxima.
                lastUnderruns = t.underrunCount
                lastUnderrunMs = now
                underruns++
                targetFrames = minOf(targetFrames + rate * STEP_UP_MS / 1000, minFrames * 2)
                fill(t, targetFrames - queued)
            } else if (now - lastUnderrunMs > CALM_MS) {
                lastUnderrunMs = now
                targetFrames = maxOf(targetFrames - rate * STEP_DOWN_MS / 1000, minFrames)
            }

            // Una ráfaga (la Mac manda una al empezar) dejaría la cola alta para siempre: entra al mismo ritmo que sale.
            if (trimming) { if (queued <= targetFrames) trimming = false }
            else if (queued > minFrames * 3) trimming = true
            if (trimming) { drops++; return }

            if (queued < trough) trough = queued
            val n = t.write(pcmBytes, offset, length, AudioTrack.WRITE_NON_BLOCKING)
            if (n < length) drops++
            if (n > 0) writtenFrames += n / 4

            if (now - lastPaceMs >= 1000) {
                lastPaceMs = now
                if (trough != Int.MAX_VALUE) {
                    lastTrough = trough
                    val r = rate + (rate.toLong() * AudioPacing.ppm(trough, targetFrames, rate) / 1_000_000).toInt()
                    if (r != curRate) { t.setPlaybackRate(r); curRate = r }
                }
                trough = Int.MAX_VALUE
            }
            if (now - lastLatMs >= 200) {
                lastLatMs = now
                publishOutputLatency(t)
            }
            if (now - lastStatsMs >= 5000) {
                lastStatsMs = now
                Log.i("LowLatencyAudio", "5s: nivel=${lastTrough * 1000L / rate}ms objetivo=${targetFrames * 1000L / rate}ms " +
                    "rate=$curRate underruns=$underruns silencios=$fills drops=$drops")
                underruns = 0; fills = 0; drops = 0
            }
        } catch (_: Exception) {}
    }

    private fun fill(t: AudioTrack, frames: Int) {
        if (frames <= 0) return
        if (silence.size < frames * 4) silence = ByteArray(frames * 4)
        val n = t.write(silence, 0, frames * 4, AudioTrack.WRITE_NON_BLOCKING)
        if (n > 0) writtenFrames += n / 4
        fills++
    }

    /** Cuánto falta para que suene lo último escrito (colchón + mezclador + salida de la TV); AvSync lo usa para alinear el video. */
    private fun publishOutputLatency(t: AudioTrack) {
        val ts = AudioTimestamp()
        val ms = if (t.getTimestamp(ts)) {
            (ts.nanoTime + (writtenFrames - ts.framePosition) * 1_000_000_000L / rate - System.nanoTime()) / 1_000_000
        } else {
            (writtenFrames - (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL)) * 1000L / rate
        }
        AvSync.audioOutMs = ms.coerceIn(0, 1500)
    }

    fun stop() {
        isRunning = false
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
    }

    private companion object {
        const val STEP_UP_MS = 30      // margen extra del colchón tras cada underrun (tope: 2 × buffer mínimo)
        const val STEP_DOWN_MS = 10    // lo que se devuelve tras CALM_MS sin underruns
        const val CALM_MS = 60_000L
        const val STALL_MS = 1500L     // sin avanzar este tiempo con audio en cola = pista colgada
    }
}
