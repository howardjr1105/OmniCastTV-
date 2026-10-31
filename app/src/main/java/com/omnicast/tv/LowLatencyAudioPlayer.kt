package com.omnicast.tv

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * Reproduce PCM 16-bit estéreo con un colchón anti-jitter: el track arranca recién con PREROLL_MS acumulados
 * y, si el buffer se vacía (underrun), se pausa y vuelve a acumular en vez de seguir sonando a trompicones.
 * Se llama siempre desde un único hilo de audio, sin sincronización propia.
 */
class LowLatencyAudioPlayer {
    private var audioTrack: AudioTrack? = null
    @Volatile private var isRunning = false
    private var playing = false
    private var prerollFrames = 0
    private var bufferedFrames = 0
    private var lastUnderruns = 0
    private var rearms = 0
    private var drops = 0
    private var lastStatsMs = 0L

    fun start(sampleRate: Int = 48000) {
        stop()
        try {
            val channelConfig = AudioFormat.CHANNEL_OUT_STEREO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBufSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)
            // Buffer mínimo + modo LOW_LATENCY = underruns con 44.1 kHz (el mezclador de la TV va a 48 kHz y no da pista rápida).
            val bufBytes = maxOf(minBufSize * 4, sampleRate * 4 * BUFFER_MS / 1000)

            audioTrack = AudioTrack.Builder()
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
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            prerollFrames = sampleRate * PREROLL_MS / 1000
            bufferedFrames = 0
            playing = false
            rearms = 0
            drops = 0
            lastStatsMs = System.currentTimeMillis()
            isRunning = true
        } catch (e: Exception) {
            Log.e("LowLatencyAudio", "Error iniciando AudioTrack: ${e.message}")
        }
    }

    fun writePcm(pcmBytes: ByteArray, offset: Int, length: Int) {
        val t = audioTrack ?: return
        if (!isRunning) return
        try {
            val n = t.write(pcmBytes, offset, length, AudioTrack.WRITE_NON_BLOCKING)
            if (n < length) drops++ // buffer lleno: descartar mantiene acotada la latencia
            if (n <= 0) return
            if (!playing) {
                bufferedFrames += n / 4
                if (bufferedFrames >= prerollFrames) {
                    t.play()
                    playing = true
                    lastUnderruns = t.underrunCount
                }
            } else if (t.underrunCount != lastUnderruns) {
                // Se vació: pausar y volver a llenar el colchón antes de seguir
                t.pause()
                playing = false
                bufferedFrames = n / 4
                rearms++
            }
            val now = System.currentTimeMillis()
            if (now - lastStatsMs >= 5000) {
                lastStatsMs = now
                Log.i("LowLatencyAudio", "rearms=$rearms drops=$drops underrunsTrack=${t.underrunCount}")
            }
        } catch (_: Exception) {}
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
        const val BUFFER_MS = 300
        const val PREROLL_MS = 80 // ponytail: fijo; subir si sigue cortándose en Wi-Fi malo, o hacerlo adaptativo
    }
}
