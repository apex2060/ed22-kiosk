package org.ed22.kiosk

import kotlin.math.PI
import kotlin.math.sin

/** Ring my device: the tone plan and the PCM for it (pure, so JVM tests cover them). Mirrors js.ring.plan in /mobile. */
object RingPlan {
    const val TOTAL_MS = 15_000L
    const val SAMPLE_RATE = 22_050
    val TONES = listOf(660, 880, 1320)
    const val BEEP_MS = 160
    const val STEP_MS = 220
    const val CYCLE_MS = 1_100

    /** One beep: [atMs] from the start, [ms] long, [hz]. Three rising beeps, a pause, repeat, until [totalMs]. */
    data class Beep(val atMs: Int, val ms: Int, val hz: Int)

    fun plan(totalMs: Long = TOTAL_MS): List<Beep> {
        val out = ArrayList<Beep>()
        var t = 0
        while (t < totalMs) {
            TONES.forEachIndexed { i, hz ->
                val at = t + i * STEP_MS
                if (at < totalMs) out += Beep(at, BEEP_MS, hz)
            }
            t += CYCLE_MS
        }
        return out
    }

    /** 16-bit mono PCM (full-scale square waves with a 5 ms edge fade against clicks) of [plan], [totalMs] long. */
    fun pcm(totalMs: Long = TOTAL_MS, rate: Int = SAMPLE_RATE): ShortArray {
        val out = ShortArray((totalMs * rate / 1000).toInt())
        val fade = rate * 5 / 1000
        for (b in plan(totalMs)) {
            val start = (b.atMs.toLong() * rate / 1000).toInt()
            val n = (b.ms.toLong() * rate / 1000).toInt()
            for (i in 0 until n) {
                val idx = start + i
                if (idx >= out.size) break
                val sq = if (sin(2 * PI * b.hz * i / rate) >= 0) 1.0 else -1.0
                val env = minOf(1.0, i / fade.toDouble(), (n - i) / fade.toDouble())
                out[idx] = (sq * env * 0.9 * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return out
    }

    /** `status.remote` as the /mobile page writes it: {id, ok, why, at}. */
    fun remote(id: String, ok: Boolean, why: String, at: Long): Map<String, Any> =
        mapOf("id" to id, "ok" to ok, "why" to why, "at" to at)
}
