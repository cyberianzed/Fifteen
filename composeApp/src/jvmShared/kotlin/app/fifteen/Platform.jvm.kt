package app.fifteen

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

actual fun nowMs(): Long = System.currentTimeMillis()

private fun zone(): ZoneId = ZoneId.systemDefault()

actual fun epochDay(ms: Long): Int = Instant.ofEpochMilli(ms).atZone(zone()).toLocalDate().toEpochDay().toInt()

actual fun dayStartMs(day: Int): Long = LocalDate.ofEpochDay(day.toLong()).atStartOfDay(zone()).toInstant().toEpochMilli()

actual fun minuteOfDay(ms: Long): Int = Instant.ofEpochMilli(ms).atZone(zone()).let { it.hour * 60 + it.minute }

actual fun weekdayOf(day: Int): Int = LocalDate.ofEpochDay(day.toLong()).dayOfWeek.value - 1

actual fun formatDay(day: Int, pattern: String): String =
    LocalDate.ofEpochDay(day.toLong()).format(DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH))

actual fun calendarRange(day: Int, monthly: Boolean, offset: Int): Pair<Int, Int> {
    val d = LocalDate.ofEpochDay(day.toLong())
    val start = if (monthly) d.withDayOfMonth(1).plusMonths(offset.toLong()) else d.withDayOfYear(1).plusYears(offset.toLong())
    val end = if (monthly) start.plusMonths(1).minusDays(1) else start.plusYears(1).minusDays(1)
    return start.toEpochDay().toInt() to end.toEpochDay().toInt()
}

private val saveLock = Any()

actual fun <T> saveLocked(block: () -> T): T = synchronized(saveLock) { block() }

actual fun newId(): String = UUID.randomUUID().toString()

actual suspend fun httpCall(method: String, url: String, headers: Map<String, String>, body: String?): HttpResult =
    withContext(Dispatchers.IO) {
        try { rawCall(method, url, headers, body) } catch (e: java.io.IOException) { throw NetworkException(e.message ?: "network error") }
    }

private fun rawCall(method: String, url: String, headers: Map<String, String>, body: String?): HttpResult {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            requestMethod = method
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }
        try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            return HttpResult(code, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "")
        } finally {
            c.disconnect()
        }
}

/** Atomic writes (temp file + rename) so a crash mid-save never corrupts the log. */
class DirTextFiles(private val dir: File) : TextFiles {
    init { dir.mkdirs() }
    override fun read(name: String): String? = File(dir, name).takeIf { it.exists() }?.readText(Charsets.UTF_8)
    @Synchronized
    override fun write(name: String, text: String) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        val target = File(dir, name)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }
}
