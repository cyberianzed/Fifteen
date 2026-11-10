package app.fifteen

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

private val META = setOf("updatedAt", "fieldTimes")

private fun fields(json: Json, p: Profile): JsonObject = json.encodeToJsonElement(Profile.serializer(), p).jsonObject

// Profiles from older versions have no per-setting times: every setting counts as changed at updatedAt.
// Newer ones list every setting, so a missing one (added in a later version) simply hasn't been set.
private fun Profile.timeOf(key: String): Long = if (fieldTimes.isEmpty()) updatedAt else fieldTimes[key] ?: 0L

/** Per-setting times for every setting, starting from the profile's own stamp. */
internal fun baselineTimes(json: Json, p: Profile): Map<String, Long> =
    if (p.fieldTimes.isNotEmpty()) p.fieldTimes else (fields(json, p).keys - META).associateWith { p.updatedAt }

/** Names of the settings that differ between two profiles. */
internal fun changedSettings(json: Json, old: Profile, new: Profile): Set<String> {
    val a = fields(json, old); val b = fields(json, new)
    return (a.keys + b.keys).filter { it !in META && a[it] != b[it] }.toSet()
}

/**
 * Merges settings one by one: each takes whichever device changed it last. Returns the merged profile and
 * whether this device has a newer setting the other side lacks (so it pushes the merged copy back).
 */
internal fun mergeProfiles(json: Json, local: Profile, remote: Profile): Pair<Profile, Boolean> {
    val lo = fields(json, local); val ro = fields(json, remote)
    val keys = (lo.keys + ro.keys) - META
    var localNewer = false
    val out = buildJsonObject {
        for (k in keys) {
            val remoteWins = remote.timeOf(k) > local.timeOf(k)
            val v = if (remoteWins) ro[k] ?: lo[k] else lo[k] ?: ro[k]
            if (v != null) put(k, v)
            if (!remoteWins && local.timeOf(k) > remote.timeOf(k) && lo[k] != ro[k]) localNewer = true
        }
    }
    val merged = json.decodeFromJsonElement(Profile.serializer(), out).copy(
        updatedAt = maxOf(local.updatedAt, remote.updatedAt),
        fieldTimes = keys.associateWith { maxOf(local.timeOf(it), remote.timeOf(it)) },
    )
    return merged to localNewer
}
