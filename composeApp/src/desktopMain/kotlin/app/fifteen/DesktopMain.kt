package app.fifteen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.awt.ComposeWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.math.abs
import kotlin.system.exitProcess

private const val INSTANCE_PORT = 47615

fun main(args: Array<String>) {
    // One instance only: a second launch just asks the first to show its window.
    val server = runCatching { ServerSocket(INSTANCE_PORT, 4, InetAddress.getLoopbackAddress()) }.getOrNull()
    if (server == null) {
        runCatching { Socket(InetAddress.getLoopbackAddress(), INSTANCE_PORT).use { it.getOutputStream().write(1) } }
        exitProcess(0)
    }
    val dir = dataDir()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = Store(DirTextFiles(dir), scope)
    // Add-ons talk through the floating companion, so they only run while it's out.
    store.nudges.petOut = { it.showBar && !it.petHidden }
    scope.launch { store.syncLoop { 5_000 } }
    scope.launch { store.nudges.loop() }
    Runtime.getRuntime().addShutdownHook(Thread { store.flush() })
    if (store.prefs.value.autostart) Autostart.set(true)

    val showRequests = mutableStateOf(0)
    Thread {
        while (true) runCatching { server.accept().use { showRequests.value++ } }
    }.apply { isDaemon = true }.start()

    val startMinimized = "--minimized" in args
    application { DesktopApp(store, startMinimized, showRequests.value) }
}

