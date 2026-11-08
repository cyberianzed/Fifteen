package app.nimisham

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** Moments that make a sound. */
enum class Sfx { Poke, Start, Due, Good, Okay, Drift, Reward }

/** Plays 16-bit mono PCM at [rate] Hz without blocking the caller. */
expect fun playPcm(samples: ShortArray, rate: Int)

/**
 * Little sounds that match the companion, made from sine/square waves and noise (no audio files):
 * the tree is a soft marimba with leaf rustles, the spirit is bubbly boops and bells, the hunter is
 * a retro power-up. Drifts get a gentle, warm two-note sound, never a buzzer.
 */
object Sounds {
    var enabled = true
    private const val RATE = 22050
    private val cache = mutableMapOf<String, ShortArray>()

    fun play(event: Sfx, avatar: String, rarity: Int = 0) {
        if (!enabled) return
        val key = "$avatar:$event:$rarity"
        val pcm = cache.getOrPut(key) { render(event, avatar, rarity) }
        runCatching { playPcm(pcm, RATE) }
    }

    // ---------- Instruments ----------

    private class Buf(seconds: Double) {
        val data = FloatArray((seconds * RATE).toInt())
        fun add(at: Double, samples: FloatArray, gain: Float = 1f) {
            val start = (at * RATE).toInt()
            for (i in samples.indices) {
                val j = start + i
                if (j in data.indices) data[j] += samples[i] * gain
            }
        }
        fun toPcm(volume: Float = 0.32f): ShortArray {
            val peak = data.maxOfOrNull { kotlin.math.abs(it) }?.coerceAtLeast(1f) ?: 1f
            return ShortArray(data.size) { i ->
                // Short fade at both ends so nothing clicks.
                val edge = minOf(1f, i / 120f, (data.size - i) / 240f)
                (data[i] / peak * volume * edge * Short.MAX_VALUE).toInt().toShort()
            }
        }
    }

    private fun samples(dur: Double) = FloatArray((dur * RATE).toInt())

    private fun marimba(freq: Double, dur: Double = 0.45): FloatArray {
        val out = samples(dur)
        for (i in out.indices) {
            val t = i.toDouble() / RATE
            val env = exp(-t * 9)
            out[i] = (sin(2 * PI * freq * t) * env + 0.35 * sin(2 * PI * freq * 4 * t) * exp(-t * 30) + 0.15 * sin(2 * PI * freq * 10 * t) * exp(-t * 60)).toFloat()
        }
        return out
    }

    private fun bell(freq: Double, dur: Double = 0.8): FloatArray {
        val out = samples(dur)
        val partials = listOf(1.0 to 1.0, 2.76 to 0.45, 5.4 to 0.25, 8.93 to 0.12)
        for (i in out.indices) {
            val t = i.toDouble() / RATE
            var v = 0.0
            for ((m, a) in partials) v += a * sin(2 * PI * freq * m * t) * exp(-t * (3 + m * 1.5))
            out[i] = v.toFloat()
        }
        return out
    }

    private fun boop(f0: Double, f1: Double, dur: Double = 0.16): FloatArray {
        val out = samples(dur)
        var phase = 0.0
        for (i in out.indices) {
            val u = i.toDouble() / out.size
            val f = f0 + (f1 - f0) * u + 18 * sin(2 * PI * 30 * u * dur)
            phase += 2 * PI * f / RATE
            out[i] = (sin(phase) * sin(PI * u)).toFloat()
        }
        return out
    }

    private fun square(f0: Double, f1: Double, dur: Double, decay: Double = 6.0): FloatArray {
        val out = samples(dur)
        var phase = 0.0
        for (i in out.indices) {
            val u = i.toDouble() / out.size
            val t = i.toDouble() / RATE
            phase += 2 * PI * (f0 + (f1 - f0) * u) / RATE
            // Soft square: a few odd harmonics, less harsh than a pure square.
            val v = sin(phase) + sin(3 * phase) / 3 + sin(5 * phase) / 5
            out[i] = (v * 0.7 * exp(-t * decay)).toFloat()
        }
        return out
    }

    private fun noise(dur: Double, seed: Int, smooth: Int = 4, decay: Double = 18.0): FloatArray {
        val rng = Random(seed)
        val out = samples(dur)
        var acc = 0.0
        for (i in out.indices) {
            acc += (rng.nextDouble() * 2 - 1 - acc) / smooth
            val t = i.toDouble() / RATE
            out[i] = (acc * exp(-t * decay) * minOf(1.0, t * 200)).toFloat()
        }
        return out
    }

    private val pentatonic = listOf(523.25, 587.33, 659.25, 783.99, 880.0, 1046.5, 1174.7, 1318.5)

    // ---------- Sounds ----------

    private fun render(e: Sfx, avatar: String, rarity: Int): ShortArray = when (avatar) {
        "spirit" -> spirit(e, rarity)
        "hunter" -> hunter(e, rarity)
        else -> tree(e, rarity)
    }

