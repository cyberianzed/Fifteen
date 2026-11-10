package app.fifteen

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncSafetyTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    @Test
    fun twoDevicesChangingDifferentSettingsBothKeepTheirChange() {
        val base = Profile(updatedAt = 100)
        // Phone turns add-ons on at 200; the PC changes the daily goal at 300.
        val phone = base.copy(addons = true, updatedAt = 200, fieldTimes = mapOf("addons" to 200L))
        val pc = base.copy(dailyGoal = 120, updatedAt = 300, fieldTimes = mapOf("dailyGoal" to 300L))
        val (onPc, pcHasNewer) = mergeProfiles(json, pc, phone)
        assertTrue(onPc.addons)
        assertEquals(120, onPc.dailyGoal)
        assertTrue(pcHasNewer) // so the PC pushes the merged copy back for the phone
        val (onPhone, _) = mergeProfiles(json, phone, onPc)
        assertTrue(onPhone.addons)
        assertEquals(120, onPhone.dailyGoal)
    }

    @Test
    fun aFreshDeviceTakesEverySyncedSetting() {
        val synced = Profile(addons = true, dailyGoal = 150, updatedAt = 500) // from an older version: no field times
        val (merged, localNewer) = mergeProfiles(json, Profile(), synced)
        assertTrue(merged.addons)
        assertEquals(150, merged.dailyGoal)
        assertFalse(localNewer)
    }

    @Test
    fun changingASettingRecordsWhenThatSettingChanged() {
        val store = Store(MemFiles(), CoroutineScope(Dispatchers.Unconfined))
        store.updateProfile { it.copy(addons = true) }
        val p = store.db.value.profile
        assertTrue("dailyGoal" in p.fieldTimes) // every setting is listed
        assertEquals(p.updatedAt, p.fieldTimes["addons"])
        assertTrue(p.fieldTimes["dailyGoal"]!! < p.updatedAt)
    }

    @Test
    fun stampsComeAfterAnythingSeenEvenWithASlowClock() {
        val store = Store(MemFiles(), CoroutineScope(Dispatchers.Unconfined))
        val ahead = nowMs() + 60 * 60_000L // another device whose clock is an hour ahead
        store.observe(ahead)
        assertTrue(store.stamp(0L) > ahead)
        // A stamp far in the future is capped, so one broken clock can't push everything years ahead.
        store.observe(nowMs() + 365L * 24 * 3_600_000L)
        assertTrue(store.stamp(0L) < nowMs() + 25L * 3_600_000L)
    }

    @Test
    fun theSignInIsSealedOnDiskAndOpensAgain() {
        val sealed = sealSecret("refresh-token-123")
        if (System.getProperty("os.name").startsWith("Windows")) assertTrue(sealed.startsWith("dpapi:"))
        assertFalse(sealed.contains("refresh-token-123") && sealed.startsWith("dpapi:"))
        assertEquals("refresh-token-123", openSecret(sealed))
        assertEquals("plain", openSecret("plain")) // older files: read as is, sealed on the next save
        assertEquals("", openSecret("dpapi:not-valid"))
    }
}
