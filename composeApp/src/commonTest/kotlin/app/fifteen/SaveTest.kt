package app.fifteen

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Files that can be told to fail, and remember what was logged and backed up. */
private class FlakyFiles : TextFiles {
    val map = mutableMapOf("prefs.json" to "{\"sounds\":false}")
    var failWrites = 0
    val logs = mutableListOf<String>()
    val backups = mutableListOf<String>()
    override fun read(name: String) = map[name]
    override fun write(name: String, text: String) {
        if (failWrites > 0) { failWrites--; throw IllegalStateException("disk busy") }
        map[name] = text
    }
    override fun log(line: String) { logs += line }
    override fun backup(name: String) { backups += name }
}

class SaveTest {
    @Test
    fun aFailedSaveIsLoggedAndTheNextOneStillWorks() {
        val files = FlakyFiles()
        val store = Store(files, CoroutineScope(Dispatchers.Unconfined))
        store.updateProfile { it.copy(addons = true) }
        files.failWrites = 1
        assertFalse(store.flush())
        assertTrue(files.logs.any { "disk busy" in it })
        // Still unsaved, so the retry writes it.
        assertTrue(store.flush())
        val again = Store(files, CoroutineScope(Dispatchers.Unconfined))
        assertTrue(again.db.value.profile.addons)
    }

    @Test
    fun anUnreadableFileIsKeptAndLogged() {
        val files = FlakyFiles()
        files.map["db.json"] = "{ not json"
        Store(files, CoroutineScope(Dispatchers.Unconfined))
        assertEquals(listOf("db.json"), files.backups)
        assertTrue(files.logs.any { it.startsWith("decode db.json") })
    }

    @Test
    fun aFreshStartNeverOverridesSyncedCategories() {
        val store = Store(FlakyFiles(), CoroutineScope(Dispatchers.Unconfined))
        val db = store.db.value
        assertTrue(db.categories.isNotEmpty())
        assertTrue(db.categories.all { it.updatedAt == 0L }) // any synced copy is newer
        assertTrue(db.dirty.isEmpty()) // and the starters are never pushed
        // Editing one stamps it, so your own changes do sync.
        store.saveCategory(db.categories.first().copy(name = "Writing"))
        assertTrue(store.db.value.categories.first { it.name == "Writing" }.updatedAt > 0)
        assertTrue(store.db.value.dirty.any { it.startsWith("category:") })
    }

    @Test
    fun aSyncRoundWithNothingNewDoesNotRewriteFiles() {
        val files = FlakyFiles()
        val store = Store(files, CoroutineScope(Dispatchers.Unconfined))
        assertTrue(store.flush())
        files.failWrites = 99 // any write attempt would now fail the flush
        store.mutateDb(changeLocal = false) { it.copy(blocks = it.blocks.sortedBy { b -> b.startAt }) } // same data
        store.updatePrefs { it.copy(lastSeq = it.lastSeq) } // same value
        assertTrue(store.flush())
        assertTrue(store.db.value.dirty.isEmpty())
    }

    @Test
    fun nothingChangedMeansNoWrite() {
        val files = FlakyFiles()
        val store = Store(files, CoroutineScope(Dispatchers.Unconfined))
        assertTrue(store.flush())
        files.failWrites = 99
        assertTrue(store.flush()) // nothing new to save, so no write is attempted
    }
}
