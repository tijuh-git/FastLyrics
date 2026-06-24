package io.github.teccheck.fastlyrics.service

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings as AndroidSettings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import io.github.teccheck.fastlyrics.Settings
import io.github.teccheck.fastlyrics.R
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
            stopSelf()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        appSettings.setOverlayServiceRunning(false)
        removeOverlay()
        super.onDestroy()
    }

    private fun ensureOverlayVisible() {
        if (overlayView != null) return

        if (!hasOverlayPermission()) {
            appSettings.setOverlayServiceRunning(false)
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
            overlayWindowType(),
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
        }

        setupDrag(view, params)

        runCatching {
            windowManager.addView(view, params)
        }.onFailure {
            Log.e(TAG, "Failed to add overlay view", it)
            appSettings.setOverlayServiceRunning(false)
            stopSelf()
            return
        }

        overlayView = view
        lyricsView = textLyrics
        touchToggleButton = buttonTouchToggle
        layoutParams = params
        appSettings.setOverlayServiceRunning(true)

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
        overlayView?.let {
            runCatching { windowManager.removeView(it) }
                .onFailure { error -> Log.w(TAG, "Overlay removeView ignored", error) }
        }
        overlayView = null
        lyricsView = null
        touchToggleButton = null
        layoutParams = null
        appSettings.setOverlayServiceRunning(false)
    }

    private fun defaultFlags(): Int {
        return WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
    }

    private fun overlayWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AndroidSettings.canDrawOverlays(this)
        } else {
            true
        }
    }

    companion object {
        private const val TAG = "LyricsOverlayService"

        const val ACTION_START = "io.github.teccheck.fastlyrics.action.OVERLAY_START"
        const val ACTION_STOP = "io.github.teccheck.fastlyrics.action.OVERLAY_STOP"
        const val ACTION_UPDATE_LYRICS = "io.github.teccheck.fastlyrics.action.OVERLAY_UPDATE"
        const val ACTION_TOGGLE_TOUCH_THROUGH = "io.github.teccheck.fastlyrics.action.OVERLAY_TOGGLE_TOUCH"

        const val EXTRA_LYRICS = "extra_lyrics"
    }
}