@Composable
private fun ApplicationScope.DesktopApp(store: Store, startMinimized: Boolean, showRequest: Int) {
    var mainOpen by remember { mutableStateOf(!startMinimized) }
    var checkInOpen by remember { mutableStateOf(false) }
    val prefs by store.prefs.collectAsState()
    val v = rememberView(store)

    LaunchedEffect(showRequest) { if (showRequest > 0) mainOpen = true }

    // Ask what's first when the computer starts (or the app opens) and nothing is running.
    LaunchedEffect(Unit) {
        delay(1500)
        if (store.prefs.value.askOnStart && store.phase() == Phase.Idle) checkInOpen = true
    }
    // Pop the check-in when a block ends; nudge again every 10 minutes if it's still waiting.
    var promptedAt by remember { mutableStateOf(0L to "") }
    LaunchedEffect(v.phase, v.live?.id, v.now / 60_000) {
        val id = v.live?.id ?: return@LaunchedEffect
        if (v.phase != Phase.Due) return@LaunchedEffect
        if (promptedAt.second != id || (!checkInOpen && v.now - promptedAt.first > 10 * 60_000L)) {
            promptedAt = v.now to id
            if (!checkInOpen) Sounds.play(Sfx.Due, store.db.value.profile.avatar)
            checkInOpen = true
        }
    }

    // "Start a block" from the rest check-in opens the picker.
    LaunchedEffect(Unit) { store.nudges.onStartRequested = { checkInOpen = true } }
    var menuAt by remember { mutableStateOf<java.awt.Point?>(null) }

    val platform = remember {
        object : PlatformUi {
            override val isDesktop = true
            override fun openCheckIn() { checkInOpen = true }
            @Composable
            override fun PlatformSettings() = DesktopSettings(store)
        }
    }

    val progressBucket = (v.progress * 40).toInt()
    val trayIcon = remember(progressBucket, v.phase, v.category?.color) {
        RingPainter(if (v.phase == Phase.Running) v.progress else if (v.phase == Phase.Due) 1f else 0f, v.category?.color?.asColor() ?: Ink.mellow, v.phase)
    }
    Tray(
        icon = trayIcon,
        tooltip = when (v.phase) {
            Phase.Running -> "${v.category?.name ?: ""} · ${durationText(v.remaining)} left"
            Phase.Due -> "Block done — check in"
            Phase.Idle -> "Fifteen · resting"
        },
        onAction = { mainOpen = true },
        menu = {
            Item("Open Fifteen", onClick = { mainOpen = true })
            Item(if (v.phase == Phase.Due) "Check in" else if (v.phase == Phase.Idle) "Start a block" else "Switch activity", onClick = { checkInOpen = true })
            if (v.phase != Phase.Idle) Item("Stop this block", onClick = { store.stopTracking() })
            Separator()
            if (!prefs.showBar || prefs.petHidden) Item("Show pet", onClick = { store.showPet() })
            else Item("Hide pet", onClick = { store.hidePet() })
            CheckboxItem("Extended bar", prefs.barMode == "bar") { on -> store.updatePrefs { it.copy(barMode = if (on) "bar" else "pet", showPet = true) } }
            Separator()
            Item("Quit", onClick = ::exitApplication)
        },
    )

    if (mainOpen) {
        val state = rememberWindowState(size = DpSize(1280.dp, 880.dp), position = WindowPosition(Alignment.Center))
        Window(onCloseRequest = { mainOpen = false }, title = "Fifteen", state = state, icon = remember { RingPainter(0.7f, Ink.mellow, Phase.Running) }) {
            LaunchedEffect(showRequest) { window.toFront() }
            FifteenApp(store, platform)
        }
    }

    val petShown = prefs.showBar && !prefs.petHidden
    if (petShown) FloatingBar(
        store, v, prefs,
        onOpen = { if (v.phase == Phase.Running) mainOpen = true else checkInOpen = true },
        onMenu = { menuAt = it },
    )

    // Add-on chat: the floating companion itself talks; the bubble comes out of it.
    val anchor = barBounds.value
    if (petShown && anchor != null && store.nudges.visible(v.db, prefs)) BubbleWindow(store, anchor, prefs.showPet && prefs.barMode == "pet")

    menuAt?.let { at ->
        PetMenu(at, onDismiss = { menuAt = null }, items = buildList {
            when (v.phase) {
                Phase.Idle -> add("Start a block" to { checkInOpen = true })
                Phase.Due -> add("Check in" to { checkInOpen = true })
                Phase.Running -> add("Switch activity" to { checkInOpen = true })
            }
            add(HIDE_LABEL to { store.hidePet() })
            add((if (prefs.barMode == "pet") "Show extended bar" else "Show pet only") to {
                store.updatePrefs { it.copy(barMode = if (it.barMode == "pet") "bar" else "pet", showPet = true) }
            })
            add("Open Fifteen" to { mainOpen = true })
            if (v.phase == Phase.Idle) add("Quit" to { exitApplication() })
            else add("Stop this block" to { store.stopTracking() })
        })
    }

    if (checkInOpen) {
        val state = rememberWindowState(size = DpSize(640.dp, 760.dp), position = WindowPosition(Alignment.Center))
        Window(
            onCloseRequest = { checkInOpen = false }, title = "Check in", state = state,
            undecorated = true, transparent = true, alwaysOnTop = true, resizable = false,
            onPreviewKeyEvent = { if (it.key == Key.Escape) { checkInOpen = false; true } else false },
        ) {
            LaunchedEffect(Unit) { window.toFront(); window.requestFocus() }
            FifteenTheme {
                Box(
                    Modifier.fillMaxSize().padding(10.dp).clip(RoundedCornerShape(28.dp)).background(Ink.bg)
                        .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(28.dp)),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                        val minH = maxHeight
                        Column(
                            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = minH),
                            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CheckInPanel(store, Modifier.fillMaxWidth()) { checkInOpen = false }
                        }
                    }
                }
            }
        }
    }
}

/** Where the floating bar is on screen, so the chat bubble can sit next to it. */
private val barBounds = mutableStateOf<java.awt.Rectangle?>(null)

/**
 * The add-on chat, coming out of the floating companion: beside it (on the side with more room) in pet mode,
 * with the bubble tails pointing at it; under or over the extended bar. Transparent around the bubbles.
 */
