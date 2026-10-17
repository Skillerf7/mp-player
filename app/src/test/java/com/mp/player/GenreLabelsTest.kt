
package com.mp.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class GenreLabelsTest {
    @Test fun gtzanTenLabels() {
        // mirrors assets/ml/genre_labels.json
        val labels = listOf("blues", "classical", "country", "disco", "hiphop", "jazz", "metal", "pop", "reggae", "rock")
        assertEquals(10, labels.size)
        assertTrue("hiphop" in labels)
        assertTrue("deutschrap" !in labels)
    }

    @Test fun softmaxSumsToOne() {
        val logits = floatArrayOf(1f, 2f, 0.5f)
        var max = logits.maxOrNull()!!
        val ex = logits.map { kotlin.math.exp((it - max).toDouble()) }
        val sum = ex.sum()
        val probs = ex.map { it / sum }
        assertTrue(kotlin.math.abs(probs.sum() - 1.0) < 1e-6)
    }
}
