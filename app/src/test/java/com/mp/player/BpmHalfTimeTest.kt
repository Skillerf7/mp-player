
package com.mp.player

import org.junit.Assert.assertTrue
import org.junit.Test

class BpmHalfTimeTest {
    @Test fun synthetic170PrefersHighWhenAmbiguous() {
        val env = BpmCore.syntheticEnvelope(170f, rate = 400, seconds = 45)
        val est = BpmCore.estimate(env, env.size, 400, userHint = null)
        // Allow half-time residual but prefer ~170 if confidence path works
        val ok = est.bpm in 155f..185f || (est.bpm in 75f..95f && est.confidence < 0.85f)
        assertTrue("bpm=${est.bpm} conf=${est.confidence} cands=${est.candidates}", ok)
    }

    @Test fun userHintLocksNearTarget() {
        val env = BpmCore.syntheticEnvelope(170f, rate = 400, seconds = 45)
        val est = BpmCore.estimate(env, env.size, 400, userHint = 170f)
        assertTrue("bpm=${est.bpm}", est.bpm in 160f..180f)
    }
}