@Composable
private fun BubbleWindow(store: Store, anchor: java.awt.Rectangle, petMode: Boolean) {
    val width = 360
    val height = 330
    val window = remember {
        ComposeWindow().apply {
            type = java.awt.Window.Type.UTILITY
            isUndecorated = true
            isTransparent = true
            isAlwaysOnTop = true
            focusableWindowState = false
            background = java.awt.Color(0, 0, 0, 0)
            title = "Fifteen chat"
        }
    }
    val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    val onLeft: Boolean
    val x: Int
    val y: Int
    val align: Alignment
    if (petMode) {
        // The companion stands centred in the bottom of its window; its head is about 110 px from the top.
        onLeft = anchor.x + anchor.width / 2 > screen.x + screen.width / 2
        x = if (onLeft) anchor.x + 44 - width else anchor.x + anchor.width - 44
        val wanted = anchor.y + 130 - height
        y = wanted.coerceIn(screen.y, screen.y + screen.height - height)
        align = when {
            y > wanted -> if (onLeft) Alignment.TopEnd else Alignment.TopStart
            else -> if (onLeft) Alignment.BottomEnd else Alignment.BottomStart
        }
    } else {
        onLeft = false
        val below = anchor.y < screen.y + screen.height / 2
        x = (anchor.x + 24).coerceIn(screen.x, screen.x + screen.width - width)
        y = if (below) anchor.y + anchor.height - 2 else anchor.y - height + 2
        align = if (below) Alignment.TopStart else Alignment.BottomStart
    }
    Window(
        create = { window },
        dispose = { it.dispose() },
        update = { w ->
            if (w.x != x || w.y != y || w.width != width || w.height != height) w.setBounds(x, y, width, height)
            if (!w.isVisible) w.isVisible = true
        },
    ) {
        FifteenTheme {
            Box(Modifier.fillMaxSize().padding(6.dp), contentAlignment = align) {
                NudgeBubble(store, showAvatar = false, maxWidth = 344.dp, mirrored = onLeft)
            }
        }
    }
}

