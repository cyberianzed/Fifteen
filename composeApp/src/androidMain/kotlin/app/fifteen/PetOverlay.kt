package app.fifteen

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/** Whether one of our screens is in front (then the pet and its chat show inside the app instead). */
object AppVisibility {
    @Volatile var inFront = false
}

/** A minimal lifecycle so a ComposeView can live in a WindowManager overlay. */
private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val controller = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
    fun start() {
        controller.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }
    fun stop() { registry.currentState = Lifecycle.State.DESTROYED }
}

/**
 * Your companion floating over other apps as a small chat head (needs "display over other apps"):
 * a ring around it fills as the block runs. Tap it to play, double-tap to open Fifteen, long-press for the menu
 * (hide, stop, quit), drag it anywhere or onto the × to hide it. Add-on chats come out of it.
 */
class PetOverlay(private val ctx: Context, private val store: Store) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var view: View? = null
    private var owner: OverlayOwner? = null
    private var target: CloseTarget? = null
    private var head: android.widget.ImageView? = null
    private var headAvatar = ""
    private val headLp = WindowManager.LayoutParams()
    private val d = ctx.resources.displayMetrics.density
    private val headSize = (64 * d).toInt()
    // Which edge it sits on and how far down; remembered when you drag it somewhere else.
    private var right = true
    private var posY = (150 * d).toInt()
    private val margin = (4 * d).toInt()
    private val mirrored = androidx.compose.runtime.mutableStateOf(true)
    private val menuOpen = androidx.compose.runtime.mutableStateOf(false)

    fun update() {
        val prefs = store.prefs.value
        val show = store.nudges.petOut(prefs) && !AppVisibility.inFront
        if (show && view == null) add() else if (!show && view != null) remove()
        if (!show) menuOpen.value = false
        if (view != null && headAvatar != store.db.value.profile.avatar) renderHead()
    }

    private fun add() {
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or (if (right) Gravity.END else Gravity.START); x = margin; y = posY }
        mirrored.value = right
        val o = OverlayOwner().also { it.start() }
        val compose = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                FifteenTheme {
                    PetOverlayContent(
                        store, mirrored.value, menuOpen.value,
                        onOpenApp = { openApp() },
                        onMenu = { menuOpen.value = it },
                        menuItems = { menuItems() },
                    )
                }
            }
        }
        lateinit var frame: DragFrame
        frame = DragFrame(
            ctx,
            onStart = { rawX, rawY ->
                // No re-layout while dragging (that stuttered): the live view fades out and a pre-drawn picture
                // of the companion follows the finger instead.
                menuOpen.value = false
                frame.animate().cancel()
                frame.alpha = 0f
                moveHead(rawX, rawY)
                head?.alpha = 1f
                showTarget()
            },
            onMove = { rawX, rawY ->
                moveHead(rawX, rawY)
                target?.hot = overTarget()
            },
            onRelease = {
                val hide = overTarget()
                head?.alpha = 0f
                hideTarget()
                if (hide) {
                    if (store.db.value.nudge.active != null) store.nudges.answer("close")
                    store.hidePet()
                    frame.alpha = 1f
                } else {
                    // Move the (invisible) pet to the nearer edge, then fade it back in there.
                    val dm = ctx.resources.displayMetrics
                    right = headLp.x + headSize / 2 > dm.widthPixels / 2
                    posY = headLp.y.coerceIn((24 * d).toInt(), (dm.heightPixels - 380 * d).toInt())
                    lp.gravity = Gravity.TOP or (if (right) Gravity.END else Gravity.START)
                    lp.x = margin
                    lp.y = posY
                    mirrored.value = right
                    runCatching { wm.updateViewLayout(frame, lp) }
                    frame.postDelayed({ frame.animate().alpha(1f).setDuration(160).start() }, 80)
                }
            },
            onOutside = { menuOpen.value = false },
        ).apply {
            // Compose looks up the lifecycle from the window's root view, which is this frame.
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            addView(compose)
        }
        runCatching { wm.addView(frame, lp); view = frame; owner = o }.onFailure { o.stop() }
        addHead()
    }

    private fun openApp() {
        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun menuItems(): List<Pair<String, () -> Unit>> {
        val phase = store.phase()
        // Run after the menu closes, outside the click (some of these remove this very window).
        fun later(f: () -> Unit): () -> Unit = { menuOpen.value = false; main.post(f) }
        return buildList {
            add(
                when (phase) {
                    Phase.Idle -> "Start a block"
                    Phase.Due -> "Check in"
                    Phase.Running -> "Switch activity"
                } to later { openCheckIn(ctx) },
            )
            add(HIDE_LABEL to later { store.hidePet() })
            add("Open Fifteen" to later { openApp() })
            if (phase == Phase.Idle) add("Quit" to later { turnOff(ctx) })
            else add("Stop this block" to later { store.stopTracking() })
        }
    }

    /** The drag picture: one bitmap in a tiny fixed-size window, added up front so dragging only moves it. */
    private fun addHead() {
        val iv = android.widget.ImageView(ctx).apply { alpha = 0f }
        headLp.apply {
            width = headSize; height = headSize
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
            x = margin; y = posY
        }
        runCatching { wm.addView(iv, headLp); head = iv }
        renderHead()
    }

    private fun renderHead() {
        val iv = head ?: return
        val db = store.db.value
        headAvatar = db.profile.avatar
        val img = androidx.compose.ui.graphics.ImageBitmap(headSize, headSize)
        androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
            androidx.compose.ui.unit.Density(d), androidx.compose.ui.unit.LayoutDirection.Ltr,
            androidx.compose.ui.graphics.Canvas(img), androidx.compose.ui.geometry.Size(headSize.toFloat(), headSize.toFloat()),
        ) {
            drawCircle(androidx.compose.ui.graphics.Color(0xE6121318))
            drawPet(db.profile.avatar, PetState(0.8f, 2, asleep = false, level = levelFor(store.totalXp(db))), 0f)
        }
        iv.setImageBitmap(img.asAndroidBitmap())
    }

    private fun moveHead(rawX: Float, rawY: Float) {
        val h = head ?: return
        headLp.x = rawX.toInt() - headSize / 2
        headLp.y = rawY.toInt() - headSize / 2
        runCatching { wm.updateViewLayout(h, headLp) }
    }

    /** Close when the companion is dropped on the target (bottom centre) or into the bottom fifth. */
    private fun overTarget(): Boolean {
        val dm = ctx.resources.displayMetrics
        val cx = headLp.x + headSize / 2
        val bottom = headLp.y + headSize
        val nearTarget = kotlin.math.abs(cx - dm.widthPixels / 2) < dm.widthPixels * 0.3 && bottom > dm.heightPixels - 170 * d
        return nearTarget || headLp.y > dm.heightPixels * 0.82
    }

    private fun showTarget() {
        if (target != null) return
        val t = CloseTarget(ctx)
        val size = (64 * d).toInt()
        val lp = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = (56 * d).toInt() }
        runCatching { wm.addView(t, lp); target = t }
    }

    private fun hideTarget() {
        target?.let { runCatching { wm.removeView(it) } }
        target = null
    }

    fun remove() {
        hideTarget()
        head?.let { runCatching { wm.removeView(it) } }
        head = null
        view?.let { runCatching { wm.removeView(it) } }
        owner?.stop()
        view = null
        owner = null
    }
}

