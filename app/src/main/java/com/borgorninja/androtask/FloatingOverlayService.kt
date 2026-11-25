package com.borgorninja.androtask

import android.app.*
import android.text.InputType
import android.widget.EditText
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.*
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.borgorninja.androtask.data.MacroStep
import com.borgorninja.androtask.data.StepType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs
import kotlin.math.hypot

class FloatingOverlayService : Service() {

    private lateinit var wm: WindowManager
    private var bubbleView:      View? = null
    private var menuView:        View? = null
    private var menuRecordButton: Button? = null
    private var overlayView:     View? = null
    private var coordPickerView: View? = null
    private var isRecording = false
    private val stepMarkerViews = linkedMapOf<Int, View>()
    private var pendingSwipeStart: Pair<Float, Float>? = null

    // Touch tracking
    private var downRawX  = 0f; private var downRawY  = 0f
    private var lastRawX  = 0f; private var lastRawY  = 0f   // updated on every MOVE
    private var downTime  = 0L
    private var totalPathLen = 0f   // accumulated distance across all MOVE events

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppLogger.i("Overlay", "Service created")
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        val notif = buildNotif()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIF_ID, notif)
        if (Settings.canDrawOverlays(this)) showBubble()
        else AppLogger.w("Overlay", "Draw-over-apps permission not granted — bubble not shown")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Settings.canDrawOverlays(this) && bubbleView == null) showBubble()
        when (intent?.getStringExtra(CMD)) {
            CMD_START       -> startRecording()
            CMD_STOP        -> stopRecordingAndHide()
            CMD_TOGGLE      -> if (isRecording) stopRecordingAndHide() else startRecording()
            CMD_DISMISS     -> { AppLogger.i("Overlay", "Dismissed by user"); stopRecordingAndHide(); stopSelf() }
            CMD_PICK_START  -> showCoordPicker(intent.getStringExtra(EXTRA_PICK_FIELD) ?: FIELD_XY)
            CMD_PICK_CANCEL -> hideCoordPicker()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        MacroAccessibilityService.isRecording = false
        removeBubble(); hideMenu(); removeOverlay(); hideCoordPicker(); removeStepMarkers()
        AppLogger.i("Overlay", "Service destroyed")
        super.onDestroy()
    }

    // ── Notification ──────────────────────────────────────────────────────────
    private fun buildNotif(): Notification {
        val togglePi  = pi(0, CMD_TOGGLE)
        val dismissPi = pi(1, CMD_DISMISS)
        val builder   = NotificationCompat.Builder(this, MainActivity.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(if (isRecording) "Recording…" else "AndroTask ready")
            .setContentText(
                if (isRecording)
                    "Tap bubble to stop  ·  ${MacroAccessibilityService.recordedSteps.size} steps"
                else "Tap bubble to start recording"
            )
            .setContentIntent(togglePi)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "Dismiss bubble", dismissPi)
        if (isRecording)
            builder.addAction(android.R.drawable.ic_media_pause, "Stop recording", pi(2, CMD_STOP))
        return builder.build()
    }

    private fun pi(req: Int, cmd: String) = PendingIntent.getService(
        this, req,
        Intent(this, FloatingOverlayService::class.java).putExtra(CMD, cmd),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun refreshNotif() =
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotif())

    // ── Bubble ────────────────────────────────────────────────────────────────
    private fun showBubble() {
        if (bubbleView != null || !Settings.canDrawOverlays(this)) return
        val px = (56 * resources.displayMetrics.density).toInt()
        val lp = baseLp(px, px).apply { gravity = Gravity.TOP or Gravity.START; x = 16; y = 320 }
        val v  = BubbleView(this)
        setupDrag(v, lp) { toggleMenu() }
        try {
            wm.addView(v, lp)
            bubbleView = v
            AppLogger.i("Overlay", "Floating bubble displayed")
        } catch (e: Exception) {
            AppLogger.e("Overlay", "Could not display bubble: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun removeBubble() {
        hideMenu()
        bubbleView?.let { runCatching { wm.removeView(it) }; bubbleView = null }
    }

    private fun bringBubbleToFront() {
        val old = bubbleView ?: return
        val oldLp = old.layoutParams as WindowManager.LayoutParams
        runCatching { wm.removeView(old) }; bubbleView = null
        val px = (56 * resources.displayMetrics.density).toInt()
        val lp = baseLp(px, px).apply { gravity = oldLp.gravity; x = oldLp.x; y = oldLp.y }
        val v  = BubbleView(this)
        setupDrag(v, lp) { toggleMenu() }
        wm.addView(v, lp); bubbleView = v
    }


    // ── Floating control menu ─────────────────────────────────────────────────
    private fun toggleMenu() {
        if (menuView == null) showMenu() else hideMenu()
    }

    private fun showMenu() {
        if (menuView != null || !Settings.canDrawOverlays(this)) return
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.argb(245, 28, 30, 34))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.argb(180, 255, 255, 255))
            }
        }
        val title = TextView(this).apply {
            text = "Realtime Touch Imitation"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(6), dp(4), dp(6), dp(8))
        }
        panel.addView(title)
        menuRecordButton = Button(this).apply {
            isAllCaps = false
            text = if (isRecording) "Dừng ghi" else "Bắt đầu ghi"
            setOnClickListener {
                if (isRecording) stopRecordingAndHide() else startRecording()
                refreshMenu()
            }
        }
        panel.addView(menuRecordButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val addTap = Button(this).apply {
            isAllCaps = false
            text = "+ Thêm điểm chạm"
            setOnClickListener { showCoordPicker(FIELD_ADD_TAP) }
        }
        panel.addView(addTap, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val addSwipe = Button(this).apply {
            isAllCaps = false
            text = "+ Thêm đường vuốt"
            setOnClickListener { showCoordPicker(FIELD_ADD_SWIPE_START) }
        }
        panel.addView(addSwipe, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val showMarkers = Button(this).apply {
            isAllCaps = false
            text = "Hiện dấu thao tác"
            setOnClickListener { hideMenu(); showStepMarkers() }
        }
        panel.addView(showMarkers, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val openApp = Button(this).apply {
            isAllCaps = false
            text = "Mở ứng dụng"
            setOnClickListener {
                hideMenu()
                startActivity(Intent(this@FloatingOverlayService, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
        }
        panel.addView(openApp, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        val close = Button(this).apply {
            isAllCaps = false
            text = "Đóng menu"
            setOnClickListener { hideMenu() }
        }
        panel.addView(close, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val bubbleLp = bubbleView?.layoutParams as? WindowManager.LayoutParams
        val lp = baseLp(dp(232), WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            x = (bubbleLp?.x ?: dp(16)).coerceAtLeast(0)
            y = ((bubbleLp?.y ?: dp(320)) + dp(64)).coerceAtLeast(0)
        }
        try {
            wm.addView(panel, lp)
            menuView = panel
            refreshMenu()
        } catch (e: Exception) {
            menuRecordButton = null
            AppLogger.e("Overlay", "Could not show control menu: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun refreshMenu() {
        menuRecordButton?.text = if (isRecording) "Dừng ghi" else "Bắt đầu ghi"
        bubbleView?.invalidate()
    }

    private fun hideMenu() {
        menuView?.let { runCatching { wm.removeView(it) } }
        menuView = null
        menuRecordButton = null
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                MainActivity.CHANNEL_ID,
                "Realtime Touch Imitation",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Floating macro controls" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    // ── Recording ─────────────────────────────────────────────────────────────
    private fun startRecording() {
        if (isRecording) return
        if (MacroAccessibilityService.instance == null) {
            Toast.makeText(this, "Hãy bật Accessibility Service trước khi ghi.", Toast.LENGTH_LONG).show()
            AppLogger.w("Record", "Recording not started: accessibility service is not connected")
            return
        }
        isRecording = true
        MacroAccessibilityService.isRecording = true
        synchronized(MacroAccessibilityService.recordedSteps) {
            MacroAccessibilityService.recordedSteps.clear()
        }
        removeStepMarkers()
        stepListRevision++
        MacroAccessibilityService.lastRecordedEventTime = 0L
        AppLogger.i("Record", "Passive accessibility recording started")
        refreshMenu()
        refreshNotif()
        // Passive recording: do not add a touchable full-screen overlay.
        // The floating bubble is the only overlay; game input remains untouched.
    }

    private fun stopRecordingAndHide() {
        isRecording = false
        MacroAccessibilityService.isRecording = false
        removeOverlay(); hideCoordPicker()
        AppLogger.i("Record", "Passive recording stopped — ${MacroAccessibilityService.recordedSteps.size} accessibility events captured")
        refreshMenu()
        refreshNotif()
        stepListRevision++
        showStepMarkers()
    }

    // ── Capture overlay ───────────────────────────────────────────────────────
    private fun showOverlay() {
        if (overlayView != null) return
        val v = object : View(this) {
            private val tint   = Paint().apply { color = Color.argb(18, 220, 0, 0) }
            private val border = Paint().apply {
                color = Color.argb(130, 220, 0, 0); style = Paint.Style.STROKE; strokeWidth = 6f
            }
            private val info   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(180, 255, 255, 255); textSize = 38f; textAlign = Paint.Align.CENTER
                setShadowLayer(4f, 2f, 2f, Color.BLACK)
            }
            override fun onDraw(c: Canvas) {
                c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), tint)
                c.drawRect(3f, 3f, width - 3f, height - 3f, border)
                val cnt = MacroAccessibilityService.recordedSteps.size
                c.drawText(
                    if (cnt == 0) "Recording — perform your gestures"
                    else "Recording — $cnt step${if (cnt == 1) "" else "s"} captured",
                    width / 2f, height * 0.08f, info
                )
            }
            override fun onTouchEvent(ev: MotionEvent): Boolean {
                // Keep MOVE handling on the hot path allocation- and redraw-free.
                // The step counter only changes after a gesture completes.
                handleRecordTouch(ev)
                if (ev.actionMasked == MotionEvent.ACTION_UP ||
                    ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                    invalidate()
                }
                return true
            }
        }
        val lp = baseLp(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // FLAG_LAYOUT_IN_SCREEN: overlay starts at true (0,0) — matches dispatchGesture coords
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }
        wm.addView(v, lp); overlayView = v
        AppLogger.d("Overlay", "Capture overlay added (LAYOUT_IN_SCREEN)")
    }

    private fun removeOverlay() {
        overlayView?.let { runCatching { wm.removeView(it) }; overlayView = null }
    }

    /**
     * Gesture capture — fixed:
     * 1. Uses rawX/rawY (screen-absolute) so recorded coords match dispatchGesture coords.
     * 2. Tracks ACTION_MOVE so swipe endpoint is always accurate even for fast gestures.
     * 3. Handles ACTION_CANCEL (fired for navigation-gesture-area swipes on Android 10+).
     * 4. Does NOT call replayStep — that caused an infinite tap-spam loop because the
     *    injected gesture was delivered back to this overlay (it's the topmost window).
     *    Gestures are recorded and replayed later via MacroAccessibilityService.playSteps().
     */
    private fun handleRecordTouch(ev: MotionEvent) {
        when (ev.actionMasked) {

            MotionEvent.ACTION_DOWN -> {
                downRawX    = ev.rawX; downRawY = ev.rawY
                lastRawX    = ev.rawX; lastRawY = ev.rawY
                totalPathLen = 0f
                downTime    = ev.eventTime
            }

            MotionEvent.ACTION_MOVE -> {
                // Accumulate total travel distance — detects swipes even if finger ends near start
                totalPathLen += hypot(ev.rawX - lastRawX, ev.rawY - lastRawY)
                lastRawX = ev.rawX; lastRawY = ev.rawY
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                val endX = lastRawX;  val endY = lastRawY   // last known position
                val dur  = (ev.eventTime - downTime).coerceIn(0L, 10_000L)
                // dx/dy from true start to true end (for direction)
                val dx   = endX - downRawX; val dy = endY - downRawY

                // Use total path length to detect swipe (catches circular/wavy gestures too)
                val isSwipe = totalPathLen > 40f

                val idx  = MacroAccessibilityService.recordedSteps.size
                val step = when {
                    isSwipe -> {
                        MacroStep(
                            macroId = 0, stepIndex = idx, type = StepType.SWIPE,
                            x = downRawX, y = downRawY,
                            x2 = endX,    y2 = endY,
                            duration = dur.coerceIn(100L, 3_000L)
                        )
                    }
                    dur >= 500L -> {
                        MacroStep(
                            macroId = 0, stepIndex = idx, type = StepType.LONG_PRESS,
                            x = downRawX, y = downRawY,
                            duration = dur.coerceIn(600L, 5_000L)
                        )
                    }
                    else -> {
                        MacroStep(
                            macroId = 0, stepIndex = idx, type = StepType.TAP,
                            x = downRawX, y = downRawY, duration = 100L
                        )
                    }
                }
                MacroAccessibilityService.recordedSteps.add(step)
                // Avoid notification IPC on the touch callback; update once when recording stops.

                // Reset accumulators
                totalPathLen = 0f
            }
        }
    }

    // ── Coord picker ──────────────────────────────────────────────────────────
    private fun showCoordPicker(field: String) {
        if (coordPickerView != null) return
        hideMenu()
        val dm = resources.displayMetrics
        var pickX = dm.widthPixels / 2f; var pickY = dm.heightPixels / 2f
        var lastRX = 0f; var lastRY = 0f

        val view = object : View(this) {
            val bg      = Paint().apply { color = Color.argb(80,0,0,0) }
            val grid    = Paint().apply { color = Color.argb(30,255,255,255); strokeWidth=1f }
            val cross   = Paint().apply { color = Color.argb(200,0,200,255); strokeWidth=2f }
            val dot     = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.argb(60,0,200,255) }
            val dotEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color=Color.argb(255,0,200,255);style=Paint.Style.STROKE;strokeWidth=3f }
            val lbl     = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color=Color.WHITE;textSize=44f;textAlign=Paint.Align.CENTER
                setShadowLayer(4f,2f,2f,Color.BLACK) }
            val btn     = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.argb(220,30,30,30) }
            val btnSet  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.argb(220,0,140,200) }
            val btnTxt  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color=Color.WHITE;textSize=40f;textAlign=Paint.Align.CENTER
                setShadowLayer(3f,1f,1f,Color.BLACK) }

            override fun onDraw(c: Canvas) {
                val w=width.toFloat();val h=height.toFloat()
                c.drawRect(0f,0f,w,h,bg)
                var gx=0f;while(gx<w){c.drawLine(gx,0f,gx,h,grid);gx+=100f}
                var gy=0f;while(gy<h){c.drawLine(0f,gy,w,gy,grid);gy+=100f}
                c.drawLine(pickX,0f,pickX,h,cross);c.drawLine(0f,pickY,w,pickY,cross)
                c.drawCircle(pickX,pickY,36f,dot);c.drawCircle(pickX,pickY,36f,dotEdge)
                val ly=if(pickY>h*0.8f)pickY-55f else pickY+70f
                c.drawText("(${pickX.toInt()}, ${pickY.toInt()})",pickX,ly,lbl)
                val bw=260f;val bh=90f;val m=36f;val by=h-bh-m
                c.drawRoundRect(m,by,m+bw,by+bh,18f,18f,btn)
                c.drawText("Cancel",m+bw/2,by+bh*0.65f,btnTxt)
                val sx=w-m-bw
                c.drawRoundRect(sx,by,sx+bw,by+bh,18f,18f,btnSet)
                c.drawText("Set",sx+bw/2,by+bh*0.65f,btnTxt)
            }
            override fun onTouchEvent(ev: MotionEvent): Boolean {
                val w=width.toFloat();val h=height.toFloat()
                val bw=260f;val bh=90f;val m=36f;val by=h-bh-m
                when(ev.action){
                    MotionEvent.ACTION_DOWN->{lastRX=ev.rawX;lastRY=ev.rawY}
                    MotionEvent.ACTION_MOVE->{
                        pickX=(pickX+ev.rawX-lastRX).coerceIn(0f,w)
                        pickY=(pickY+ev.rawY-lastRY).coerceIn(0f,h)
                        lastRX=ev.rawX;lastRY=ev.rawY;invalidate()
                    }
                    MotionEvent.ACTION_UP->{
                        val tx=ev.x;val ty=ev.y
                        if(tx in m..(m+bw)&&ty in by..(by+bh)){coordPickResult.value=null;hideCoordPicker()}
                        val sx=w-m-bw
                        if(tx in sx..(sx+bw)&&ty in by..(by+bh)){
                            if (field == FIELD_ADD_SWIPE_START) {
                                pendingSwipeStart = pickX to pickY
                                hideCoordPicker()
                                showCoordPicker(FIELD_ADD_SWIPE_END)
                                Toast.makeText(this@FloatingOverlayService, "Chọn điểm kết thúc đường vuốt.", Toast.LENGTH_SHORT).show()
                            } else if (field == FIELD_ADD_TAP || field == FIELD_ADD_SWIPE_END) {
                                completeCoordinatePick(field, pickX, pickY)
                                hideCoordPicker()
                            } else {
                                coordPickResult.value=Pair(field,Pair(pickX,pickY))
                                hideCoordPicker()
                            }
                        }
                    }
                };return true
            }
        }
        val lp = baseLp(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }
        wm.addView(view, lp); coordPickerView = view
        bringBubbleToFront()
    }


    private fun completeCoordinatePick(field: String, x: Float, y: Float) {
        when (field) {
            FIELD_ADD_TAP -> {
                synchronized(MacroAccessibilityService.recordedSteps) {
                    val list = MacroAccessibilityService.recordedSteps
                    list.add(MacroStep(macroId = 0L, stepIndex = list.size, type = StepType.TAP,
                        x = x, y = y, duration = 100L, delayBefore = 0L, label = "Manual tap point"))
                }
                stepListRevision++
                showStepMarkers()
                Toast.makeText(this, "Đã thêm điểm chạm. Nhấn vào dấu số để chỉnh thời gian.", Toast.LENGTH_SHORT).show()
            }
            FIELD_ADD_SWIPE_END -> {
                val start = pendingSwipeStart
                pendingSwipeStart = null
                if (start != null) {
                    synchronized(MacroAccessibilityService.recordedSteps) {
                        val list = MacroAccessibilityService.recordedSteps
                        list.add(MacroStep(macroId = 0L, stepIndex = list.size, type = StepType.SWIPE,
                            x = start.first, y = start.second, x2 = x, y2 = y,
                            duration = 350L, delayBefore = 0L, label = "Manual swipe"))
                    }
                    stepListRevision++
                    showStepMarkers()
                    Toast.makeText(this, "Đã thêm đường vuốt. Nhấn vào dấu số để chỉnh thời gian.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showStepMarkers() {
        if (!Settings.canDrawOverlays(this)) return
        removeStepMarkers()
        val snapshot = synchronized(MacroAccessibilityService.recordedSteps) {
            MacroAccessibilityService.recordedSteps.toList()
        }
        snapshot.forEachIndexed { index, step ->
            val markerSize = dp(46)
            val marker = object : View(this) {
                private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
                private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat()
                }
                private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE; textAlign = Paint.Align.CENTER
                    textSize = dp(13).toFloat(); typeface = Typeface.DEFAULT_BOLD
                }
                override fun onDraw(canvas: Canvas) {
                    val cx = width / 2f; val cy = height / 2f
                    fill.color = when (step.type) {
                        StepType.SWIPE, StepType.SCROLL_UP, StepType.SCROLL_DOWN -> Color.rgb(230, 126, 34)
                        StepType.LONG_PRESS -> Color.rgb(156, 39, 176)
                        else -> Color.rgb(30, 136, 229)
                    }
                    canvas.drawCircle(cx, cy, minOf(cx, cy) - dp(3), fill)
                    canvas.drawCircle(cx, cy, minOf(cx, cy) - dp(3), border)
                    canvas.drawText((index + 1).toString(), cx, cy + dp(4).toFloat(), numberPaint)
                }
            }
            val lp = baseLp(markerSize, markerSize).apply {
                gravity = Gravity.TOP or Gravity.START
                flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                x = (step.x.toInt() - markerSize / 2).coerceAtLeast(0)
                y = (step.y.toInt() - markerSize / 2).coerceAtLeast(0)
            }
            var downX = 0f
            var downY = 0f
            var dragged = false
            marker.setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; dragged = false; true }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (kotlin.math.hypot(dx, dy) > dp(6)) dragged = true
                        if (dragged) {
                            lp.x = (lp.x + dx.toInt()).coerceAtLeast(0)
                            lp.y = (lp.y + dy.toInt()).coerceAtLeast(0)
                            runCatching { wm.updateViewLayout(view, lp) }
                            downX = event.rawX; downY = event.rawY
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (dragged) {
                            val centerX = lp.x + markerSize / 2f
                            val centerY = lp.y + markerSize / 2f
                            synchronized(MacroAccessibilityService.recordedSteps) {
                                val list = MacroAccessibilityService.recordedSteps
                                if (index in list.indices) {
                                    val old = list[index]
                                    val dx = centerX - old.x; val dy = centerY - old.y
                                    list[index] = old.copy(x = centerX, y = centerY,
                                        x2 = if (old.type == StepType.SWIPE) old.x2 + dx else old.x2,
                                        y2 = if (old.type == StepType.SWIPE) old.y2 + dy else old.y2)
                                }
                            }
                            stepListRevision++
                            showStepMarkers()
                        } else showStepTimingDialog(index)
                        true
                    }
                    else -> true
                }
            }
            try {
                wm.addView(marker, lp)
                stepMarkerViews[index] = marker
            } catch (e: Exception) {
                AppLogger.w("Overlay", "Could not show step marker " + (index + 1) + ": " + e.message)
            }
        }
    }

    private fun removeStepMarkers() {
        stepMarkerViews.values.forEach { view -> runCatching { wm.removeView(view) } }
        stepMarkerViews.clear()
    }

    private fun showStepTimingDialog(index: Int) {
        val step = synchronized(MacroAccessibilityService.recordedSteps) {
            MacroAccessibilityService.recordedSteps.getOrNull(index)
        } ?: return
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        val durationInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(step.duration.toString())
            hint = "Thời lượng thao tác (ms)"
        }
        val delayInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(step.delayBefore.toString())
            hint = "Chờ trước khi chạy (ms)"
        }
        content.addView(TextView(this).apply { text = "Thời lượng thao tác (ms)"; setTextColor(Color.WHITE) })
        content.addView(durationInput)
        content.addView(TextView(this).apply { text = "Thời gian chờ trước thao tác (ms)"; setTextColor(Color.WHITE) })
        content.addView(delayInput)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Chỉnh thao tác " + (index + 1))
            .setView(content)
            .setPositiveButton("Lưu") { _, _ ->
                val duration = durationInput.text.toString().toLongOrNull()?.coerceAtLeast(1L) ?: step.duration
                val delay = delayInput.text.toString().toLongOrNull()?.coerceAtLeast(0L) ?: step.delayBefore
                synchronized(MacroAccessibilityService.recordedSteps) {
                    val list = MacroAccessibilityService.recordedSteps
                    if (index in list.indices) list[index] = list[index].copy(duration = duration, delayBefore = delay)
                }
                stepListRevision++
                showStepMarkers()
            }
            .setNeutralButton("Xóa") { _, _ ->
                synchronized(MacroAccessibilityService.recordedSteps) {
                    val list = MacroAccessibilityService.recordedSteps
                    if (index in list.indices) {
                        list.removeAt(index)
                        list.indices.forEach { i -> list[i] = list[i].copy(stepIndex = i) }
                    }
                }
                stepListRevision++
                showStepMarkers()
            }
            .setNegativeButton("Đóng", null)
            .create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
    }

    private fun hideCoordPicker() {
        coordPickerView?.let { runCatching { wm.removeView(it) }; coordPickerView = null }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun setupDrag(view: View, lp: WindowManager.LayoutParams, onClick: () -> Unit) {
        var sx=0f;var sy=0f;var drag=false
        view.setOnTouchListener { _,ev ->
            when(ev.action){
                MotionEvent.ACTION_DOWN->{sx=ev.rawX;sy=ev.rawY;drag=false;true}
                MotionEvent.ACTION_MOVE->{
                    if(!drag&&hypot(ev.rawX-sx,ev.rawY-sy)>12f)drag=true
                    if(drag){lp.x+=(ev.rawX-sx).toInt();lp.y+=(ev.rawY-sy).toInt()
                        wm.updateViewLayout(view,lp);sx=ev.rawX;sy=ev.rawY}
                    true
                }
                MotionEvent.ACTION_UP->{if(!drag)onClick();true}
                else->false
            }
        }
    }

    private fun baseLp(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    )

    private inner class BubbleView(ctx: Context) : View(ctx) {
        private val fill   = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color=Color.WHITE;style=Paint.Style.STROKE;strokeWidth=4f }
        private val txt    = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color=Color.WHITE;textAlign=Paint.Align.CENTER
            textSize=28f;typeface=Typeface.DEFAULT_BOLD }
        override fun onDraw(c: Canvas) {
            val cx=width/2f;val cy=height/2f;val r=minOf(cx,cy)-4f
            fill.color=if(isRecording)Color.argb(230,210,45,45) else Color.argb(230,33,150,83)
            c.drawCircle(cx,cy,r,fill);c.drawCircle(cx,cy,r,stroke)
            val cnt=MacroAccessibilityService.recordedSteps.size
            c.drawText(if(isRecording&&cnt>0)"$cnt" else if(isRecording)"●" else "⏺",cx,cy+10f,txt)
        }
    }

    companion object {
        private const val NOTIF_ID       = 1337
        const val CMD                    = "command"
        const val CMD_START              = "start_record"
        const val CMD_STOP               = "stop_record"
        const val CMD_TOGGLE             = "toggle"
        const val CMD_DISMISS            = "dismiss"
        const val CMD_PICK_START         = "pick_start"
        const val CMD_PICK_CANCEL        = "pick_cancel"
        const val EXTRA_PICK_FIELD       = "pick_field"
        const val FIELD_XY               = "xy"
        const val FIELD_XY2              = "xy2"
        const val FIELD_ADD_TAP            = "add_tap"
        const val FIELD_ADD_SWIPE_START    = "add_swipe_start"
        const val FIELD_ADD_SWIPE_END      = "add_swipe_end"
        @Volatile var stepListRevision: Long = 0L
        val coordPickResult = MutableStateFlow<Pair<String, Pair<Float,Float>>?>(null)
    }
}