/** The right-click menu on the floating companion: a small dark card that closes when you click elsewhere. */
@Composable
private fun PetMenu(at: java.awt.Point, onDismiss: () -> Unit, items: List<Pair<String, () -> Unit>>) {
    val width = 236
    val height = 16 + items.size * 38
    val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    val x = at.x.coerceIn(screen.x, screen.x + screen.width - width)
    val y = (if (at.y + height > screen.y + screen.height) at.y - height else at.y).coerceAtLeast(screen.y)
    val state = rememberWindowState(size = DpSize(width.dp, height.dp), position = WindowPosition(x.dp, y.dp))
    Window(
        onCloseRequest = onDismiss, title = "Fifteen menu", state = state,
        undecorated = true, transparent = true, alwaysOnTop = true, resizable = false,
        onPreviewKeyEvent = { if (it.key == Key.Escape) { onDismiss(); true } else false },
    ) {
        LaunchedEffect(Unit) {
            window.toFront(); window.requestFocus()
            window.addWindowFocusListener(object : java.awt.event.WindowFocusListener {
                override fun windowGainedFocus(e: java.awt.event.WindowEvent?) {}
                override fun windowLostFocus(e: java.awt.event.WindowEvent?) { onDismiss() }
            })
        }
        FifteenTheme {
            Column(
                Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(Color(0xFF16181E))
                    .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(14.dp)).padding(vertical = 8.dp),
            ) {
                for ((label, action) in items) {
                    val danger = label == "Quit" || label.startsWith("Stop")
                    Box(
                        Modifier.fillMaxWidth().height(38.dp).clickable { onDismiss(); action() }.padding(horizontal = 16.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(label, color = if (danger) Ink.coral else Ink.text, fontSize = 14.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * The always-on-top companion window. Two shapes: the full bar (companion + calm bar) and pet mode
 * (a bigger companion standing on its own with a tiny timer). Double-click switches; a single click on the
 * companion makes it react; drag anywhere to move it.
 */
@Composable
private fun FloatingBar(store: Store, v: View, prefs: LocalPrefs, onOpen: () -> Unit, onMenu: (java.awt.Point) -> Unit) {
    val petMode = prefs.showPet && prefs.barMode == "pet"
    val barW = 460
    val petW = if (prefs.showPet) 104 else 0
    val (width, h) = when {
        petMode -> 210 to 250
        prefs.showPet -> (barW + petW) to 104
        else -> barW to 46
    }
    var poke by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var pendingOpen by remember { mutableStateOf<Job?>(null) }
    // A little hop when it starts talking.
    val talking = v.db.nudge.active?.id
    LaunchedEffect(talking) { if (talking != null && store.nudges.visible(v.db, prefs)) poke++ }
    val window = remember {
        ComposeWindow().apply {
            type = java.awt.Window.Type.UTILITY
            isUndecorated = true
            isTransparent = true
            isAlwaysOnTop = true
            focusableWindowState = false
            background = java.awt.Color(0, 0, 0, 0)
            title = "Fifteen pet"
        }
    }
    Window(
        create = { window },
        dispose = { it.dispose() },
        update = { w ->
            if (w.width != width || w.height != h) {
                if (w.isVisible && w.width > 0) {
                    // Switching shape: keep the bottom-left corner (where the companion stands) in place.
                    val bottom = w.y + w.height
                    w.setBounds(w.x, bottom - h, width, h)
                    barBounds.value = w.bounds
                } else {
                    val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                    val x = if (prefs.barX >= 0) prefs.barX else screen.x + (screen.width - width) / 2
                    val y = if (prefs.barY >= 0) prefs.barY else screen.y + 6
                    w.setBounds(x, y, width, h)
                    barBounds.value = w.bounds
                }
            }
            if (!w.isVisible) w.isVisible = true
        },
    ) {
        FifteenTheme {
            // Callbacks read the latest state but stay stable, so the gesture detector never restarts mid-drag.
            val density = androidx.compose.ui.platform.LocalDensity.current.density
            val onClickLatest = rememberUpdatedState<(Float) -> Unit> { x ->
                    if (petMode || x <= petW * density) poke++
                    else {
                        pendingOpen?.cancel()
                        pendingOpen = scope.launch { delay(330); onOpen() }
                    }
            }
            val onDoubleLatest = rememberUpdatedState {
                pendingOpen?.cancel()
                if (prefs.showPet) store.updatePrefs { it.copy(barMode = if (it.barMode == "pet") "bar" else "pet") }
            }
            val onMenuLatest = rememberUpdatedState(onMenu)
            val drag = Modifier.fillMaxSize().moveWindowOnDrag(
                window,
                onClick = { x -> onClickLatest.value(x) },
                onDoubleClick = { onDoubleLatest.value() },
                onRightClick = { p -> onMenuLatest.value(p) },
                onMoved = { x, y -> store.updatePrefs { it.copy(barX = x, barY = y) }; barBounds.value = window.bounds },
            )
            if (petMode) {
                Column(drag, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    PetView(v.db.profile.avatar, v.petState(), Modifier.size(196.dp), interactive = false, poke = poke)
                    MiniTimer(v)
                    Spacer(Modifier.height(6.dp))
                }
            } else {
                Row(drag, verticalAlignment = Alignment.Bottom) {
                    if (prefs.showPet) {
                        PetView(v.db.profile.avatar, v.petState(), Modifier.size(100.dp), interactive = false, poke = poke)
                        Spacer(Modifier.width(4.dp))
                    }
                    Box(Modifier.width(barW.dp).padding(bottom = 4.dp, end = 4.dp)) {
                        FocusBar(v, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

/**
 * Drag moves the window (screen coordinates, so no jitter). A press without movement is a click; two clicks
 * within 350 ms are a double-click.
 */
private fun Modifier.moveWindowOnDrag(
    window: ComposeWindow,
    onClick: (Float) -> Unit,
    onDoubleClick: () -> Unit,
    onRightClick: (java.awt.Point) -> Unit,
    onMoved: (Int, Int) -> Unit,
) = pointerInput(Unit) {
    var lastClick = 0L
    awaitPointerEventScope {
        while (true) {
            val down = awaitPointerEvent(PointerEventPass.Initial)
            if (down.changes.none { it.pressed }) continue
            if (down.buttons.isSecondaryPressed) {
                while (awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed }) Unit
                onRightClick(MouseInfo.getPointerInfo().location)
                continue
            }
            val startMouse = MouseInfo.getPointerInfo().location
            val startWin = window.location
            var moved = false
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                val p = MouseInfo.getPointerInfo().location
                val dx = p.x - startMouse.x; val dy = p.y - startMouse.y
                if (abs(dx) + abs(dy) > 4) moved = true
                if (moved) window.setLocation(startWin.x + dx, startWin.y + dy)
                if (e.changes.none { it.pressed }) break
            }
            if (moved) {
                onMoved(window.x, window.y); lastClick = 0
            } else {
                val now = System.currentTimeMillis()
                if (now - lastClick < 350) { lastClick = 0; onDoubleClick() }
                else { lastClick = now; onClick(down.changes.first().position.x) }
            }
        }
    }
}

@Composable
private fun DesktopSettings(store: Store) {
    val prefs by store.prefs.collectAsState()
    SectionTitle("On this computer")
    Toggle(
        "Floating pet", prefs.showBar && !prefs.petHidden,
        "Your companion floats over your windows with a tiny timer. Click it to play, double-click for the extended bar, right-click to hide it or quit. Add-ons talk through it.",
    ) { on -> if (on) store.showPet() else store.updatePrefs { it.copy(showBar = false) } }
    Toggle("Extended bar", prefs.barMode == "bar", "The companion with a wide bar showing what you're on and the time left. Double-click the pet to switch.") { on ->
        store.updatePrefs { it.copy(barMode = if (on) "bar" else "pet", showPet = true) }
    }
    Toggle("Ask what's first at startup", prefs.askOnStart) { on -> store.updatePrefs { it.copy(askOnStart = on) } }
    Toggle(
        "Start with Windows", prefs.autostart,
        if (Autostart.available) "Starts quietly in the tray when you sign in." else "Available in the installed app (MSI).",
    ) { on -> store.updatePrefs { it.copy(autostart = on) }; Autostart.set(on) }
    Toggle("Reset pet position", false, "Moves the pet back to the top centre.") { store.updatePrefs { it.copy(barX = -1, barY = -1) } }
}

/** Tray / window icon: a ring that fills as the block runs. */
private class RingPainter(val progress: Float, val color: Color, val phase: Phase) : Painter() {
    override val intrinsicSize = Size(64f, 64f)
    override fun DrawScope.onDraw() {
        val s = size.minDimension
        val stroke = s * 0.16f
        val tl = Offset(stroke / 2, stroke / 2)
        val sz = Size(s - stroke, s - stroke)
        drawCircle(Color(0xFF16181E), radius = s / 2)
        drawArc(Color(0x55FFFFFF), 0f, 360f, false, tl, sz, style = Stroke(stroke))
        drawArc(if (phase == Phase.Due) Ink.mellow else color, -90f, 360f * progress, false, tl, sz, style = Stroke(stroke))
        if (phase == Phase.Idle) drawCircle(Color(0xFF9A9CA5), radius = s * 0.12f)
    }
}

/** %APPDATA%\Fifteen. The app used to be called Nimisham: its files move over once, on first start. */
private fun dataDir(): File {
    val base = File(System.getenv("APPDATA") ?: System.getProperty("user.home"))
    val dir = File(base, "Fifteen")
    val old = File(base, "Nimisham")
    if (!File(dir, "db.json").exists() && File(old, "db.json").exists()) {
        dir.mkdirs()
        old.listFiles()?.filter { it.isFile }?.forEach { runCatching { it.copyTo(File(dir, it.name), overwrite = true) } }
    }
    return dir
}

/** Windows sign-in startup via the per-user Run key (only for the installed app). */
object Autostart {
    private const val KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private val exe: String? by lazy {
        System.getProperty("jpackage.app-path")
            ?: ProcessHandle.current().info().command().orElse(null)?.takeIf { it.endsWith("Fifteen.exe", ignoreCase = true) }
    }
    val available get() = exe != null && System.getProperty("os.name").startsWith("Windows")

    fun set(enabled: Boolean) {
        if (!available) return
        // The old name's entry would start a copy that no longer exists.
        run(listOf("reg", "delete", KEY, "/v", "Nimisham", "/f"))
        run(
            if (enabled) listOf("reg", "add", KEY, "/v", "Fifteen", "/t", "REG_SZ", "/d", "\"$exe\" --minimized", "/f")
            else listOf("reg", "delete", KEY, "/v", "Fifteen", "/f"),
        )
    }

    private fun run(cmd: List<String>) {
        runCatching { ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor() }
    }
}
