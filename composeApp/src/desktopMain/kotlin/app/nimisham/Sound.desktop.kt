package app.nimisham

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

actual fun playPcm(samples: ShortArray, rate: Int) {
    Thread {
        runCatching {
            val bytes = ByteArray(samples.size * 2)
            for (i in samples.indices) {
                val v = samples[i].toInt()
                bytes[2 * i] = (v and 0xff).toByte()
                bytes[2 * i + 1] = (v shr 8 and 0xff).toByte()
            }
            val fmt = AudioFormat(rate.toFloat(), 16, 1, true, false)
            val line = AudioSystem.getSourceDataLine(fmt)
            line.open(fmt)
            line.start()
            line.write(bytes, 0, bytes.size)
            line.drain()
            line.close()
        }
    }.apply { isDaemon = true }.start()
}
