package app.fifteen

import com.sun.jna.platform.win32.Crypt32Util
import java.util.Base64

private const val PREFIX = "dpapi:"
private val isWindows = System.getProperty("os.name").startsWith("Windows")

/** Windows DPAPI: only this Windows user on this PC can open it. Elsewhere it's stored as is. */
actual fun sealSecret(plain: String): String {
    if (plain.isEmpty() || !isWindows) return plain
    return runCatching { PREFIX + Base64.getEncoder().encodeToString(Crypt32Util.cryptProtectData(plain.toByteArray(Charsets.UTF_8))) }
        .getOrDefault(plain)
}

actual fun openSecret(stored: String): String {
    if (!stored.startsWith(PREFIX)) return stored // older versions stored it plainly; it's sealed on the next save
    return runCatching { String(Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(stored.removePrefix(PREFIX))), Charsets.UTF_8) }
        .getOrDefault("")
}
