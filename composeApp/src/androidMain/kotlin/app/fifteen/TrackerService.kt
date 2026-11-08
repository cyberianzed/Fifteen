package app.fifteen

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.RemoteViews
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Keeps Fifteen alive: the permanent timer notification, the block-end alarm, the overlay bar,
 * the widget and the sync loop all live here.
 */
class TrackerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val store get() = FifteenApplication.store(this)
    private var overlay: OverlayBar? = null
    private var petOverlay: PetOverlay? = null
    private var syncJob: Job? = null
    private var lastAlarmFor: String? = null
    private var lastDueNotified: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        startInForeground()
        // Turned off from the notification or Settings: don't come back until the app is opened.
        if (store.prefs.value.serviceOff) { stopSelf(); return }
        overlay = OverlayBar(this)
        scope.launch { store.db.combine(store.prefs) { a, b -> a to b }.collect { refresh() } }
        scope.launch {
            while (true) {
                delay(10_000)
                refresh()
            }
        }
        petOverlay = PetOverlay(this, store)
        scope.launch(Dispatchers.Default) { store.nudges.loop() }
        syncJob = scope.launch(Dispatchers.Default) {
            val pm = getSystemService(PowerManager::class.java)
            store.syncLoop { if (pm.isInteractive) 8_000 else 60_000 }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (store.prefs.value.serviceOff) { stopSelf(); return START_NOT_STICKY }
        refresh()
        return START_STICKY
    }

    private fun startInForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF_ID, n)
    }

    private fun refresh() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification())
        val phase = store.phase()
        val live = store.liveBlock()
        // Block-end alarm (exact, survives Doze) for the live block.
        if (live != null && phase == Phase.Running && lastAlarmFor != "${live.id}:${live.minutes}") {
            scheduleAlarm(live.endAt)
            lastAlarmFor = "${live.id}:${live.minutes}"
        }
        if (phase == Phase.Due && live != null && lastDueNotified != live.id) {
            lastDueNotified = live.id
            nm.notify(DUE_ID, buildDueNotification(live))
        }
        if (phase != Phase.Due) nm.cancel(DUE_ID)
        overlay?.update(store)
        petOverlay?.update()
        NudgeNotifier.update(this, store)
        FifteenWidget.updateAll(this)
    }

    @SuppressLint("ScheduleExactAlarm")
    private fun scheduleAlarm(at: Long) {
        val am = getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            this, 7, Intent(this, ActionReceiver::class.java).setAction(ActionReceiver.ACTION_ALARM),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    private fun action(act: String, req: Int, rating: Int? = null): PendingIntent =
        PendingIntent.getBroadcast(
            this, req, Intent(this, ActionReceiver::class.java).setAction(act).apply { rating?.let { putExtra("rating", it) } },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openCheckInIntent(): PendingIntent = PendingIntent.getActivity(
        this, 2, Intent(this, CheckInActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun buildNotification(): Notification {
        val prefs = store.prefs.value
        val now = nowMs()
        val phase = store.phase(now)
        val live = store.liveBlock()
        val cat = store.category(live?.categoryId)
        val b = Notification.Builder(this, if (prefs.richNotification) CH_TIMER else CH_QUIET)
            .setSmallIcon(R.drawable.ic_stat)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (!prefs.richNotification) {
            return b.setContentTitle("Fifteen is keeping time").build()
        }
        cat?.let { b.setColor(it.color.toInt()).setColorized(false) }
        when (phase) {
            Phase.Running -> {
                live!!
                val pct = ((now - live.startAt) * 1000 / (live.minutes * 60_000L)).toInt().coerceIn(0, 1000)
                val (q, _) = quoteFor(live.id)
                b.setContentTitle(live.intent.ifBlank { cat?.name ?: "Focus" })
                    .setContentText(if (store.db.value.profile.quotes) q else "${live.minutes}-minute block")
                    .setSubText(cat?.name)
                    .setUsesChronometer(true)
                    .setChronometerCountDown(true)
                    .setWhen(live.endAt)
                    .setShowWhen(true)
                    .setProgress(1000, pct, false)
                    .addAction(Notification.Action.Builder(null, "Switch", openCheckInIntent()).build())
                    .addAction(Notification.Action.Builder(null, "+5 min", action(ActionReceiver.ACTION_EXTEND, 20)).build())
                    .addAction(Notification.Action.Builder(null, "Stop", action(ActionReceiver.ACTION_STOP, 21)).build())
            }
            Phase.Due -> {
                b.setContentTitle("Block done — how did it go?")
                    .setContentText("Tap a rating to log it and keep going with ${cat?.name ?: "the same thing"}.")
                    .addAction(Notification.Action.Builder(null, "100%", action(ActionReceiver.ACTION_RATE, 30, 100)).build())
                    .addAction(Notification.Action.Builder(null, "75%", action(ActionReceiver.ACTION_RATE, 31, 75)).build())
                    .addAction(Notification.Action.Builder(null, "Drifted", action(ActionReceiver.ACTION_RATE, 32, 0)).build())
            }
            Phase.Idle -> {
                val pts = store.dayPoints(epochDay(now))
                b.setContentTitle("Resting · $pts points today")
                    .setContentText("Tap Start when you're ready for the next block.")
                    .addAction(Notification.Action.Builder(null, "Start", action(ActionReceiver.ACTION_RESUME, 40)).build())
                    .addAction(Notification.Action.Builder(null, "Choose…", openCheckInIntent()).build())
                    .addAction(Notification.Action.Builder(null, "Quit", action(ActionReceiver.ACTION_OFF, 41)).build())
            }
        }
        return b.build()
    }

    /** Heads-up nudge when a block ends (separate, so it can make a sound once). */
    private fun buildDueNotification(live: Block): Notification {
        val cat = store.category(live.categoryId)
        return Notification.Builder(this, CH_CHECKIN)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Block done · ${live.intent.ifBlank { cat?.name ?: "" }}")
            .setContentText("How focused were you? Tap to check in.")
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(openCheckInIntent())
            .setFullScreenIntent(openCheckInIntent(), true)
            .addAction(Notification.Action.Builder(null, "100%", action(ActionReceiver.ACTION_RATE, 50, 100)).build())
            .addAction(Notification.Action.Builder(null, "75%", action(ActionReceiver.ACTION_RATE, 51, 75)).build())
            .addAction(Notification.Action.Builder(null, "Drifted", action(ActionReceiver.ACTION_RATE, 52, 0)).build())
            .build()
    }

    override fun onDestroy() {
        overlay?.remove()
        petOverlay?.remove()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val NOTIF_ID = 15
        const val DUE_ID = 16
        const val CH_TIMER = "timer"
        const val CH_QUIET = "quiet"
        const val CH_CHECKIN = "checkin"

        fun createChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_TIMER, "Timer", NotificationManager.IMPORTANCE_LOW).apply {
                description = "The live countdown for the current block"; setShowBadge(false)
            })
            nm.createNotificationChannel(NotificationChannel(CH_QUIET, "Background (minimal)", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
            nm.createNotificationChannel(NotificationChannel(NudgeNotifier.CHANNEL, "Add-ons (eye breaks, water, check-ins)", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Little chats from your companion"; setSound(null, null); enableVibration(false)
            })
            nm.createNotificationChannel(NotificationChannel(CH_CHECKIN, "Check-ins", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Asks how a block went when it ends"; enableVibration(true)
            })
        }
    }
}

/** The floating bar drawn over other apps: a thin line at the very top, or a small draggable pill. */
class OverlayBar(private val ctx: Context) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var view: BarView? = null
    private var style: String? = null

    fun update(store: Store) {
        val prefs = store.prefs.value
        val show = prefs.overlay && prefs.overlayStyle != "pet" && Settings.canDrawOverlays(ctx) && store.phase() != Phase.Idle
        if (!show) { remove(); return }
        if (view == null || style != prefs.overlayStyle) {
            remove()
            add(prefs.overlayStyle)
        }
        val live = store.liveBlock()
        val now = nowMs()
        view?.apply {
            progress = live?.let { ((now - it.startAt).toFloat() / (it.minutes * 60_000f)).coerceIn(0f, 1f) } ?: 1f
            color = store.category(live?.categoryId)?.color?.toInt() ?: 0xFFF2C14E.toInt()
            due = store.phase(now) == Phase.Due
            label = if (due) "check in" else live?.let { "${((it.endAt - now) / 60_000L + 1).coerceAtLeast(0)}m" } ?: ""
            invalidate()
        }
    }

    private fun add(s: String) {
        val v = BarView(ctx, s)
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val d = ctx.resources.displayMetrics.density
        val lp = if (s == "pill") {
            WindowManager.LayoutParams(
                (92 * d).toInt(), (30 * d).toInt(), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.START; x = (16 * d).toInt(); y = (60 * d).toInt() }
        } else {
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, (3 * d).toInt(), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP
                alpha = 0.8f // Android 12+ lets touches pass through untouchable overlays at ≤ 0.8 opacity
                if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (s == "pill") v.attachDrag(wm, lp) { openCheckIn(ctx) }
        runCatching { wm.addView(v, lp); view = v; style = s }
    }

    fun remove() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }
}

@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class BarView(ctx: Context, private val style: String) : View(ctx) {
    var progress = 0f
    var color = 0xFFF2C14E.toInt()
    var due = false
    var label = ""
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = ctx.resources.displayMetrics.density

    fun attachDrag(wm: WindowManager, lp: WindowManager.LayoutParams, onTap: () -> Unit) {
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx; val dy = e.rawY - sy
                    if (abs(dx) + abs(dy) > 10) moved = true
                    if (moved) { lp.x = ox + dx.toInt(); lp.y = oy + dy.toInt(); runCatching { wm.updateViewLayout(this, lp) } }
                }
                MotionEvent.ACTION_UP -> if (!moved) onTap()
            }
            true
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (style == "line") {
            paint.color = 0x33FFFFFF
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.color = if (due) 0xFFF2C14E.toInt() else color
            canvas.drawRect(0f, 0f, w * progress, h, paint)
            return
        }
        val r = RectF(0f, 0f, w, h)
        paint.color = 0xE6121318.toInt()
        canvas.drawRoundRect(r, h / 2, h / 2, paint)
        paint.color = if (due) 0xFFF2C14E.toInt() else color
        canvas.drawCircle(h / 2, h / 2, 4 * d, paint)
        paint.color = 0xFFF3F1EA.toInt()
        paint.textSize = 12 * d
        canvas.drawText(label, h, h / 2 + 4 * d, paint)
        paint.color = 0x33FFFFFF
        val left = h / 2; val right = w - h / 2; val y = h - 5 * d
        canvas.drawRoundRect(RectF(left, y, right, y + 2 * d), d, d, paint)
        paint.color = if (due) 0xFFF2C14E.toInt() else color
        canvas.drawRoundRect(RectF(left, y, left + (right - left) * progress, y + 2 * d), d, d, paint)
    }
}

