package app.fifteen

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

enum class SyncState { NotConfigured, SignedOut, Syncing, Synced, Offline, Error }

data class SyncStatus(val state: SyncState = SyncState.SignedOut, val message: String = "", val lastOk: Long = 0)

/**
 * Supabase sync over plain REST, pushing dirty records with upsert and pulling by the server's increasing
 * `seq`; last write wins by updated_at. Two ways to sync:
 *  - a sync key (the default): rows live in `key_records` under an id derived from the key, sent in the
 *    `x-sync-id` header, and their data is encrypted with the key, so the server can't read it;
 *  - an email account (the original way): rows live in `records` under the signed-in user.
 */
class SyncEngine(private val store: Store, private val json: Json) {
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()
    private val lock = Mutex()
    private var accessToken = ""
    private var tokenExpiry = 0L

    val url get() = store.prefs.value.supabaseUrl.ifBlank { BakedConfig.SUPABASE_URL }.trimEnd('/')
    val key get() = store.prefs.value.supabaseKey.ifBlank { BakedConfig.SUPABASE_KEY }
    val configured get() = url.startsWith("https://") && key.isNotBlank()
    val keyMode get() = store.prefs.value.syncKey.isNotBlank()
    val signedIn get() = keyMode || store.prefs.value.refreshToken.isNotBlank()

    private var cachedId = "" to ""
    private fun syncId(): String {
        val k = store.prefs.value.syncKey
        if (cachedId.first != k) cachedId = k to SyncKey.syncId(k)
        return cachedId.second
    }

    private fun table() = if (keyMode) "key_records" else "records"
    private fun owner() = if (keyMode) "sync_id" else "user_id"
    private fun rest() = if (keyMode) base() + ("x-sync-id" to syncId()) else authed()

    /**
     * Starts syncing this device with [input] (a key typed, pasted or scanned). If it's signed in with email,
     * the key is first saved in that account, so signing in with the email later can recover it; the email
     * account's own copy of the data stays as it is. Everything on this device is then uploaded under the key.
     * Returns an error message, or null when done.
     */
    suspend fun useKey(input: String): String? {
        val key = SyncKey.parse(input) ?: return "That key doesn't look right. Check it and try again."
        if (!configured) return "Add your Supabase URL and key first."
        lock.withLock {
            if (store.prefs.value.refreshToken.isNotBlank()) {
                val err = runCatching { linkEmail(key) }.exceptionOrNull()
                if (err != null) return "Couldn't save the key to your email account (${err.message}). Nothing changed; try again."
            }
            accessToken = ""
            tokenExpiry = 0
            store.updatePrefs { it.copy(syncKey = key, linkedKey = "", refreshToken = "", userId = "", lastSeq = 0) }
            store.markAllDirty()
            _status.value = SyncStatus(SyncState.Syncing)
        }
        store.kick.trySend(Unit)
        return null
    }

    /** Makes a new key and starts syncing with it. */
    suspend fun createKey(): String? = useKey(SyncKey.generate())

    /** Stops syncing on this device; its data stays here and the synced copy stays under the key. */
    fun forgetKey() {
        store.updatePrefs { it.copy(syncKey = "", lastSeq = 0) }
        _status.value = SyncStatus(SyncState.SignedOut)
    }

    private suspend fun linkEmail(key: String) {
        if (!ensureToken()) throw IllegalStateException("signed out")
        val row = buildJsonArray {
            add(buildJsonObject {
                put("user_id", store.prefs.value.userId); put("kind", "synckey"); put("id", "me")
                put("data", buildJsonObject { put("key", key) }); put("updated_at", nowMs())
            })
        }
        val r = httpCall(
            "POST", "$url/rest/v1/records?on_conflict=user_id,kind,id",
            authed() + ("Prefer" to "resolution=merge-duplicates,return=minimal"), row.toString(),
        )
        if (!r.ok) throw IllegalStateException(errorText(r))
    }

    private fun base() = mapOf("apikey" to key, "Accept" to "application/json")

    suspend fun signIn(email: String, password: String, createAccount: Boolean): String? {
        if (!configured) return "Add your Supabase URL and key first."
        val body = buildJsonObject { put("email", email.trim()); put("password", password) }.toString()
        return runCatching {
            val endpoint = if (createAccount) "$url/auth/v1/signup" else "$url/auth/v1/token?grant_type=password"
            val r = httpCall("POST", endpoint, base(), body)
            if (!r.ok) return errorText(r)
            val o = json.parseToJsonElement(r.body).jsonObject
            val access = o["access_token"]?.jsonPrimitive?.content
            if (access == null) return "Account created. Confirm the email Supabase sent, then sign in."
            acceptSession(o, email.trim())
            store.kick.trySend(Unit)
            null
        }.getOrElse { "Couldn't reach Supabase: ${it.message}" }
    }

    fun signOut() {
        accessToken = ""
        tokenExpiry = 0
        store.updatePrefs { it.copy(refreshToken = "", userId = "", lastSeq = 0) }
        _status.value = SyncStatus(SyncState.SignedOut)
    }