    private fun tree(e: Sfx, rarity: Int): ShortArray = when (e) {
        Sfx.Poke -> Buf(0.5).apply { add(0.0, noise(0.35, 3, 6, 9.0), 0.8f); add(0.04, marimba(659.25, 0.3), 0.7f) }.toPcm(0.26f)
        Sfx.Start -> Buf(0.5).apply { add(0.0, marimba(523.25)) }.toPcm(0.24f)
        Sfx.Due -> Buf(0.9).apply { add(0.0, marimba(783.99)); add(0.18, marimba(1046.5)) }.toPcm()
        Sfx.Good -> Buf(0.9).apply { listOf(523.25, 659.25, 783.99, 1046.5).forEachIndexed { i, f -> add(i * 0.09, marimba(f)) } }.toPcm()
        Sfx.Okay -> Buf(0.7).apply { add(0.0, marimba(587.33)); add(0.12, marimba(783.99)) }.toPcm(0.26f)
        Sfx.Drift -> Buf(0.9).apply { add(0.0, marimba(329.63, 0.6)); add(0.22, marimba(392.0, 0.6)) }.toPcm(0.22f)
        Sfx.Reward -> sparkle(rarity) { marimba(it, 0.35) }
    }

    private fun spirit(e: Sfx, rarity: Int): ShortArray = when (e) {
        Sfx.Poke -> Buf(0.35).apply { add(0.0, boop(380.0, 900.0)); add(0.15, boop(1100.0, 1300.0, 0.08), 0.6f) }.toPcm(0.24f)
        Sfx.Start -> Buf(0.3).apply { add(0.0, boop(500.0, 800.0, 0.14)) }.toPcm(0.22f)
        Sfx.Due -> Buf(1.2).apply { add(0.0, bell(880.0)); add(0.22, bell(1318.5)) }.toPcm(0.28f)
        Sfx.Good -> Buf(0.8).apply { listOf(600.0, 800.0, 1000.0, 1300.0).forEachIndexed { i, f -> add(i * 0.07, boop(f, f * 1.25, 0.11)) } }.toPcm(0.26f)
        Sfx.Okay -> Buf(0.5).apply { add(0.0, boop(600.0, 750.0)); add(0.12, boop(800.0, 950.0)) }.toPcm(0.24f)
        Sfx.Drift -> Buf(0.6).apply { add(0.0, boop(700.0, 520.0, 0.22)); add(0.2, boop(560.0, 470.0, 0.25)) }.toPcm(0.2f)
        Sfx.Reward -> sparkle(rarity) { bell(it * 1.5, 0.5) }
    }

    private fun hunter(e: Sfx, rarity: Int): ShortArray = when (e) {
        Sfx.Poke -> Buf(0.45).apply { add(0.0, square(180.0, 820.0, 0.28, 3.0)); add(0.0, noise(0.3, 9, 2, 6.0), 0.35f) }.toPcm(0.22f)
        Sfx.Start -> Buf(0.3).apply { add(0.0, square(440.0, 880.0, 0.12, 8.0)) }.toPcm(0.2f)
        Sfx.Due -> Buf(0.9).apply {
            listOf(392.0, 523.25, 659.25).forEachIndexed { i, f -> add(i * 0.09, square(f, f, 0.12, 10.0)) }
            add(0.27, square(783.99, 783.99, 0.45, 4.0))
        }.toPcm(0.24f)
        Sfx.Good -> Buf(1.0).apply {
            listOf(523.25, 659.25, 783.99).forEachIndexed { i, f -> add(i * 0.07, square(f, f, 0.1, 12.0)) }
            add(0.21, square(1046.5, 1046.5, 0.5, 4.0)); add(0.21, square(523.25, 523.25, 0.5, 4.0), 0.5f)
        }.toPcm(0.24f)
        Sfx.Okay -> Buf(0.5).apply { add(0.0, square(523.25, 523.25, 0.1, 12.0)); add(0.1, square(783.99, 783.99, 0.25, 6.0)) }.toPcm(0.22f)
        Sfx.Drift -> Buf(0.7).apply { add(0.0, square(329.63, 329.63, 0.25, 5.0)); add(0.2, square(261.63, 293.66, 0.35, 4.0)) }.toPcm(0.18f)
        Sfx.Reward -> sparkle(rarity) { square(it, it, 0.14, 14.0) }
    }

    /** A quick run of bright notes; rarer finds get a longer, higher run. */
    private fun sparkle(rarity: Int, voice: (Double) -> FloatArray): ShortArray {
        val notes = 4 + rarity * 2
        val buf = Buf(0.12 + notes * 0.07 + 0.5)
        val rng = Random(rarity * 31 + 7)
        for (i in 0 until notes) {
            val f = pentatonic[(i + rng.nextInt(2)).coerceAtMost(pentatonic.lastIndex)] * (if (rarity >= 2 && i >= notes / 2) 2.0 else 1.0)
            buf.add(i * 0.07, voice(f), 0.8f)
        }
        return buf.toPcm(0.26f)
    }
}
