package app.fifteen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.random.Random
import kotlin.test.Test

/** Renders the main screens with a month of sample data into composeApp/build/screenshots. */
class ScreenshotTest {
    private val out = File("build/screenshots").apply { mkdirs() }

    private fun seededStore(phase: Phase = Phase.Running): Store {
        val store = Store(MemFiles(), CoroutineScope(Dispatchers.Unconfined))
        val rng = Random(7)
        val cats = store.activeCategories()
        val now = nowMs()
        val today = epochDay(now)
        val blocks = mutableListOf<Block>()
        for (d in (today - 40)..today) {
            if (rng.nextInt(6) == 0 && d != today) continue
            val start = dayStartMs(d) + (8 + rng.nextInt(3)) * 3600_000L
            val n = if (d == today) ((now - start) / 900_000L).toInt().coerceIn(0, 30) - 1 else 10 + rng.nextInt(20)
            var t = start
            val history = mutableListOf<Block>()
            for (i in 0 until n) {
                val cat = if (i % 7 == 6) cats.last() else cats[rng.nextInt(cats.size - 1)]
                val rating = listOf(0, 50, 75, 75, 90, 90, 100, 100, 100)[rng.nextInt(9)]
                val b = Block(newId(), cat.id, startAt = t, minutes = 15)
                val o = score(b, rating, cat.rest, history.reversed())
                val rated = b.copy(rating = rating, ratedAt = t + 900_000, points = o.points, bonuses = o.bonuses, reward = o.reward?.id)
                blocks += rated; history += rated
                t += 900_000L + (if (rng.nextInt(5) == 0) 1_800_000L else 0L)
                if (t > now - 900_000L && d == today) break
            }
        }
        when (phase) {
            Phase.Running -> blocks += Block("live", "deep", "Draft the onboarding flow", startAt = now - 6 * 60_000L, minutes = 15, bounty = "golden")
            Phase.Due -> blocks += Block("live", "deep", "Draft the onboarding flow", startAt = now - 16 * 60_000L, minutes = 15, bounty = "egg")
            Phase.Idle -> {}
        }
        store.mutateDb { it.copy(blocks = blocks) }
        return store
    }

    private val platform = object : PlatformUi {
        override val isDesktop = true
        override fun openCheckIn() {}
        @Composable
        override fun PlatformSettings() {
            SectionTitle("On this computer")
            Toggle("Floating bar", true, "Always-on-top strip") {}
            Toggle("Companion on the bar", true) {}
        }
        @Composable
        override fun PlatformFooter() {}
    }

