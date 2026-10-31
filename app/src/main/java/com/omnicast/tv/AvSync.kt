package com.omnicast.tv

/**
 * Calcula cuánto hay que retrasar el video para que suene y se vea a la vez.
 *
 * La Mac marca cada cuadro de video con su hora NTP y manda por UDP :7104 paquetes que ligan el timestamp RTP del audio
 * a la hora NTP. Las dos horas son del mismo reloj de la Mac, así que el desfase entre relojes se cancela:
 *   tránsito = (llegada local) − (hora NTP del contenido)      ← incluye red + desfase de relojes
 *   retraso  = (tránsitoAudio + latenciaSalidaAudio) − (tránsitoVideo + latenciaDecodificador)
 * El tránsito se toma como mínimo de una ventana (el jitter solo suma), así que una ráfaga de Wi-Fi no mueve el resultado.
 */
object AvSync {
    private const val AUDIO_RATE = 44100L
    private const val WINDOW_MS = 5000L
    private const val DISPLAY_MS = 20L // composición + vsync tras soltar el cuadro del decodificador

    /** Latencia hasta que suena lo último escrito (colchón + mezclador + HDMI); la publica LowLatencyAudioPlayer. */
    @Volatile var audioOutMs = 0L
    /** Entrada al decodificador → cuadro soltado; la publica HardwareH264Decoder. */
    @Volatile var decodeMs = 30L

    private class MinWindow {
        var cur = Long.MAX_VALUE
        var prev = Long.MAX_VALUE
        var start = 0L
        fun add(x: Long, now: Long) {
            if (now - start > WINDOW_MS) { prev = cur; cur = Long.MAX_VALUE; start = now }
            if (x < cur) cur = x
        }
        fun min() = minOf(cur, prev)
    }

    private val video = MinWindow()
    private val audio = MinWindow()
    private var rtpRef = 0L
    private var ntpRefMs = 0L
    private var haveRef = false

    /** Hora NTP de 64 bits (segundos.fracción) → milisegundos. */
    fun ntpMs(raw: Long): Long = (raw ushr 32) * 1000 + (((raw and 0xFFFFFFFFL) * 1000) ushr 32)

    @Synchronized fun resetVideo() { video.cur = Long.MAX_VALUE; video.prev = Long.MAX_VALUE }

    @Synchronized fun resetAudio() { audio.cur = Long.MAX_VALUE; audio.prev = Long.MAX_VALUE; haveRef = false }

    @Synchronized fun onVideo(ntpMs: Long, nowMs: Long) = video.add(nowMs - ntpMs, nowMs)

    @Synchronized fun onSync(rtp: Long, ntpMs: Long) { rtpRef = rtp; ntpRefMs = ntpMs; haveRef = true }

    @Synchronized fun onAudio(rtp: Long, nowMs: Long) {
        if (!haveRef) return
        val dRtp = (rtp - rtpRef).toInt() // con signo: el timestamp RTP da la vuelta a los 32 bits
        audio.add(nowMs - (ntpRefMs + dRtp * 1000L / AUDIO_RATE), nowMs)
    }

    /** @return ms de retraso para el video, o null si aún faltan datos o salen absurdos (se usa el valor por defecto). */
    @Synchronized fun targetDelayMs(): Long? {
        val a = audio.min()
        val v = video.min()
        if (a == Long.MAX_VALUE || v == Long.MAX_VALUE) return null
        val d = (a + audioOutMs) - (v + decodeMs + DISPLAY_MS)
        return if (d in -200..1500) d else null
    }

    @Synchronized fun debug(): String {
        val a = audio.min()
        val v = video.min()
        return "vMin=${if (v == Long.MAX_VALUE) "-" else v} aMin=${if (a == Long.MAX_VALUE) "-" else a} " +
            "audioOut=${audioOutMs}ms dec=${decodeMs}ms target=${targetDelayMs() ?: "-"}"
    }
}
