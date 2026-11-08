package app.nimisham

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
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/** Whether one of our screens is in front (then the in-app chat card shows instead of the overlay). */
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
 * The companion's add-on chat floating over other apps (needs "display over other apps"). It uses the same
 * Compose bubble as the app, so typing dots, buttons and the 20-second timer all work here too.
 */
class NudgeOverlay(private val ctx: Context, private val store: Store) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var owner: OverlayOwner? = null
    private var target: CloseTarget? = null
    private val d = ctx.resources.displayMetrics.density
    // Which edge the bubble sits on and how far down; remembered when you drag it somewhere else.
    private var right = false
    private var posY = (90 * d).toInt()
    private val margin = (6 * d).toInt()
    private val collapsed = androidx.compose.runtime.mutableStateOf(false)
    private val mirrored = androidx.compose.runtime.mutableStateOf(false)

    fun update() {
        val db = store.db.value
        val prefs = store.prefs.value
        val show = store.nudges.visible(db, prefs) && prefs.nudgeOverlay && Settings.canDrawOverlays(ctx) && !AppVisibility.inFront
        if (show && view == null) add() else if (!show && view != null) remove()
    }

    private fun add() {
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or (if (right) Gravity.END else Gravity.START); x = margin; y = posY }
        collapsed.value = false
        mirrored.value = right
        val o = OverlayOwner().also { it.start() }
        val compose = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                NimishamTheme {
                    Box(Modifier.padding(6.dp)) {
                        NudgeBubble(store, maxWidth = 320.dp, mirrored = mirrored.value, collapsed = collapsed.value)
                    }
                }
            }
        }
        lateinit var frame: DragFrame
        frame = DragFrame(
            ctx,
            onStart = { rawX, rawY ->
                // Messages hide; the companion alone follows the finger like a chat head.
                collapsed.value = true
                val head = (64 * d).toInt()
                lp.gravity = Gravity.TOP or Gravity.START
                lp.x = rawX.toInt() - head / 2
                lp.y = rawY.toInt() - head / 2
                runCatching { wm.updateViewLayout(frame, lp) }
                showTarget()
            },
            onMove = { dx, dy ->
                lp.x += dx.toInt(); lp.y += dy.toInt()
                runCatching { wm.updateViewLayout(frame, lp) }
                target?.hot = overTarget(lp, frame)
            },
            onRelease = {
                val close = overTarget(lp, frame)
                hideTarget()
                if (close) {
                    store.nudges.answer("close")
                } else {
                    // Snap to the nearer edge; messages open towards the middle of the screen.
                    val dm = ctx.resources.displayMetrics
                    right = lp.x + frame.width / 2 > dm.widthPixels / 2
                    posY = lp.y.coerceIn((24 * d).toInt(), (dm.heightPixels - 360 * d).toInt())
                    lp.gravity = Gravity.TOP or (if (right) Gravity.END else Gravity.START)
                    lp.x = margin
                    lp.y = posY
                    mirrored.value = right
                    collapsed.value = false
                    runCatching { wm.updateViewLayout(frame, lp) }
                }
            },
        ).apply {
            // Compose looks up the lifecycle from the window's root view, which is this frame.
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            addView(compose)
        }
        runCatching { wm.addView(frame, lp); view = frame; owner = o }.onFailure { o.stop() }
    }

    /** Close when the bubble is dropped on the target (bottom centre) or dragged into the bottom fifth. */
    private fun overTarget(lp: WindowManager.LayoutParams, v: View): Boolean {
        val screenH = ctx.resources.displayMetrics.heightPixels
        val screenW = ctx.resources.displayMetrics.widthPixels
        val cx = lp.x + v.width / 2
        val bottom = lp.y + v.height
        val nearTarget = kotlin.math.abs(cx - screenW / 2) < screenW * 0.35 && bottom > screenH - 170 * d
        return nearTarget || lp.y > screenH * 0.8
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
) : FrameLayout(ctx) {
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

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
                if (dragging) { onMove(e.rawX - lastX, e.rawY - lastY); lastX = e.rawX; lastY = e.rawY }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) { dragging = false; onRelease() }
        }
        return true
    }
}

/** The round × you drop the bubble on; it grows and turns coral when the bubble is over it. */
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
        val name = AVATARS.firstOrNull { it.id == db.profile.avatar }?.name ?: "Nimisham"
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
