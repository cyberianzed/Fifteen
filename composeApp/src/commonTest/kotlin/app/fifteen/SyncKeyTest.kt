package app.fifteen

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncKeyTest {
    private fun store(files: TextFiles = MemFiles()) = Store(files, CoroutineScope(Dispatchers.Unconfined))

    /** Turns pushed rows into what the server sends back on a pull (adds seq). */
    private fun asPulled(rows: JsonArray): JsonArray = buildJsonArray {
        rows.forEachIndexed { i, r ->
            val o = r.jsonObject
            add(buildJsonObject { o.forEach { (k, v) -> put(k, v) }; put("seq", i + 1L) })
        }
    }

    @Test
    fun aNewKeyLooksRightAndReadsBack() {
        val key = SyncKey.generate()
        assertTrue(Regex("^F15(-[0-9A-HJKMNP-TV-Z]{4}){6}$").matches(key), key)
        assertEquals(key, SyncKey.parse(key))
        assertNotEquals(key, SyncKey.generate())
    }

    @Test
    fun keysAreReadHoweverTheyWereTypedOrScanned() {
        val key = SyncKey.generate()
        val loose = key.lowercase().replace("-", " ")
        assertEquals(key, SyncKey.parse(loose))
        assertEquals(key, SyncKey.parse(key.replace("-", "")))
        assertEquals(key, SyncKey.parse(SyncKey.link(key)))
        assertEquals(key, SyncKey.parse("  $key \n"))
        val withZero = SyncKey.generate().let { k -> generateSequence { SyncKey.generate() }.first { '0' in it.drop(4) } }
        assertEquals(withZero, SyncKey.parse(withZero.substring(0, 4) + withZero.drop(4).replace('0', 'O')))
    }

    @Test
    fun aTypoIsCaughtInsteadOfMakingANewEmptyKey() {
        val key = SyncKey.generate()
        val chars = key.toCharArray()
        val i = chars.indexOfLast { it != '-' }
        chars[i - 2] = if (chars[i - 2] == 'A') 'B' else 'A'
        assertNull(SyncKey.parse(String(chars)))
        assertNull(SyncKey.parse(key.dropLast(1)))
        assertNull(SyncKey.parse("hello"))
    }

    @Test
    fun theServerIdNeverRevealsTheKey() {
        val key = SyncKey.generate()
        val id = SyncKey.syncId(key)
        assertEquals(64, id.length)
        assertEquals(id, SyncKey.syncId(key.lowercase()))
        assertNotEquals(id, SyncKey.syncId(SyncKey.generate()))
        assertFalse(id.contains(key.drop(4).replace("-", "").lowercase().take(8)))
    }

    @Test
    fun dataIsEncryptedAndOnlyOpensForTheSameKeyAndRecord() {
        val key = SyncKey.generate()
        val plain = """{"name":"Deep work"}"""
        val sealed = SyncKey.seal(key, "category", "deep", plain)
        assertFalse(sealed.contains("Deep"))
        assertNotEquals(sealed, SyncKey.seal(key, "category", "deep", plain)) // a fresh nonce every time
        assertEquals(plain, SyncKey.open(key, "category", "deep", sealed))
        assertFailsWith<Exception> { SyncKey.open(key, "category", "learn", sealed) } // moved to another record
        assertFailsWith<Exception> { SyncKey.open(SyncKey.generate(), "category", "deep", sealed) } // another key
    }

    @Test
    fun usingAKeyUploadsEverythingEncryptedUnderTheKey() = runBlocking {
        val s = store()
        assertNotNull(s.sync.useKey("not a key"))
        val key = SyncKey.generate()
        assertNull(s.sync.useKey(key))
        assertEquals(key, s.prefs.value.syncKey)
        assertEquals(0L, s.prefs.value.lastSeq)
        val db = s.db.value
        assertTrue(db.categories.all { "category:${it.id}" in db.dirty })
        assertTrue("profile:me" in db.dirty)

        val rows = s.sync.pushRows(db)
        val text = rows.toString()
        assertFalse(text.contains("Deep work")) // category names never leave the device readable
        assertFalse(text.contains("user_id"))
        val first = rows.first().jsonObject
        assertEquals(SyncKey.syncId(key), first["sync_id"]!!.jsonPrimitive.content)
        assertEquals(setOf("e"), first["data"]!!.jsonObject.keys)
    }

    @Test
    fun anotherDeviceWithTheSameKeyGetsTheData() = runBlocking {
        val key = SyncKey.generate()
        val pc = store()
        pc.sync.useKey(key)
        pc.saveCategory(pc.db.value.categories.first().copy(name = "Writing"))
        val rows = asPulled(pc.sync.pushRows(pc.db.value))

        val phone = store()
        phone.sync.useKey(key)
        phone.sync.merge(rows)
        assertTrue(phone.db.value.categories.any { it.name == "Writing" })

        val stranger = store()
        stranger.sync.useKey(SyncKey.generate())
        stranger.sync.merge(rows) // can't open them: nothing changes
        assertFalse(stranger.db.value.categories.any { it.name == "Writing" })
    }

    @Test
    fun aKeySavedInTheEmailAccountIsOfferedAfterSignIn() {
        val s = store()
        val key = SyncKey.generate()
        s.sync.merge(buildJsonArray {
            add(buildJsonObject {
                put("kind", "synckey"); put("id", "me"); put("updated_at", 1L); put("seq", 1L)
                put("data", buildJsonObject { put("key", key) })
            })
        })
        assertEquals(key, s.prefs.value.linkedKey)
        assertEquals("", s.prefs.value.syncKey) // offered, not switched
    }

    @Test
    fun theKeyIsSealedOnDisk() = runBlocking {
        val files = MemFiles()
        val s = store(files)
        val key = SyncKey.generate()
        s.sync.useKey(key)
        assertTrue(s.flush())
        val onDisk = files.map["prefs.json"]!!
        if (System.getProperty("os.name").startsWith("Windows")) assertFalse(onDisk.contains(key))
        assertEquals(key, store(files).prefs.value.syncKey)
    }
}