/** Home-screen widget: today's companion (drawn by the shared code) + the live countdown. */
class FifteenWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        updateAll(context)
        runCatching { startTracker(context) }
    }

    companion object {
        private var lastKey = ""
        private var lastBitmap: android.graphics.Bitmap? = null

        fun updateAll(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = manager.getAppWidgetIds(ComponentName(ctx, FifteenWidget::class.java))
            if (ids.isEmpty()) return
            val store = FifteenApplication.store(ctx)
            val now = nowMs()
            val phase = store.phase(now)
            val live = store.liveBlock()
            val cat = store.category(live?.categoryId)
            val today = epochDay(now)
            val pts = store.dayPoints(today)
            val db = store.db.value
            val todays = db.blocks.filter { it.rated && epochDay(it.startAt) == today }
            val pet = PetState(
                growth = growthFor(pts, db.profile.dailyGoal),
                fruits = todays.count { it.rating == 100 },
                asleep = phase == Phase.Idle,
                level = levelFor(store.totalXp()),
            )
            val key = "${db.profile.avatar}:${(pet.growth * 50).toInt()}:${pet.fruits}:${pet.asleep}:${pet.level}"
            val bmp = if (key == lastKey && lastBitmap != null) lastBitmap!! else renderPet(ctx, db.profile.avatar, pet).also { lastKey = key; lastBitmap = it }

            val rv = RemoteViews(ctx.packageName, R.layout.widget)
            rv.setImageViewBitmap(R.id.widget_pet, bmp)
            val open = PendingIntent.getActivity(ctx, 3, Intent(ctx, if (phase == Phase.Running) MainActivity::class.java else CheckInActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            rv.setOnClickPendingIntent(R.id.widget_root, open)
            when (phase) {
                Phase.Running -> {
                    live!!
                    rv.setTextViewText(R.id.widget_title, live.intent.ifBlank { cat?.name ?: "" })
                    val base = SystemClock.elapsedRealtime() + (live.endAt - now)
                    rv.setChronometer(R.id.widget_timer, base, null, true)
                    rv.setChronometerCountDown(R.id.widget_timer, true)
                    rv.setProgressBar(R.id.widget_progress, 1000, ((now - live.startAt) * 1000 / (live.minutes * 60_000L)).toInt(), false)
                    rv.setTextViewText(R.id.widget_sub, "$pts / ${db.profile.dailyGoal} pts today")
                }
                Phase.Due -> {
                    rv.setTextViewText(R.id.widget_title, "Block done")
                    rv.setChronometer(R.id.widget_timer, SystemClock.elapsedRealtime(), "check in", false)
                    rv.setProgressBar(R.id.widget_progress, 1000, 1000, false)
                    rv.setTextViewText(R.id.widget_sub, "Tap to tell your companion how it went")
                }
                Phase.Idle -> {
                    rv.setTextViewText(R.id.widget_title, "Resting")
                    rv.setChronometer(R.id.widget_timer, SystemClock.elapsedRealtime(), "$pts pts", false)
                    rv.setProgressBar(R.id.widget_progress, 1000, (pet.growth.coerceAtMost(1f) * 1000).toInt(), false)
                    rv.setTextViewText(R.id.widget_sub, "Tap to start a block")
                }
            }
            runCatching { manager.updateAppWidget(ids, rv) }
        }

        private fun renderPet(ctx: Context, kind: String, s: PetState): android.graphics.Bitmap {
            val d = ctx.resources.displayMetrics.density
            val px = (96 * d).toInt().coerceAtMost(320)
            val img = ImageBitmap(px, px)
            CanvasDrawScope().draw(Density(d), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(img), Size(px.toFloat(), px.toFloat())) {
                drawPet(kind, s, (nowMs() % 600_000L) / 1000f)
            }
            return img.asAndroidBitmap()
        }
    }
}
