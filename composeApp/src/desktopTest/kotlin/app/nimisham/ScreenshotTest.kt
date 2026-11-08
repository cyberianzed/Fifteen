package app.nimisham

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
        shot("01-now", 1280, 900) { NimishamApp(store, platform, Tab.Now) }
        shot("02-insights", 1280, 2300) { NimishamApp(store, platform, Tab.Insights) }
        shot("03-garden", 1280, 1700) { NimishamApp(store, platform, Tab.Garden) }
        shot("04-settings", 1280, 1700) { NimishamApp(store, platform, Tab.Settings) }
        shot("05-phone-now", 400, 1500) { NimishamApp(store, platform, Tab.Now) }
        shot("06-focus", 900, 700) { NimishamTheme { FocusMode(store) {} } }
    }

    @Test
    fun checkInAndBar() {
        val due = seededStore(Phase.Due)
        shot("07-checkin-rate", 640, 620) { NimishamTheme { Box(Modifier.fillMaxSize().background(Ink.bg)) { CheckInPanel(due) {} } } }
        due.rate("live", 90)
        shot("08-checkin-next", 640, 900) { NimishamTheme { Box(Modifier.fillMaxSize().background(Ink.bg)) { CheckInPanel(due) {} } } }
        val running = seededStore()
        shot("09-bar", 600, 130) {
            NimishamTheme {
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
                shot("pet-${a.id}-$label", 260, 260) { NimishamTheme { Box(Modifier.fillMaxSize().background(Ink.bg)) { PetView(a.id, ps, Modifier.fillMaxSize()) } } }
            }
        }
    }
}