/**
 * Lets taps reach the bubble's buttons, but takes over once a finger moves past the touch slop, reporting
 * movement in screen coordinates (so moving the window doesn't feed back into the drag).
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
private class DragFrame(
    ctx: Context,
    private val onStart: (Float, Float) -> Unit,
    private val onMove: (Float, Float) -> Unit,
    private val onRelease: () -> Unit,
    private val onOutside: () -> Unit = {},
) : FrameLayout(ctx) {
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) { onOutside(); return false }
        return super.dispatchTouchEvent(e)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; dragging = false }
            MotionEvent.ACTION_MOVE -> if (!dragging && kotlin.math.hypot(e.rawX - downX, e.rawY - downY) > slop) {
                dragging = true; lastX = e.rawX; lastY = e.rawY; onStart(e.rawX, e.rawY)
            }
        }
        return dragging
    }

    // Also reached directly when the press lands on plain text (no button took the touch).
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; dragging = false }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && kotlin.math.hypot(e.rawX - downX, e.rawY - downY) > slop) {
                    dragging = true; lastX = e.rawX; lastY = e.rawY; onStart(e.rawX, e.rawY)
                }
                if (dragging) onMove(e.rawX, e.rawY)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) { dragging = false; onRelease() }
        }
        return true
    }
}

/** The round × you drop the pet on to hide it; it grows and turns coral when the bubble is over it. */
@SuppressLint("ViewConstructor")
private class CloseTarget(ctx: Context) : View(ctx) {
    var hot = false
        set(value) { if (field != value) { field = value; invalidate() } }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val r = width / 2f * (if (hot) 1f else 0.8f)
        val cx = width / 2f; val cy = height / 2f
        paint.style = Paint.Style.FILL
        paint.color = if (hot) 0xFFE5735C.toInt() else 0xCC121318.toInt()
        canvas.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.12f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = 0xFFF3F1EA.toInt()
        val k = r * 0.32f
        canvas.drawLine(cx - k, cy - k, cx + k, cy + k, paint)
        canvas.drawLine(cx + k, cy - k, cx - k, cy + k, paint)
    }
}

/** The add-on chat as a notification with the answer buttons (works with the screen locked too). */
object NudgeNotifier {
    const val ID = 17
    const val CHANNEL = "nudges"
    private var lastKey = ""

