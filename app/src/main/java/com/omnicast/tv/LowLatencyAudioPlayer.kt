package com.omnicast.tv

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log

class LowLatencyAudioPlayer {
    private var audioTrack: AudioTrack? = null
    @Volatile private var isRunning = false

    fun start(sampleRate: Int = 48000) {
        stop()
        try {
            val channelConfig = AudioFormat.CHANNEL_OUT_STEREO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBufSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding).coerceAtLeast(4096)

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
                .setBufferSizeInBytes(minBufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }

            audioTrack = builder.build().apply { play() }
            isRunning = true
        } catch (e: Exception) {
            Log.e("LowLatencyAudio", "Error iniciando AudioTrack: ${e.message}")
        }
    }

    fun writePcm(pcmBytes: ByteArray, offset: Int, length: Int) {
        if (!isRunning) return
        try {
            audioTrack?.write(pcmBytes, offset, length, AudioTrack.WRITE_NON_BLOCKING)
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
}
