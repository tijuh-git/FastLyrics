package io.github.teccheck.fastlyrics.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings as AndroidSettings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.app.NotificationCompat
import io.github.teccheck.fastlyrics.MainActivity
import io.github.teccheck.fastlyrics.R
import io.github.teccheck.fastlyrics.Settings
import kotlin.math.roundToInt

class LyricsOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var lyricsView: TextView? = null
    private var touchToggleButton: ImageButton? = null
    private lateinit var appSettings: Settings

    private var isTouchThrough = false
    private var layoutParams: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        appSettings = Settings(this)
        appSettings.setOverlayServiceRunning(false)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching {
            when (intent?.action) {
                ACTION_STOP -> {
                    stopSelf()
                    return START_NOT_STICKY
                }
                ACTION_TOGGLE_TOUCH_THROUGH -> {
                    setTouchThrough(!isTouchThrough)
                    return START_STICKY
                }
                ACTION_UPDATE_LYRICS, ACTION_START -> {
                    val lyrics = intent.getStringExtra(EXTRA_LYRICS).orEmpty()
                    ensureOverlayVisible()
                    updateLyrics(lyrics)
                    if (intent.action == ACTION_START) {
                        setTouchThrough(false)
                    }
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
        appSettings.setOverlayServiceRunning(false)
        @Suppress("DEPRECATION")
        stopForeground(true)
        removeOverlay()
        super.onDestroy()
    }

    private fun ensureOverlayVisible() {
        if (overlayView != null) return

        if (!hasOverlayPermission()) {
            appSettings.setOverlayLastError("permission: canDrawOverlays=false")
            appSettings.setOverlayServiceRunning(false)
            stopSelf()
            return
        }

        val themedContext = ContextThemeWrapper(this, R.style.Theme_FastLyrics_Material2)
        val view = LayoutInflater.from(themedContext).inflate(R.layout.overlay_lyrics, null)
        val textLyrics = view.findViewById<TextView>(R.id.overlay_text_lyrics)
        val buttonClose = view.findViewById<ImageButton>(R.id.overlay_button_close)
        val buttonTouchToggle = view.findViewById<ImageButton>(R.id.overlay_button_touch_toggle)

        val widthPx = (resources.displayMetrics.widthPixels * 0.9f).roundToInt()

        val params = WindowManager.LayoutParams(
            widthPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            defaultFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (resources.displayMetrics.density * 64).roundToInt()
        }

        buttonClose.setOnClickListener { stopSelf() }
        buttonTouchToggle.setOnClickListener { setTouchThrough(!isTouchThrough) }

        setupDrag(view, params)

        runCatching {
            windowManager.addView(view, params)
        }.onFailure {
            Log.e(TAG, "Failed to add overlay view", it)
            appSettings.setOverlayLastError("addView: ${it.javaClass.simpleName}: ${it.message ?: "unknown"}")
            appSettings.setOverlayServiceRunning(false)
            stopSelf()
            return
        }

        overlayView = view
        lyricsView = textLyrics
        touchToggleButton = buttonTouchToggle
        layoutParams = params
        appSettings.setOverlayServiceRunning(true)
        appSettings.setOverlayLastError(null)

        runCatching { startForeground(NOTIFICATION_ID, buildNotification()) }
            .onFailure { Log.w(TAG, "startForeground failed, continuing without notification", it) }

        syncTouchToggleUi()
    }

    private fun setupDrag(view: View, params: WindowManager.LayoutParams) {
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
                    overlayView?.let { windowManager.updateViewLayout(it, params) }; true
                }
                else -> false
            }
        }
    }

    private fun updateLyrics(lyrics: String) {
        if (lyrics.isBlank()) return
        lyricsView?.text = lyrics
    }

    private fun setTouchThrough(enabled: Boolean) {
        isTouchThrough = enabled
        val view = overlayView ?: return
        val params = layoutParams ?: return
        params.flags = if (enabled) defaultFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                       else defaultFlags()
        windowManager.updateViewLayout(view, params)
        syncTouchToggleUi()
        updateNotification()
    }

    private fun syncTouchToggleUi() {
        touchToggleButton?.apply {
            setImageResource(if (isTouchThrough) R.drawable.baseline_check_circle_24 else R.drawable.baseline_drag_handle_24)
            contentDescription = getString(if (isTouchThrough) R.string.overlay_touch_through_on else R.string.overlay_touch_through_off)
            alpha = if (isTouchThrough) 0.65f else 1f
        }
    }

    private fun removeOverlay() {
        overlayView?.let {
            runCatching { windowManager.removeView(it) }
                .onFailure { error -> Log.w(TAG, "Overlay removeView ignored", error) }
        }
        overlayView = null; lyricsView = null; touchToggleButton = null; layoutParams = null
        appSettings.setOverlayServiceRunning(false)
    }

    private fun defaultFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun hasOverlayPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) AndroidSettings.canDrawOverlays(this) else true

    // ---- Notification ----

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(NOTIFICATION_CHANNEL_ID, "Lyrics Overlay", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, LyricsOverlayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val touchIntent = PendingIntent.getService(
            this, 2,
            Intent(this, LyricsOverlayService::class.java).apply { action = ACTION_TOGGLE_TOUCH_THROUGH },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val touchLabel = if (isTouchThrough) "▶ Interactive" else "👆 Pass-through"
        val statusText = if (isTouchThrough) "Pass-through ON — tap ▶ Interactive to use overlay" else "Interactive — drag to move"
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.round_music_note_24)
            .setContentTitle("Lyrics Overlay")
            .setContentText(statusText)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .addAction(0, touchLabel, touchIntent)
            .addAction(0, "■ Stop", stopIntent)
            .build()
    }

    private fun updateNotification() {
        runCatching {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    companion object {
        private const val TAG = "LyricsOverlayService"
        private const val NOTIFICATION_CHANNEL_ID = "lyrics_overlay"
        private const val NOTIFICATION_ID = 4201

        const val ACTION_START = "io.github.teccheck.fastlyrics.action.OVERLAY_START"
        const val ACTION_STOP = "io.github.teccheck.fastlyrics.action.OVERLAY_STOP"
        const val ACTION_UPDATE_LYRICS = "io.github.teccheck.fastlyrics.action.OVERLAY_UPDATE"
        const val ACTION_TOGGLE_TOUCH_THROUGH = "io.github.teccheck.fastlyrics.action.OVERLAY_TOGGLE_TOUCH"

        const val EXTRA_LYRICS = "extra_lyrics"
    }
}