    private fun shot(name: String, w: Int, h: Int, content: @Composable () -> Unit) {
        ImageComposeScene(w, h, Density(1f)) { content() }.use { scene ->
            scene.render(0)
            scene.render(1_200_000_000L)
            val img = scene.render(2_400_000_000L)
            File(out, "$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }

    @Test
    fun screens() {
        val store = seededStore()
        shot("01-now", 1280, 900) { FifteenApp(store, platform, Tab.Now) }
        shot("02-insights", 1280, 2300) { FifteenApp(store, platform, Tab.Insights) }
        shot("03-garden", 1280, 1700) { FifteenApp(store, platform, Tab.Garden) }
        shot("04-settings", 1280, 1700) { FifteenApp(store, platform, Tab.Settings) }
        shot("05-phone-now", 400, 1500) { FifteenApp(store, platform, Tab.Now) }
        shot("06-focus", 900, 700) { FifteenTheme { FocusMode(store) {} } }
    }

    /** Images for the GitHub README (docs/screenshots), rendered from sample data at 1.5–2× for crispness. */
    @Test
    fun readmeShots() {
        val docs = File("../docs/screenshots").apply { mkdirs() }
        fun doc(name: String, wDp: Int, hDp: Int, density: Float, content: @Composable () -> Unit) {
            ImageComposeScene((wDp * density).toInt(), (hDp * density).toInt(), Density(density)) { content() }.use { scene ->
                scene.render(0); scene.render(1_500_000_000L)
                val img = scene.render(3_100_000_000L)
                File(docs, "$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
        }
        val store = seededStore()
        val wallpaper = androidx.compose.ui.graphics.Brush.linearGradient(
            listOf(androidx.compose.ui.graphics.Color(0xFF1F2A44), androidx.compose.ui.graphics.Color(0xFF3B2F5C), androidx.compose.ui.graphics.Color(0xFF6B4A5E)),
        )
        doc("app-now", 1280, 860, 1.5f) { FifteenApp(store, platform, Tab.Now) }
        doc("app-insights", 1280, 1480, 1.5f) { FifteenApp(store, platform, Tab.Insights, insightsOffset = -1) }
        doc("app-garden", 1280, 1180, 1.5f) { FifteenApp(store, platform, Tab.Garden) }
        doc("phone-now", 400, 860, 2f) { FifteenApp(store, platform, Tab.Now) }
        doc("floating-bar", 640, 136, 2f) {
            FifteenTheme {
                Box(Modifier.fillMaxSize().background(wallpaper).padding(16.dp), contentAlignment = Alignment.BottomStart) {
                    val v = rememberView(store)
                    Row(verticalAlignment = Alignment.Bottom) {
                        PetView("tree", v.petState(), Modifier.size(100.dp), interactive = false)
                        FocusBar(v, Modifier.width(480.dp).padding(bottom = 4.dp))
                    }
                }
            }
        }
        doc("floating-pet", 260, 300, 2f) {
            FifteenTheme {
                Box(Modifier.fillMaxSize().background(wallpaper), contentAlignment = Alignment.BottomCenter) {
                    val v = rememberView(store)
                    androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 14.dp)) {
                        PetView("tree", v.petState(), Modifier.size(210.dp), interactive = false)
                        MiniTimer(v)
                    }
                }
            }
        }
        val due = seededStore(Phase.Due)
        doc("checkin-rate", 600, 460, 2f) { FifteenTheme { Box(Modifier.fillMaxSize().background(Ink.bg)) { CheckInPanel(due) {} } } }
        due.rate("live", 100)
        doc("companions", 780, 300, 2f) {
            FifteenTheme {
                Row(Modifier.fillMaxSize().background(Ink.bg).padding(10.dp)) {
                    PetView("tree", PetState(1.15f, 5, false, level = 12), Modifier.size(250.dp), interactive = false)
                    PetView("spirit", PetState(0.9f, 4, false, level = 12, mood = 0.9f), Modifier.size(250.dp), interactive = false)
                    PetView("hunter", PetState(0.8f, 3, false, level = 22), Modifier.size(250.dp), interactive = false)
                }
            }
        }
    }

    /** Every screen at a 360dp phone width (e.g. a 720px, 320dpi phone). */
    @Test
    fun phone360() {
        fun ph(name: String, hDp: Int, content: @Composable () -> Unit) {
            ImageComposeScene(720, hDp * 2, Density(2f)) { content() }.use { scene ->
                scene.render(0); scene.render(1_500_000_000L)
                File(out, "p360-$name.png").writeBytes(scene.render(3_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
        }
        val store = seededStore()
        ph("now", 1500) { FifteenApp(store, platform, Tab.Now) }
        ph("insights", 2600) { FifteenApp(store, platform, Tab.Insights) }
        ph("garden", 2200) { FifteenApp(store, platform, Tab.Garden) }
        ph("settings", 2200) { FifteenApp(store, platform, Tab.Settings) }
        val due = seededStore(Phase.Due)
        // As in CheckInActivity: 14dp margin around the card.
        ph("checkin-rate", 520) { FifteenTheme { Box(Modifier.fillMaxSize().background(Ink.bg).padding(14.dp)) { CheckInPanel(due) {} } } }
        due.rate("live", 90)
        ph("checkin-next", 900) { FifteenTheme { Box(Modifier.fillMaxSize().background(Ink.bg).padding(14.dp)) { CheckInPanel(due) {} } } }
    }

    /** Add-ons: the tab (desktop + 360dp phone) and the chat bubble at a few stages. */
    @Test
    fun addonShots() {
        val store = seededStore()
        store.updateProfile { it.copy(addons = true) }
        store.mutateDb { it.copy(nudge = it.nudge.copy(water = mapOf(epochDay(nowMs()) to 5), eyes = mapOf(epochDay(nowMs()) to 3))) }
        fun stage(kind: String, st: String, timer: Long = 0) = store.mutateDb {
            it.copy(nudge = it.nudge.copy(active = ActiveNudge("demo-$kind-$st", kind, st, nowMs() - 20_000, timer)))
        }
        stage("eye", "ask")
        shot("addon-tab", 1280, 1700) { FifteenApp(store, platform, Tab.Addons) }
        ImageComposeScene(720, 1500 * 2, Density(2f)) { FifteenApp(store, platform, Tab.Addons) }.use { sc ->
            sc.render(0); sc.render(1_500_000_000L)
            File(out, "p360-addons.png").writeBytes(sc.render(3_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
        ImageComposeScene(720, 900 * 2, Density(2f)) { FifteenApp(store, platform, Tab.Now) }.use { sc ->
            sc.render(0); sc.render(1_500_000_000L)
            File(out, "p360-now-nudge.png").writeBytes(sc.render(3_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
        val chats = listOf(Triple("eye", "ask", 0L), Triple("eye", "timer", nowMs() + 12_000), Triple("water", "ask2", 0L), Triple("well", "low", 0L))
        for ((k, st, timer) in chats) {
            stage(k, st, timer)
            shot("chat-$k-$st", 420, 330) { FifteenTheme { Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF2C3550)).padding(10.dp)) { NudgeBubble(store) } } }
        }
    }

    /** README: add-on chats coming out of the floating pet, over a desktop-like wallpaper. */
    @Test
    fun addonDocShots() {
        val docs = File("../docs/screenshots").apply { mkdirs() }
        val wallpaper = androidx.compose.ui.graphics.Brush.linearGradient(
            listOf(androidx.compose.ui.graphics.Color(0xFF1F2A44), androidx.compose.ui.graphics.Color(0xFF3B2F5C), androidx.compose.ui.graphics.Color(0xFF6B4A5E)),
        )
        fun scene(name: String, phase: Phase, kind: String, stage: String, timerSec: Int = 0) {
            val store = seededStore(phase)
            store.updateProfile { it.copy(addons = true) }
            val now = nowMs()
            store.mutateDb { it.copy(nudge = it.nudge.copy(active = ActiveNudge("doc-$kind-$stage", kind, stage, now - 20_000, if (timerSec > 0) now + timerSec * 1000L else 0))) }
            val density = 2f
            ImageComposeScene((560 * density).toInt(), (300 * density).toInt(), Density(density)) {
                FifteenTheme {
                    val v = rememberView(store)
                    Box(Modifier.fillMaxSize().background(wallpaper).padding(16.dp), contentAlignment = Alignment.TopEnd) {
                        Row(verticalAlignment = Alignment.Top) {
                            Box(Modifier.padding(top = 40.dp)) { NudgeBubble(store, showAvatar = false, maxWidth = 330.dp, mirrored = true) }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                PetView(v.db.profile.avatar, v.petState(), Modifier.size(170.dp), interactive = false)
                                MiniTimer(v)
                            }
                        }
                    }
                }
            }.use { sc ->
                sc.render(0); sc.render(1_500_000_000L)
                File(docs, "$name.png").writeBytes(sc.render(4_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
        }
        scene("addon-eye-break", Phase.Running, "eye", "timer", timerSec = 14)
        scene("addon-water", Phase.Running, "water", "ask2")
        scene("addon-rest", Phase.Idle, "rest", "ask")
    }

    @Test
    fun checkInAndBar() {
        val due = seededStore(Phase.Due)
        shot("07-checkin-rate", 640, 620) { FifteenTheme { Box(Modifier.fillMaxSize().background(Ink.bg)) { CheckInPanel(due) {} } } }
        due.rate("live", 90)
        shot("08-checkin-next", 640, 900) { FifteenTheme { Box(Modifier.fillMaxSize().background(Ink.bg)) { CheckInPanel(due) {} } } }
        val running = seededStore()
        shot("09-bar", 600, 130) {
            FifteenTheme {
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF3A4A5C)).padding(10.dp), contentAlignment = Alignment.BottomStart) {
                    val v = rememberView(running)
                    Row(verticalAlignment = Alignment.Bottom) {
                        PetView("tree", v.petState(), Modifier.size(100.dp))
                        FocusBar(v, Modifier.width(460.dp).padding(bottom = 4.dp))
                    }
                }
            }
        }
        for (a in AVATARS) {
            for ((label, ps) in listOf("seed" to PetState(0.03f, 0, false), "mid" to PetState(0.55f, 1, false, level = 8), "full" to PetState(1.3f, 6, false, level = 24), "sleep" to PetState(0.6f, 2, true, level = 12))) {
                shot("pet-${a.id}-$label", 260, 260) { FifteenTheme { Box(Modifier.fillMaxSize().background(Ink.bg)) { PetView(a.id, ps, Modifier.fillMaxSize()) } } }
            }
        }
    }
}
