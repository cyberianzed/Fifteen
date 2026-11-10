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
 * Supabase sync over plain REST: one `records` table (kind, id, json data, updated_at, seq).
 * Pushes dirty records with upsert, pulls by the server's increasing `seq`, last write wins by updated_at.
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
    val signedIn get() = store.prefs.value.refreshToken.isNotBlank()

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
            if (!ensureToken()) return true
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
        val userId = store.prefs.value.userId
        val pushed = mutableMapOf<String, Long>()
        val rows = buildJsonArray {
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
                add(buildJsonObject {
                    put("user_id", userId); put("kind", kind); put("id", id)
                    put("data", data); put("updated_at", updated)
                })
            }
        }
        if (rows.isNotEmpty()) {
            val r = httpCall(
                "POST", "$url/rest/v1/records?on_conflict=user_id,kind,id",
                authed() + ("Prefer" to "resolution=merge-duplicates,return=minimal"), rows.toString(),
            )
            if (!r.ok) throw IllegalStateException(errorText(r))
        }
        // Clear only what didn't change again while the request was in flight.
        store.mutateDb(changeLocal = false) { d ->
            d.copy(dirty = d.dirty.filterNot { k -> if (k in pushed) updatedOf(d, k) == pushed[k] else k in db.dirty }.toSet())
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
                "$url/rest/v1/records?select=kind,id,data,updated_at,seq&seq=gt.$since&order=seq.asc&limit=500",
                authed(), null,
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

    private fun merge(rows: JsonArray) {
        store.mutateDb(changeLocal = false) { start ->
            var d = start
            val blocks = d.blocks.associateBy { it.id }.toMutableMap()
            val cats = d.categories.associateBy { it.id }.toMutableMap()
            var profile = d.profile
            var nudge = d.nudge
            var pushProfile = false
            for (row in rows) {
                val o = row.jsonObject
                val kind = o["kind"]!!.jsonPrimitive.content
                val data: JsonElement = o["data"] ?: continue
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
        return "HTTP ${r.code}${msg?.let { ": $it" } ?: ""}"
    }
}
