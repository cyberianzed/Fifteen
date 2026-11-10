package app.fifteen

/** Wall clock in epoch milliseconds. */
expect fun nowMs(): Long

/** Local calendar day number (days since 1970-01-01 in the device time zone). */
expect fun epochDay(ms: Long): Int

/** Epoch ms of local midnight that starts [day]. */
expect fun dayStartMs(day: Int): Long

/** Minutes since local midnight, 0..1439. */
expect fun minuteOfDay(ms: Long): Int

/** 0 = Monday … 6 = Sunday. */
expect fun weekdayOf(day: Int): Int

/** Formats a local day with a java.time pattern such as "EEE d MMM". */
expect fun formatDay(day: Int, pattern: String): String

/** First and last day of the month (or year) containing [day], shifted by [offset] months (years). */
expect fun calendarRange(day: Int, monthly: Boolean, offset: Int): Pair<Int, Int>

/** Runs [block] under one process-wide lock (used so saves never interleave). */
expect fun <T> saveLocked(block: () -> T): T

/** Random unique id for records. */
expect fun newId(): String

/** Seals a secret (the sync sign-in) before it's written to disk; Windows encrypts it for this user only. */
expect fun sealSecret(plain: String): String

/** Opens a sealed secret; "" if it can't be opened (for example the file was copied to another PC). */
expect fun openSecret(stored: String): String

class HttpResult(val code: Int, val body: String) {
    val ok get() = code in 200..299
}

class NetworkException(message: String) : Exception(message)

/** Plain HTTPS call; never throws for HTTP status codes, [NetworkException] for network failures. */
expect suspend fun httpCall(method: String, url: String, headers: Map<String, String>, body: String?): HttpResult

/** Small named text files in the app's private data folder. */
interface TextFiles {
    fun read(name: String): String?
    fun write(name: String, text: String)
    /** Adds a line to a small log next to the data (errors only), so a failed save leaves a trace. */
    fun log(line: String) {}
    /** Keeps a copy of a file that couldn't be read, so it's never silently replaced. */
    fun backup(name: String) {}
}

fun clockText(ms: Long): String {
    val m = minuteOfDay(ms)
    val h = m / 60
    val h12 = if (h % 12 == 0) 12 else h % 12
    return "$h12:${(m % 60).toString().padStart(2, '0')}${if (h < 12) "am" else "pm"}"
}

fun durationText(ms: Long): String {
    val total = (ms.coerceAtLeast(0) + 999) / 1000
    val m = total / 60
    val s = total % 60
    return "$m:${s.toString().padStart(2, '0')}"
}

fun hoursText(minutes: Double): String {
    val h = (minutes / 60).toInt()
    val m = (minutes % 60).toInt()
    return if (h == 0) "${m}m" else if (m == 0) "${h}h" else "${h}h ${m}m"
}

/** Platform text rendering tweaks (sharper hinting on Windows). */
expect fun crispText(): androidx.compose.ui.text.PlatformTextStyle?