    fun update(ctx: Context, store: Store) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val db = store.db.value
        val a = db.nudge.active
        if (a == null || !store.nudges.visible(db, store.prefs.value)) {
            if (lastKey.isNotEmpty()) { nm.cancel(ID); lastKey = "" }
            return
        }
        val key = "${a.id}:${a.stage}"
        if (key == lastKey) return
        lastKey = key
        val step = store.nudges.step(a, db.nudge, db.profile)
        val name = AVATARS.firstOrNull { it.id == db.profile.avatar }?.name ?: "Fifteen"
        val open = PendingIntent.getActivity(ctx, 9, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(name)
            .setContentText(step.messages.last())
            .setStyle(Notification.BigTextStyle().bigText(step.messages.joinToString("\n")))
            .setContentIntent(open)
            .setAutoCancel(step.buttons.isEmpty())
            .setCategory(Notification.CATEGORY_REMINDER)
        if (step.timerSec > 0 && a.timerEnd > 0) b.setUsesChronometer(true).setChronometerCountDown(true).setWhen(a.timerEnd).setShowWhen(true)
        step.buttons.forEachIndexed { i, (label, action) ->
            val pi = PendingIntent.getBroadcast(
                ctx, 60 + i, Intent(ctx, ActionReceiver::class.java).setAction(ActionReceiver.ACTION_NUDGE).putExtra("nudge_action", action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            b.addAction(Notification.Action.Builder(null, label, pi).build())
        }
        runCatching { nm.notify(ID, b.build()) }
    }
}

/** Turns the background service off until the app is opened again (or the phone restarts). */
fun turnOff(ctx: Context) {
    val store = FifteenApplication.store(ctx)
    store.updatePrefs { it.copy(serviceOff = true) }
    store.flush()
    ctx.stopService(Intent(ctx, TrackerService::class.java))
}

/** The floating pet: the companion in a ring, plus the add-on chat or the long-press menu beside it. */
@androidx.compose.runtime.Composable
private fun PetOverlayContent(
    store: Store,
    mirrored: Boolean,
    menuOpen: Boolean,
    onOpenApp: () -> Unit,
    onMenu: (Boolean) -> Unit,
    menuItems: () -> List<Pair<String, () -> Unit>>,
) {
    val v = rememberView(store)
    val prefs by store.prefs.collectAsState()
    val talking = store.nudges.visible(v.db, prefs)
    var poke by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // A little hop when it starts talking.
    androidx.compose.runtime.LaunchedEffect(v.db.nudge.active?.id) { if (v.db.nudge.active != null) poke++ }
    val tap = androidx.compose.runtime.rememberUpdatedState { poke++ }
    val double = androidx.compose.runtime.rememberUpdatedState(onOpenApp)
    val long = androidx.compose.runtime.rememberUpdatedState { onMenu(true) }

    val headView: @androidx.compose.runtime.Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(68.dp).pointerInput(Unit) {
                    detectTapGestures(onTap = { tap.value() }, onDoubleTap = { double.value() }, onLongPress = { long.value() })
                },
                contentAlignment = Alignment.Center,
            ) {
                val color = v.category?.color?.asColor() ?: Ink.mellow
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 3.dp.toPx()
                    drawCircle(Color(0xE6121318), radius = size.minDimension / 2)
                    val inset = stroke / 2 + 1.dp.toPx()
                    val tl = androidx.compose.ui.geometry.Offset(inset, inset)
                    val sz = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2)
                    drawArc(Color(0x26FFFFFF), 0f, 360f, false, tl, sz, style = Stroke(stroke))
                    val sweep = when (v.phase) { Phase.Running -> 360f * v.progress; Phase.Due -> 360f; Phase.Idle -> 0f }
                    drawArc(if (v.phase == Phase.Due) Ink.mellow else color, -90f, sweep, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                PetView(v.db.profile.avatar, v.petState(), Modifier.size(58.dp), interactive = false, poke = poke)
            }
            if (v.phase != Phase.Idle) {
                Spacer(Modifier.height(3.dp))
                Text(
                    if (v.phase == Phase.Due) "check in" else "${(v.remaining / 60_000L + 1).coerceAtLeast(0)}m",
                    color = if (v.phase == Phase.Due) Ink.mellow else Ink.text, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xE6121318)).padding(horizontal = 7.dp, vertical = 1.dp),
                )
            }
        }
    }
    val side: @androidx.compose.runtime.Composable () -> Unit = {
        when {
            menuOpen -> Column(
                Modifier.width(220.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xF216181E))
                    .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(16.dp)).padding(vertical = 6.dp),
            ) {
                for ((label, action) in menuItems() + ("Close" to { onMenu(false) })) {
                    val danger = label == "Quit" || label.startsWith("Stop")
                    Text(
                        label, color = if (danger) Ink.coral else if (label == "Close") Ink.muted else Ink.text, fontSize = 15.sp,
                        modifier = Modifier.fillMaxWidth().clickable { action() }.padding(horizontal = 16.dp, vertical = 11.dp),
                    )
                }
            }
            talking -> NudgeBubble(store, showAvatar = false, maxWidth = 270.dp, mirrored = mirrored)
        }
    }
    Row(Modifier.padding(6.dp), verticalAlignment = Alignment.Top) {
        if (mirrored) {
            side(); Spacer(Modifier.width(6.dp)); headView()
        } else {
            headView(); Spacer(Modifier.width(6.dp)); side()
        }
    }
}
