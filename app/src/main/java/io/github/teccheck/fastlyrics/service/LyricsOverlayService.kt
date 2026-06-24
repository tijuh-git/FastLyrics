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
import android.provider.Settings
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
import kotlin.math.roundToInt

class LyricsOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var lyricsView: TextView? = null
    private var touchToggleButton: ImageButton? = null

    private var isTouchThrough = false

    private var layoutParams: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_TOGGLE_TOUCH_THROUGH -> {
                setTouchThrough(!isTouchThrough)
                updateNotification()
                return START_STICKY
            }

            ACTION_UPDATE_LYRICS, ACTION_START -> {
                val lyrics = intent.getStringExtra(EXTRA_LYRICS).orEmpty()
                ensureOverlayVisible()
                updateLyrics(lyrics)
                if (intent.action == ACTION_START) {
                    setTouchThrough(false)
                    updateNotification()
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        @Suppress("DEPRECATION")
        stopForeground(true)
        removeOverlay()
        super.onDestroy()
    }

    private fun ensureOverlayVisible() {
        if (overlayView != null) return

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_lyrics, null)
        val textLyrics = view.findViewById<TextView>(R.id.overlay_text_lyrics)
        val buttonClose = view.findViewById<ImageButton>(R.id.overlay_button_close)
        val buttonTouchToggle = view.findViewById<ImageButton>(R.id.overlay_button_touch_toggle)

        val widthPx = (resources.displayMetrics.widthPixels * 0.9f).roundToInt()

        val params = WindowManager.LayoutParams(
            widthPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            defaultFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (resources.displayMetrics.density * 64).roundToInt()
        }

        buttonClose.setOnClickListener {
            stopSelf()
        }

        buttonTouchToggle.setOnClickListener {
            setTouchThrough(!isTouchThrough)
            updateNotification()
        }

        setupDrag(view, params)

        windowManager.addView(view, params)

        overlayView = view
        lyricsView = textLyrics
        touchToggleButton = buttonTouchToggle
        layoutParams = params

        startForeground(NOTIFICATION_ID, buildNotification())
        syncTouchToggleUi()
    }

    private fun setupDrag(view: View, params: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var touchStartX = 0f
        var touchStartY = 0f

        view.setOnTouchListener { _, event ->
            if (isTouchThrough) {
                return@setOnTouchListener false
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - touchStartX).toInt()
                    params.y = startY + (event.rawY - touchStartY).toInt()
                    overlayView?.let { windowManager.updateViewLayout(it, params) }
                    true
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

        params.flags = if (enabled) {
            defaultFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            defaultFlags()
        }

        windowManager.updateViewLayout(view, params)
        syncTouchToggleUi()
    }

    private fun syncTouchToggleUi() {
        touchToggleButton?.apply {
            setImageResource(if (isTouchThrough) R.drawable.baseline_check_circle_24 else R.drawable.baseline_drag_handle_24)
            contentDescription = getString(
                if (isTouchThrough) R.string.overlay_touch_through_on else R.string.overlay_touch_through_off
            )
            alpha = if (isTouchThrough) 0.65f else 1f
        }
    }

    private fun removeOverlay() {
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
        lyricsView = null
        touchToggleButton = null
        layoutParams = null
    }

    private fun defaultFlags(): Int {
        return WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, LyricsOverlayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val touchIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, LyricsOverlayService::class.java).apply { action = ACTION_TOGGLE_TOUCH_THROUGH },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val touchActionTitle =
            if (isTouchThrough) R.string.overlay_disable_touch_through else R.string.overlay_enable_touch_through

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.round_music_note_24)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent)
            .addAction(R.drawable.baseline_drag_handle_24, getString(touchActionTitle), touchIntent)
            .addAction(R.drawable.baseline_close_24, getString(R.string.overlay_stop), stopIntent)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.overlay_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "lyrics_overlay"
        private const val NOTIFICATION_ID = 4201

        const val ACTION_START = "io.github.teccheck.fastlyrics.action.OVERLAY_START"
        const val ACTION_STOP = "io.github.teccheck.fastlyrics.action.OVERLAY_STOP"
        const val ACTION_UPDATE_LYRICS = "io.github.teccheck.fastlyrics.action.OVERLAY_UPDATE"
        const val ACTION_TOGGLE_TOUCH_THROUGH = "io.github.teccheck.fastlyrics.action.OVERLAY_TOGGLE_TOUCH"

        const val EXTRA_LYRICS = "extra_lyrics"
    }
}



