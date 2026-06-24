package io.github.teccheck.fastlyrics.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings as AndroidSettings
import android.util.Log
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dirror.lyricviewx.LyricViewX
import io.github.teccheck.fastlyrics.MainActivity
import io.github.teccheck.fastlyrics.R
import io.github.teccheck.fastlyrics.Settings
import kotlin.math.roundToInt

class LyricsOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var appSettings: Settings

    private var lyricsWindowView: View? = null
    private var lyricsTextView: TextView? = null
    private var lyricViewX: LyricViewX? = null
    private var lyricsParams: WindowManager.LayoutParams? = null

    private var controlsWindowView: View? = null
    private var controlsParams: WindowManager.LayoutParams? = null
    private var touchToggleButton: ImageButton? = null

    private var settingsWindowView: View? = null
    private var settingsParams: WindowManager.LayoutParams? = null

    private var isTouchThrough = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        appSettings = Settings(this)
        appSettings.setOverlayServiceRunning(false)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching {
            when (intent?.action) {
                ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
                ACTION_TOGGLE_TOUCH_THROUGH -> { setTouchThrough(!isTouchThrough); return START_STICKY }
                ACTION_UPDATE_LYRICS, ACTION_START -> {
                    val lyrics = intent.getStringExtra(EXTRA_LYRICS).orEmpty()
                    val syncedLyrics = intent.getStringExtra(EXTRA_SYNCED_LYRICS)
                    ensureOverlayVisible()
                    loadLyrics(lyrics, syncedLyrics)
                    if (intent.action == ACTION_START) setTouchThrough(false)
                }
            }
        }.onFailure {
            Log.e(TAG, "Overlay command failure", it)
            appSettings.setOverlayLastError("command: ${it.javaClass.simpleName}: ${it.message ?: "unknown"}")
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        appSettings.setOverlayServiceRunning(false)
        @Suppress("DEPRECATION")
        stopForeground(true)
        removeOverlay()
        super.onDestroy()
    }

    private fun ensureOverlayVisible() {
        if (lyricsWindowView != null) return
        if (!hasOverlayPermission()) {
            appSettings.setOverlayLastError("permission: canDrawOverlays=false")
            appSettings.setOverlayServiceRunning(false); stopSelf(); return
        }

        val themedContext = ContextThemeWrapper(this, R.style.Theme_FastLyrics_Material2)
        val density = resources.displayMetrics.density
        val screenW = resources.displayMetrics.widthPixels
        val topY = (density * 64).roundToInt()

        // --- Lyrics window ---
        val lView = LayoutInflater.from(themedContext).inflate(R.layout.overlay_lyrics, null)
        val lParams = WindowManager.LayoutParams(
            sizeWidthPx(screenW), WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(), defaultLyricsFlags(), PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = topY }

        applyBackgroundAlpha(lView)
        setupLyricsDrag(lView, lParams)

        runCatching { windowManager.addView(lView, lParams) }.onFailure {
            appSettings.setOverlayLastError("addView lyrics: ${it.javaClass.simpleName}: ${it.message ?: "unknown"}")
            appSettings.setOverlayServiceRunning(false); stopSelf(); return
        }

        val lx = lView.findViewById<LyricViewX>(R.id.overlay_lyric_view_x).apply {
            setNormalTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics))
            setCurrentTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 19f, resources.displayMetrics))
            setCurrentColor(ContextCompat.getColor(themedContext, R.color.theme_primary))
            setNormalColor(0xCCFFFFFF.toInt())
        }

        // --- Controls window (ALWAYS interactive) ---
        val ctrlView = LayoutInflater.from(themedContext).inflate(R.layout.overlay_controls, null)
        val btnToggle   = ctrlView.findViewById<ImageButton>(R.id.overlay_ctrl_touch_toggle)
        val btnClose    = ctrlView.findViewById<ImageButton>(R.id.overlay_ctrl_close)
        val btnOpenApp  = ctrlView.findViewById<ImageButton>(R.id.overlay_ctrl_open_app)
        val btnSettings = ctrlView.findViewById<ImageButton>(R.id.overlay_ctrl_settings)
        val ctrlParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; y = topY }

        btnClose.setOnClickListener { stopSelf() }
        btnToggle.setOnClickListener { setTouchThrough(!isTouchThrough) }
        btnOpenApp.setOnClickListener {
            // Bring existing task to front, never create a new instance
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            })
        }
        btnSettings.setOnClickListener { toggleSettingsPanel(themedContext, lParams.y) }

        runCatching { windowManager.addView(ctrlView, ctrlParams) }
            .onFailure { Log.w(TAG, "Controls window failed", it) }

        lyricsWindowView = lView
        lyricsTextView   = lView.findViewById(R.id.overlay_text_lyrics)
        lyricViewX       = lx
        lyricsParams     = lParams
        controlsWindowView = ctrlView
        controlsParams     = ctrlParams
        touchToggleButton  = btnToggle

        appSettings.setOverlayServiceRunning(true)
        appSettings.setOverlayLastError(null)
        runCatching { startForeground(NOTIFICATION_ID, buildNotification()) }
            .onFailure { Log.w(TAG, "startForeground failed", it) }
        syncTouchToggleUi()
    }

    private fun toggleSettingsPanel(ctx: Context, nearY: Int) {
        if (settingsWindowView != null) {
            runCatching { windowManager.removeView(settingsWindowView) }
            settingsWindowView = null; settingsParams = null; return
        }

        val sv = LayoutInflater.from(ctx).inflate(R.layout.overlay_settings, null)
        val alpha = appSettings.getOverlayBackgroundAlpha()
        val sParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; y = nearY + 80 }

        val alphaSlider  = sv.findViewById<SeekBar>(R.id.overlay_alpha_slider)
        val alphaLabel   = sv.findViewById<TextView>(R.id.overlay_alpha_value)
        val btnS = sv.findViewById<Button>(R.id.overlay_size_s)
        val btnM = sv.findViewById<Button>(R.id.overlay_size_m)
        val btnL = sv.findViewById<Button>(R.id.overlay_size_l)
        val btnClose = sv.findViewById<Button>(R.id.overlay_settings_close)

        // Slider range 10..100 mapped to SeekBar 0..90
        alphaSlider.progress = (alpha - 10).coerceIn(0, 90)
        alphaLabel.text = "$alpha%"

        alphaSlider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                val v = p + 10
                alphaLabel.text = "$v%"
                if (fromUser) { appSettings.setOverlayBackgroundAlpha(v); applyBackgroundAlpha(lyricsWindowView) }
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) = Unit
        })

        fun applySize(s: String) {
            appSettings.setOverlaySize(s)
            val screenW = resources.displayMetrics.widthPixels
            lyricsParams?.let { p ->
                p.width = sizeWidthPx(screenW)
                lyricsWindowView?.let { windowManager.updateViewLayout(it, p) }
            }
        }

        btnS.setOnClickListener { applySize("S") }
        btnM.setOnClickListener { applySize("M") }
        btnL.setOnClickListener { applySize("L") }
        btnClose.setOnClickListener { toggleSettingsPanel(ctx, nearY) }

        runCatching { windowManager.addView(sv, sParams) }
            .onFailure { Log.w(TAG, "Settings window failed", it) }
        settingsWindowView = sv
        settingsParams = sParams
    }

    private fun applyBackgroundAlpha(view: View?) {
        view ?: return
        val alpha = appSettings.getOverlayBackgroundAlpha()
        val bg = view.background
        if (bg is GradientDrawable) {
            bg.alpha = (alpha * 255 / 100).coerceIn(0, 255)
        } else {
            bg?.alpha = (alpha * 255 / 100).coerceIn(0, 255)
        }
    }

    private fun sizeWidthPx(screenW: Int): Int {
        val ratio = when (appSettings.getOverlaySize()) {
            "S" -> 0.50f
            "L" -> 0.95f
            else -> 0.75f // M default
        }
        return (screenW * ratio).roundToInt()
    }

    private fun loadLyrics(plain: String, synced: String?) {
        if (!synced.isNullOrBlank()) {
            lyricsTextView?.visibility = View.GONE
            lyricViewX?.apply { visibility = View.VISIBLE; loadLyric(synced) }
        } else {
            lyricViewX?.visibility = View.GONE
            lyricsTextView?.apply { visibility = View.VISIBLE; if (plain.isNotBlank()) text = plain }
        }
    }

    fun updatePosition(timeMs: Long) { lyricViewX?.updateTime(timeMs) }

    private fun setupLyricsDrag(view: View, params: WindowManager.LayoutParams) {
        var startX = 0; var startY = 0
        var touchStartX = 0f; var touchStartY = 0f
        view.setOnTouchListener { _, event ->
            if (isTouchThrough) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchStartX = event.rawX; touchStartY = event.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - touchStartX).toInt()
                    params.y = startY + (event.rawY - touchStartY).toInt()
                    lyricsWindowView?.let { windowManager.updateViewLayout(it, params) }
                    controlsParams?.let { cp -> cp.y = params.y; controlsWindowView?.let { windowManager.updateViewLayout(it, cp) } }
                    true
                }
                else -> false
            }
        }
    }

    private fun setTouchThrough(enabled: Boolean) {
        isTouchThrough = enabled
        val view = lyricsWindowView ?: return
        val params = lyricsParams ?: return
        params.flags = if (enabled) defaultLyricsFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else defaultLyricsFlags()
        windowManager.updateViewLayout(view, params)
        syncTouchToggleUi(); updateNotification()
    }

    private fun syncTouchToggleUi() {
        touchToggleButton?.apply {
            setImageResource(if (isTouchThrough) R.drawable.baseline_check_circle_24 else R.drawable.baseline_drag_handle_24)
            contentDescription = getString(if (isTouchThrough) R.string.overlay_touch_through_on else R.string.overlay_touch_through_off)
        }
    }

    private fun removeOverlay() {
        settingsWindowView?.let { runCatching { windowManager.removeView(it) } }
        lyricsWindowView?.let { runCatching { windowManager.removeView(it) } }
        controlsWindowView?.let { runCatching { windowManager.removeView(it) } }
        lyricsWindowView = null; lyricsTextView = null; lyricViewX = null; lyricsParams = null
        controlsWindowView = null; controlsParams = null; touchToggleButton = null
        settingsWindowView = null; settingsParams = null
        appSettings.setOverlayServiceRunning(false)
    }

    private fun defaultLyricsFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun hasOverlayPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) AndroidSettings.canDrawOverlays(this) else true

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) return
        manager.createNotificationChannel(NotificationChannel(NOTIFICATION_CHANNEL_ID, "Lyrics Overlay", NotificationManager.IMPORTANCE_LOW))
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = PendingIntent.getService(this, 1,
            Intent(this, LyricsOverlayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val touchIntent = PendingIntent.getService(this, 2,
            Intent(this, LyricsOverlayService::class.java).apply { action = ACTION_TOGGLE_TOUCH_THROUGH },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val touchLabel = if (isTouchThrough) "▶ Interactive" else "👆 Pass-through"
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.round_music_note_24)
            .setContentTitle("FastLyrics Overlay")
            .setContentText(if (isTouchThrough) "Pass-through ON" else "Interactive")
            .setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .addAction(0, touchLabel, touchIntent)
            .addAction(0, "■ Stop", stopIntent)
            .build()
    }

    private fun updateNotification() {
        runCatching { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification()) }
    }

    companion object {
        private const val TAG = "LyricsOverlayService"
        private const val NOTIFICATION_CHANNEL_ID = "lyrics_overlay"
        private const val NOTIFICATION_ID = 4201
        var instance: LyricsOverlayService? = null
            private set
        const val ACTION_START = "io.github.teccheck.fastlyrics.action.OVERLAY_START"
        const val ACTION_STOP = "io.github.teccheck.fastlyrics.action.OVERLAY_STOP"
        const val ACTION_UPDATE_LYRICS = "io.github.teccheck.fastlyrics.action.OVERLAY_UPDATE"
        const val ACTION_TOGGLE_TOUCH_THROUGH = "io.github.teccheck.fastlyrics.action.OVERLAY_TOGGLE_TOUCH"
        const val EXTRA_LYRICS = "extra_lyrics"
        const val EXTRA_SYNCED_LYRICS = "extra_synced_lyrics"
    }
}

