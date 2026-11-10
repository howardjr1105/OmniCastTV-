package com.omnicast.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioPacingTest {
    @Test fun ppmFollowsQueueErrorAndClamps() {
        val rate = 44100
        assertEquals(0, AudioPacing.ppm(4410, 4410, rate))
        assertEquals(1000, AudioPacing.ppm(4410 + 441, 4410, rate))   // sobran 10 ms -> +0,1 %
        assertEquals(-1000, AudioPacing.ppm(4410 - 441, 4410, rate))  // faltan 10 ms -> -0,1 %
        assertEquals(AudioPacing.MAX_PPM, AudioPacing.ppm(44100, 4410, rate))
        assertEquals(-AudioPacing.MAX_PPM, AudioPacing.ppm(0, 44100, rate))
    }

    @Test fun rtpSequenceGaps() {
        assertEquals(0, AudioPacing.lostBefore(11, 10))       // en orden
        assertEquals(2, AudioPacing.lostBefore(13, 10))       // se perdieron 11 y 12
        assertEquals(-1, AudioPacing.lostBefore(10, 10))      // duplicado
        assertEquals(-1, AudioPacing.lostBefore(9, 10))       // tardío
        assertEquals(0, AudioPacing.lostBefore(0, 0xFFFF))    // vuelta de 16 bits
        assertEquals(-1, AudioPacing.lostBefore(0xFFFF, 0))   // tardío a través de la vuelta
    }
}
