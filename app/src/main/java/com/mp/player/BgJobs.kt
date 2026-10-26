package com.mp.player

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Begrenzter Hintergrund-Executor für Cover-Suche und ähnliche IO-Jobs.
 * Nicht für DSP/Audio. Analyse behält eigenen MIN_PRIORITY-Thread.
 * Cover-Jobs sind abbruchbar und an eine Track-URI gebunden (kein veraltetes Ergebnis).
 */
object BgJobs {
    private val seq = AtomicInteger(0)
    private val pool = Executors.newFixedThreadPool(2, ThreadFactory { r ->
        Thread(r, "sp-bg-${seq.incrementAndGet()}").apply {
            priority = Thread.NORM_PRIORITY - 1
            isDaemon = true
        }
    })
    private val main = Handler(Looper.getMainLooper())
    private val coverFuture = AtomicReference<Future<*>?>(null)
    @Volatile private var coverUri: String? = null

    fun submitCover(forUri: String, block: () -> Unit) {
        coverUri = forUri
        coverFuture.getAndSet(null)?.cancel(true)
        val f = pool.submit {
            try {
                if (Thread.interrupted()) return@submit
                if (coverUri != forUri) return@submit
                block()
            } catch (_: InterruptedException) {
                // abgebrochen
            } catch (_: Throwable) {
                // Cover-Fehler stoppen nie den Player
            }
        }
        coverFuture.set(f)
    }

    fun isCoverStillValid(forUri: String): Boolean = coverUri == forUri

    fun cancelCover() {
        coverUri = null
        coverFuture.getAndSet(null)?.cancel(true)
    }

    fun postMain(block: () -> Unit) {
        main.post {
            try {
                block()
            } catch (_: Throwable) {
            }
        }
    }
}
