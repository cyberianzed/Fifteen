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
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null

    fun update() {
        val db = store.db.value
        val prefs = store.prefs.value
        val show = store.nudges.visible(db, prefs) && prefs.nudgeOverlay && Settings.canDrawOverlays(ctx) && !AppVisibility.inFront
        if (show && view == null) add() else if (!show && view != null) remove()
    }

    private fun add() {
        val d = ctx.resources.displayMetrics.density
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = (10 * d).toInt(); y = (90 * d).toInt() }
        val o = OverlayOwner().also { it.start() }
        val v = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                NimishamTheme {
                    Box(Modifier.padding(6.dp)) { NudgeBubble(store, maxWidth = 320.dp) }
                }
            }
        }
        runCatching { wm.addView(v, lp); view = v; owner = o }.onFailure { o.stop() }
    }

    fun remove() {
        view?.let { runCatching { wm.removeView(it) } }
        owner?.stop()
        view = null
        owner = null
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
