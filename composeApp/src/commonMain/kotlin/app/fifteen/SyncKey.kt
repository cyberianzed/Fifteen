package app.fifteen

/**
 * A sync key: the only thing two devices need to share to sync, with no account. It looks like
 * `F15-7KQ2-M9XD-4TPA-HW3C-8NRB` (23 random characters, about 115 bits, plus one check character that
 * catches typos). From it come two separate secrets:
 *  - the sync id the server files rows under (it never sees the key itself), and
 *  - the encryption key, so the server only ever stores scrambled data.
 */
object SyncKey {
    /** Crockford base32: no I, L, O or U, so it's easy to read and type. */
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val BODY = 23
    const val LINK_PREFIX = "fifteen://key/"

    fun generate(): String {
        val bytes = secureRandomBytes(BODY)
        val body = bytes.map { ALPHABET[it.toInt() and 31] }.joinToString("")
        return format(body + check(body))
    }

    /**
     * Reads a key however it was pasted (lower case, spaces, missing dashes, O for 0, a fifteen:// link).
     * Returns the canonical form, or null if it isn't a valid key (wrong length or a typo).
     */
    fun parse(input: String): String? {
        var s = input.trim()
        if (s.startsWith(LINK_PREFIX, ignoreCase = true)) s = s.substring(LINK_PREFIX.length)
        s = s.uppercase().filter { it.isLetterOrDigit() }
        if (s.startsWith("F15")) s = s.substring(3)
        s = s.map { when (it) { 'O' -> '0'; 'I', 'L' -> '1'; else -> it } }.joinToString("")
        if (s.length != BODY + 1 || s.any { it !in ALPHABET }) return null
        val body = s.substring(0, BODY)
        return if (s.last() == check(body)) format(s) else null
    }

    /** The id rows are stored under on the server (hex, 64 characters). */
    fun syncId(key: String): String = hmacSha256(material(key), "fifteen/sync-id/v1".encodeToByteArray()).toHex()

    /** Encrypts one record for the server; [kind] and [id] are bound in so rows can't be swapped. */
    fun seal(key: String, kind: String, id: String, plain: String): String =
        base64Encode(aesGcmSeal(encKey(key), plain.encodeToByteArray(), "$kind:$id".encodeToByteArray()))

    /** Decrypts a record from the server; throws if the key, kind or id don't match. */
    fun open(key: String, kind: String, id: String, sealed: String): String =
        aesGcmOpen(encKey(key), base64Decode(sealed), "$kind:$id".encodeToByteArray()).decodeToString()

    fun link(key: String) = LINK_PREFIX + key

    private fun encKey(key: String) = hmacSha256(material(key), "fifteen/encryption/v1".encodeToByteArray())

    private fun material(key: String): ByteArray = (parse(key) ?: key).filter { it != '-' }.encodeToByteArray()

    private fun check(body: String): Char {
        var sum = 0
        body.forEachIndexed { i, c -> sum += (i + 1) * ALPHABET.indexOf(c) }
        return ALPHABET[sum % 31]
    }

    private fun format(s: String) = "F15-" + s.chunked(4).joinToString("-")

    private fun ByteArray.toHex() = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}

/** Set when the app is opened from a fifteen://key/ link (a scanned QR code); Settings offers to join it. */
val PendingSyncKey = androidx.compose.runtime.mutableStateOf<String?>(null)
