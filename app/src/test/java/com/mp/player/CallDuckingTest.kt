package com.mp.player

import org.junit.Assert.assertEquals
import org.junit.Test

class CallDuckingTest {
    @Test fun normalVolumeIsFull() {
        assertEquals(1f, CallDucking.normalVolume(), 0.001f)
    }
}
