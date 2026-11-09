package app.fifteen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** What each platform plugs into the shared app. */
interface PlatformUi {
    val isDesktop: Boolean
    /** Open the check-in (desktop: its own popup window; Android: an in-app sheet). */
    fun openCheckIn()
    fun keepScreenOn(on: Boolean) {}
    @Composable
    fun PlatformSettings()
    /** Optional box at the very end of Settings (Android: keep it running in the background). */
    @Composable
    fun PlatformFooter()
}

enum class Tab(val title: String) { Now("Now"), Insights("Insights"), Addons("Add-ons"), Garden("Garden"), Settings("Settings") }

/** [insightsOffset]: which week Insights opens on (screenshots use last week, which is always full). */
@Composable
fun FifteenApp(store: Store, platform: PlatformUi, initialTab: Tab = Tab.Now, insightsOffset: Int = 0) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var focusMode by remember { mutableStateOf(false) }
    LaunchedEffect(focusMode) { platform.keepScreenOn(focusMode) }
    val db by store.db.collectAsState()
    val addons = db.profile.addons
    // The Add-ons tab exists only while add-ons are switched on in Settings.
    if (!addons && tab == Tab.Addons) tab = Tab.Now
    FifteenTheme {
        Box(Modifier.fillMaxSize().background(Ink.bg)) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= 860.dp
                if (wide) {
                    Row(Modifier.fillMaxSize()) {
                        NavRail(tab, addons) { tab = it }
                        Box(Modifier.weight(1f).fillMaxHeight()) { TabContent(tab, store, platform, wide = true, insightsOffset) { focusMode = true } }
                    }
                } else {
                    Column(Modifier.fillMaxSize().statusBarsPadding()) {
                        Box(Modifier.weight(1f)) { TabContent(tab, store, platform, wide = false, insightsOffset) { focusMode = true } }
                        BottomNav(tab, addons) { tab = it }
                    }
                }
            }
            if (focusMode) FocusMode(store) { focusMode = false }
        }
    }
}

@Composable
private fun TabContent(tab: Tab, store: Store, platform: PlatformUi, wide: Boolean, insightsOffset: Int, onFocus: () -> Unit) {
    AnimatedContent(tab, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }) { t ->
        when (t) {
            Tab.Now -> NowScreen(store, platform, wide, onFocus)
            Tab.Insights -> InsightsScreen(store, wide, insightsOffset)
            Tab.Addons -> AddonsScreen(store, wide)
            Tab.Garden -> GardenScreen(store, wide)
            Tab.Settings -> SettingsScreen(store, platform, wide)
        }
    }
}

@Composable
private fun NavRail(tab: Tab, addons: Boolean, onTab: (Tab) -> Unit) {
    Column(Modifier.width(196.dp).fillMaxHeight().padding(start = 28.dp, top = 36.dp, bottom = 28.dp)) {
        Text("fifteen.", color = Ink.text, fontSize = 21.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.5.sp)
        Text("win the next fifteen", color = Ink.muted, fontSize = 12.sp)
        Spacer(Modifier.height(44.dp))
        for (t in Tab.entries.filter { it != Tab.Addons || addons }) {
            val sel = t == tab
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onTab(t) }.padding(vertical = 11.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (sel) Ink.mellow else Color.Transparent))
                Spacer(Modifier.width(12.dp))
                Text(t.title, color = if (sel) Ink.text else Ink.muted, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun BottomNav(tab: Tab, addons: Boolean, onTab: (Tab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Ink.bg).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (t in Tab.entries.filter { it != Tab.Addons || addons }) {
            val sel = t == tab
            if (t == Tab.Addons) {
                // A small "+" in the middle instead of a full label, so the bar stays as calm as before.
                Box(
                    Modifier.size(30.dp).clip(CircleShape)
                        .background(if (sel) Ink.mellow else Color.Transparent)
                        .border(1.dp, if (sel) Ink.mellow else Color(0x33FFFFFF), CircleShape)
                        .clickable { onTab(t) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("+", color = if (sel) Color(0xFF16130A) else Ink.muted, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                }
                continue
            }
            Column(
                Modifier.clip(RoundedCornerShape(14.dp)).clickable { onTab(t) }.padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(t.title, color = if (sel) Ink.text else Ink.muted, fontSize = 13.sp)
                Spacer(Modifier.height(5.dp))
                Box(Modifier.size(4.dp).clip(CircleShape).background(if (sel) Ink.mellow else Color.Transparent))
            }
        }
    }
}

/** Shared scrolling page frame with generous breathing space. */
@Composable
fun Page(wide: Boolean, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 1180.dp).fillMaxWidth()
                .padding(horizontal = if (wide) 40.dp else 18.dp, vertical = if (wide) 36.dp else 18.dp),
        ) { content() }
    }
}

/**
 * A calm full-screen view: big companion, a slow ring and the time left. On phones it keeps the screen on,
 * which is the closest thing to an always-on display an app can offer.
 */
@Composable
fun FocusMode(store: Store, onExit: () -> Unit) {
    val v = rememberView(store)
    val color = v.category?.color?.asColor() ?: Ink.mellow
    val now = v.now
    // Drift the layout a few pixels every minute against burn-in.
    val shift = ((now / 60_000L) % 7 - 3).toInt()
    Box(
        Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF15161C), Color.Black))).clickable(onClick = onExit),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.padding(start = (12 + shift * 2).dp, top = (12 + shift).dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PetView(v.db.profile.avatar, v.petState(), Modifier.size(260.dp))
            Spacer(Modifier.height(18.dp))
            when (v.phase) {
                Phase.Running -> {
                    Text(durationText(v.remaining), color = Ink.text.copy(alpha = 0.85f), fontSize = 72.sp, fontWeight = FontWeight.ExtraLight)
                    Text(v.live?.intent?.ifBlank { null } ?: v.category?.name ?: "", color = color, fontSize = 15.sp)
                    Spacer(Modifier.height(18.dp))
                    ThinProgress(v.progress, color, Modifier.width(280.dp).height(3.dp))
                    if (v.db.profile.quotes) {
                        Spacer(Modifier.height(28.dp))
                        val (q, by) = quoteFor(v.live?.id ?: "")
                        Text(q, color = Ink.faint, fontSize = 13.sp)
                        if (by.isNotBlank()) Text("— $by", color = Ink.faint.copy(alpha = 0.7f), fontSize = 11.sp)
                    }
                }
                Phase.Due -> Text("Block done — tap to check in", color = Ink.mellow, fontSize = 18.sp)
                Phase.Idle -> Text("Resting", color = Ink.muted, fontSize = 18.sp)
            }
            Spacer(Modifier.height(22.dp))
            Text(clockText(now), color = Ink.faint, fontSize = 12.sp)
        }
    }
}