    private fun acceptSession(o: JsonObject, email: String? = null) {
        accessToken = o["access_token"]!!.jsonPrimitive.content
        val expiresIn = o["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600
        tokenExpiry = nowMs() + (expiresIn - 120) * 1000
        val refresh = o["refresh_token"]?.jsonPrimitive?.content ?: ""
        val userId = o["user"]?.jsonObject?.get("id")?.jsonPrimitive?.content ?: store.prefs.value.userId
        store.updatePrefs { p ->
            val switchedUser = p.userId.isNotBlank() && p.userId != userId
            p.copy(
                refreshToken = refresh, userId = userId, email = email ?: p.email,
                lastSeq = if (switchedUser) 0 else p.lastSeq,
            )
        }
    }

    private suspend fun ensureToken(): Boolean {
        if (accessToken.isNotBlank() && nowMs() < tokenExpiry) return true
        val refresh = store.prefs.value.refreshToken
        if (refresh.isBlank()) return false
        val r = httpCall(
            "POST", "$url/auth/v1/token?grant_type=refresh_token", base(),
            buildJsonObject { put("refresh_token", refresh) }.toString(),
        )
        if (r.code == 400 || r.code == 401) {
            signOut()
            _status.value = SyncStatus(SyncState.SignedOut, "Session expired — sign in again.")
            return false
        }
        if (!r.ok) throw IllegalStateException(errorText(r))
        acceptSession(json.parseToJsonElement(r.body).jsonObject)
        return true
    }

    private fun authed() = base() + ("Authorization" to "Bearer $accessToken")

    /** One push + pull round. Returns false when it failed (caller backs off). */
    suspend fun syncNow(): Boolean = lock.withLock {
        if (!configured) {
            _status.value = SyncStatus(SyncState.NotConfigured); return true
        }
        if (!signedIn) {
            if (_status.value.state != SyncState.SignedOut) _status.value = SyncStatus(SyncState.SignedOut)
            return true
        }
        runCatching {
            _status.value = _status.value.copy(state = SyncState.Syncing)
            if (!keyMode && !ensureToken()) return true
            // Pull first, so what's pushed already includes the other device's changes.
            pull()
            push()
            _status.value = SyncStatus(SyncState.Synced, "", nowMs())
            true
        }.getOrElse {
            val offline = it is NetworkException
            _status.value = _status.value.copy(
                state = if (offline) SyncState.Offline else SyncState.Error,
                message = if (offline) "Offline — changes are saved and will sync later." else (it.message ?: "Sync failed"),
            )
            false
        }
    }

    private suspend fun push() {
        val db = store.db.value
        if (db.dirty.isEmpty()) return
        val pushed = mutableMapOf<String, Long>()
        val rows = pushRows(db, pushed)
        if (rows.isNotEmpty()) {
            val r = httpCall(
                "POST", "$url/rest/v1/${table()}?on_conflict=${owner()},kind,id",
                rest() + ("Prefer" to "resolution=merge-duplicates,return=minimal"), rows.toString(),
            )
            if (!r.ok) throw IllegalStateException(errorText(r))
        }
        // Clear only what didn't change again while the request was in flight.
        store.mutateDb(changeLocal = false) { d ->
            d.copy(dirty = d.dirty.filterNot { k -> if (k in pushed) updatedOf(d, k) == pushed[k] else k in db.dirty }.toSet())
        }
    }

    /** The rows to upload for [db]'s dirty records; with a sync key each one's data is encrypted. */
    internal fun pushRows(db: LocalDb, pushed: MutableMap<String, Long> = mutableMapOf()): JsonArray {
        val key = store.prefs.value.syncKey
        val ownerValue = if (keyMode) syncId() else store.prefs.value.userId
        return buildJsonArray {
            for (k in db.dirty) {
                val (kind, id) = k.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                val (data, updated) = when (kind) {
                    "block" -> db.blocks.firstOrNull { it.id == id }?.let { json.encodeToJsonElement(Block.serializer(), it) to it.updatedAt }
                    "category" -> db.categories.firstOrNull { it.id == id }?.let { json.encodeToJsonElement(Category.serializer(), it) to it.updatedAt }
                    "profile" -> json.encodeToJsonElement(Profile.serializer(), db.profile) to db.profile.updatedAt
                    "nudge" -> json.encodeToJsonElement(NudgeState.serializer(), db.nudge) to db.nudge.updatedAt
                    else -> null
                } ?: continue
                pushed[k] = updated
                val payload: JsonElement = if (keyMode) buildJsonObject { put("e", SyncKey.seal(key, kind, id, data.toString())) } else data
                add(buildJsonObject {
                    put(owner(), ownerValue); put("kind", kind); put("id", id)
                    put("data", payload); put("updated_at", updated)
                })
            }
        }
    }

    private fun updatedOf(d: LocalDb, k: String): Long? {
        val (kind, id) = k.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        return when (kind) {
            "block" -> d.blocks.firstOrNull { it.id == id }?.updatedAt
            "category" -> d.categories.firstOrNull { it.id == id }?.updatedAt
            "profile" -> d.profile.updatedAt
            "nudge" -> d.nudge.updatedAt
            else -> null
        }
    }

    private suspend fun pull() {
        var since = (store.prefs.value.lastSeq - 20).coerceAtLeast(0)
        while (true) {
            val r = httpCall(
                "GET",
                "$url/rest/v1/${table()}?select=kind,id,data,updated_at,seq&seq=gt.$since&order=seq.asc&limit=500" +
                    (if (keyMode) "&sync_id=eq.${syncId()}" else ""),
                rest(), null,
            )
            if (!r.ok) throw IllegalStateException(errorText(r))
            val rows = json.parseToJsonElement(r.body).jsonArray
            if (rows.isEmpty()) return
            merge(rows)
            val maxSeq = rows.maxOf { it.jsonObject["seq"]!!.jsonPrimitive.long }
            store.updatePrefs { it.copy(lastSeq = maxOf(it.lastSeq, maxSeq)) }
            if (rows.size < 500) return
            since = maxSeq
        }
    }

    internal fun merge(rows: JsonArray) {
        val key = store.prefs.value.syncKey
        // Rows from the server as plain JSON: with a sync key they're decrypted here (a row that doesn't
        // open with this key is skipped).
        val plain = rows.mapNotNull { row ->
            val o = row.jsonObject
            val kind = o["kind"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.content ?: ""
            val raw = o["data"] ?: return@mapNotNull null
            val data = if (key.isNotBlank()) {
                val sealed = (raw as? JsonObject)?.get("e")?.jsonPrimitive?.content ?: return@mapNotNull null
                runCatching { json.parseToJsonElement(SyncKey.open(key, kind, id, sealed)) }.getOrNull() ?: return@mapNotNull null
            } else raw
            kind to data
        }
        // An email account that was moved to a sync key keeps the key, so it can be offered here.
        if (key.isBlank()) {
            plain.lastOrNull { it.first == "synckey" }?.second?.let { d ->
                val linked = runCatching { d.jsonObject["key"]?.jsonPrimitive?.content }.getOrNull()?.let { SyncKey.parse(it) }
                if (linked != null) store.updatePrefs { it.copy(linkedKey = linked) }
            }
        }
        store.mutateDb(changeLocal = false) { start ->
            var d = start
            val blocks = d.blocks.associateBy { it.id }.toMutableMap()
            val cats = d.categories.associateBy { it.id }.toMutableMap()
            var profile = d.profile
            var nudge = d.nudge
            var pushProfile = false
            for ((kind, data) in plain) {
                runCatching {
                    when (kind) {
                        "block" -> {
                            val b = json.decodeFromJsonElement(Block.serializer(), data)
                            val local = blocks[b.id]
                            if (local == null || b.updatedAt > local.updatedAt) blocks[b.id] = b
                        }
                        "category" -> {
                            val c = json.decodeFromJsonElement(Category.serializer(), data)
                            val local = cats[c.id]
                            if (local == null || c.updatedAt > local.updatedAt) cats[c.id] = c
                        }
                        "profile" -> {
                            val p = json.decodeFromJsonElement(Profile.serializer(), data)
                            val (merged, localNewer) = mergeProfiles(json, profile, p)
                            profile = merged
                            if (localNewer) pushProfile = true
                        }
                        "nudge" -> {
                            val nn = json.decodeFromJsonElement(NudgeState.serializer(), data)
                            if (nn.updatedAt > nudge.updatedAt) nudge = nn
                        }
                    }
                }
            }
            for (row in rows) { row.jsonObject["updated_at"]?.jsonPrimitive?.longOrNull?.let { store.observe(it) } }
            d = d.copy(
                blocks = blocks.values.sortedBy { it.startAt }, categories = cats.values.toList(), profile = profile, nudge = nudge,
                dirty = if (pushProfile) d.dirty + "profile:me" else d.dirty,
            )
            normalizeLive(d)
        }
    }

    /** Two devices may each start a block offline; the newest one wins and the others end there. */
    private fun normalizeLive(d: LocalDb): LocalDb {
        val live = d.blocks.filter { it.live }
        if (live.size <= 1) return d
        val newest = live.maxBy { it.startAt }
        val ended = live.filter { it.id != newest.id }.map { it.id }.toSet()
        return d.copy(
            blocks = d.blocks.map { if (it.id in ended) it.copy(stoppedAt = newest.startAt, updatedAt = store.stamp(it.updatedAt)) else it },
            dirty = d.dirty + ended.map { "block:$it" },
        )
    }

    private fun errorText(r: HttpResult): String {
        val msg = runCatching {
            val o = json.parseToJsonElement(r.body).jsonObject
            (o["msg"] ?: o["message"] ?: o["error_description"] ?: o["error"])?.let { (it as? JsonPrimitive)?.content }
        }.getOrNull()
        if (keyMode && (r.code == 404 || msg?.contains("key_records") == true)) {
            return "Supabase needs the sync-key table: run the latest supabase/schema.sql once."
        }
        return "HTTP ${r.code}${msg?.let { ": $it" } ?: ""}"
    }
}
