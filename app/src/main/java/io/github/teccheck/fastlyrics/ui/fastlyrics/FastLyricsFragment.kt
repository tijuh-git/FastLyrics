package io.github.teccheck.fastlyrics.ui.fastlyrics

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.GestureDetector
import androidx.core.content.ContextCompat
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Result
import dev.forkhandles.result4k.Success
import dev.forkhandles.result4k.valueOrNull
import io.github.teccheck.fastlyrics.R
import io.github.teccheck.fastlyrics.Settings
import io.github.teccheck.fastlyrics.databinding.FragmentFastLyricsBinding
import io.github.teccheck.fastlyrics.exceptions.LyricsApiException
import io.github.teccheck.fastlyrics.model.SongMeta
import io.github.teccheck.fastlyrics.model.SongWithLyrics
import io.github.teccheck.fastlyrics.service.LyricsOverlayService
import io.github.teccheck.fastlyrics.utils.Utils
import io.github.teccheck.fastlyrics.utils.Utils.copyToClipboard
import io.github.teccheck.fastlyrics.utils.Utils.openLink
import io.github.teccheck.fastlyrics.utils.Utils.setVisible
import io.github.teccheck.fastlyrics.utils.Utils.share

import com.squareup.picasso.Picasso
import android.graphics.drawable.BitmapDrawable
import android.widget.Toast

class FastLyricsFragment : Fragment() {

    private lateinit var lyricsViewModel: FastLyricsViewModel
    private var _binding: FragmentFastLyricsBinding? = null

    // This property is only valid between onCreateView and onDestroyView.
    private val binding get() = _binding!!

    private lateinit var settings: Settings

    private var isFullscreenMode = false
    private var isOverlayRunning = false
    private lateinit var doubleTapDetector: GestureDetector

    private val menuProvider = object : MenuProvider {
        override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
            menuInflater.inflate(R.menu.menu_main, menu)
        }

        override fun onMenuItemSelected(item: MenuItem): Boolean {
            return onMenuAction(item)
        }
    }

    private fun onMenuAction(item: MenuItem): Boolean {
        val state = lyricsViewModel.state
        return when (item.itemId) {
            R.id.action_scroll_top -> {
                binding.scrollView.smoothScrollTo(0, 0)
                true
            }
            R.id.action_copy -> {
                copyToClipboard(requireContext(), getString(R.string.lyrics_clipboard_label), state.getLyrics())
                true
            }
            R.id.action_share -> {
                share(requireContext(), state.getSongTitle(), state.getSongArtist(), state.getLyrics())
                true
            }
            R.id.action_source -> {
                openLink(requireContext(), state.getSourceUrl())
                true
            }
            else -> false
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        lyricsViewModel = ViewModelProvider(this)[FastLyricsViewModel::class.java]
        _binding = FragmentFastLyricsBinding.inflate(inflater, container, false)

        val menuHost: MenuHost = requireActivity()
        menuHost.addMenuProvider(menuProvider, viewLifecycleOwner, Lifecycle.State.RESUMED)

        settings = Settings(requireContext())

        doubleTapDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleFullscreenMode()
                return true
            }
        })

        lyricsViewModel.songMeta.observe(viewLifecycleOwner, this::songMetaObserver)
        lyricsViewModel.songWithLyrics.observe(viewLifecycleOwner, this::songWithLyricsObserver)
        lyricsViewModel.songPosition.observe(viewLifecycleOwner, this::setTime)

        binding.refresher.setOnRefreshListener { loadLyricsForCurrentSong() }
        binding.refresher.setColorSchemeResources(R.color.theme_primary, R.color.theme_secondary)

        binding.header.syncedLyricsSwitch.isChecked = settings.getSyncedLyricsByDefault()
        binding.header.syncedLyricsSwitch.setOnCheckedChangeListener { _, checked ->
            showSynced(checked)
        }

        val onLyricsTouch = View.OnTouchListener { _, event ->
            doubleTapDetector.onTouchEvent(event)
            false
        }
        binding.scrollView.setOnTouchListener(onLyricsTouch)
        binding.lyricsView.textLyrics.setOnTouchListener(onLyricsTouch)
        binding.lyricsView.lyricViewX.setOnTouchListener(onLyricsTouch)
        binding.lyricsView.toggleFullscreen.setOnClickListener { toggleFullscreenMode() }
        binding.lyricsView.toggleOverlay.setOnClickListener { toggleOverlayMode() }

        isFullscreenMode = settings.getFullscreenLyricsMode()
        applyFullscreenMode(isFullscreenMode)
        updateOverlayButtonState()

        return binding.root
    }

    override fun onResume() {
        super.onResume()

        lyricsViewModel.setupSongMetaListener()
        setNewState(lyricsViewModel.state)
        updateOverlayButtonState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun toggleFullscreenMode() {
        isFullscreenMode = !isFullscreenMode
        settings.setFullscreenLyricsMode(isFullscreenMode)
        applyFullscreenMode(isFullscreenMode)
    }

    private fun applyFullscreenMode(fullscreen: Boolean) {
        isFullscreenMode = fullscreen
        binding.header.root.setVisible(!fullscreen)
        binding.lyricsView.footer.setVisible(true)
        binding.lyricsView.lyricViewX.setVisible(true)

        binding.lyricsView.source.setVisible(!fullscreen)
        binding.lyricsView.copy.setVisible(!fullscreen)
        binding.lyricsView.share.setVisible(!fullscreen)

        binding.lyricsView.toggleFullscreen.apply {
            setIconResource(if (fullscreen) R.drawable.baseline_close_24 else R.drawable.baseline_open_in_new_24)
            text = getString(if (fullscreen) R.string.toggle_fullscreen_exit else R.string.toggle_fullscreen_enter)
        }
    }

    private fun setNewState(state: UiState) {
        lyricsViewModel.state = state

        binding.refresher.isRefreshing = state.isRefreshing

        // Header
        binding.header.root.setVisible(state.showHeader && !isFullscreenMode)
        binding.header.textSongTitle.text = state.getSongTitle()
        binding.header.textSongArtist.text = state.getSongArtist()
        val hasSyncedLyrics = state.hasSyncedLyrics()
        binding.header.syncedLyricsAvailable.setVisible(hasSyncedLyrics)
        binding.header.syncedLyricsSwitch.isChecked = settings.getSyncedLyricsByDefault() && hasSyncedLyrics

        Picasso.get().load(state.getArtUrl())
            .placeholder(BitmapDrawable(resources, state.getArtBitmap()))
            .into(binding.header.imageSongArt)

        // Error
        binding.errorView.root.setVisible(state.showError)
        state.getErrorText()?.let { binding.errorView.errorText.setText(it) }
        state.getErrorIcon()?.let { binding.errorView.errorIcon.setImageResource(it) }

        // Lyrics
        binding.lyricsView.root.setVisible(state.showText)
        binding.lyricsView.textLyrics.text = state.getLyrics()
        pushOverlayLyricsIfRunning(state.getLyrics())
        state.getSyncedLyrics()?.let { binding.lyricsView.lyricViewX.loadLyric(it) }

        state.getSongProvider()?.let {
            val providerIconRes = Utils.getProviderIconRes(it)
            val providerNameRes = Utils.getProviderNameRes(it)

            binding.lyricsView.source.setText(providerNameRes)
            binding.lyricsView.source.setIconResource(providerIconRes)

            binding.lyricsView.textLyricsProvider.setText(providerNameRes)
            binding.lyricsView.textLyricsProvider.setCompoundDrawablesRelativeWithIntrinsicBounds(
                providerIconRes,
                0,
                0,
                0
            )
        }

        binding.lyricsView.source.setOnClickListener {
            openLink(requireContext(), state.getSourceUrl())
        }
        binding.lyricsView.copy.setOnClickListener {
            copyToClipboard(requireContext(), getString(R.string.lyrics_clipboard_label), state.getLyrics())
        }
        binding.lyricsView.share.setOnClickListener {
            share(requireContext(), state.getSongTitle(), state.getSongArtist(), state.getLyrics())
        }

        // Keep the fullscreen toggle available while restoring screen chrome state.
        applyFullscreenMode(isFullscreenMode)

        showSynced(binding.header.syncedLyricsSwitch.isChecked)

        if (state.startRefresh) loadLyricsForCurrentSong()


        // Apply settings
        lyricsViewModel.autoRefresh = settings.getIsAutoRefreshEnabled()

        val textSize = settings.getTextSize().toFloat()
        val textSizeFocusAdd = 3f

        binding.lyricsView.lyricViewX.apply {
            setNormalTextSize(
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    textSize,
                    resources.displayMetrics
                )
            )
            setCurrentColor(ContextCompat.getColor(requireContext(), R.color.theme_primary))
            setCurrentTextSize(
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    textSize + textSizeFocusAdd,
                    resources.displayMetrics
                )
            )
        }

        binding.lyricsView.textLyrics.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
    }

    private fun loadLyricsForCurrentSong() {
        binding.refresher.isRefreshing = true
        val success = lyricsViewModel.loadLyricsForCurrentSong(requireContext())
        if (!success) binding.refresher.isRefreshing = false
    }

    private fun songMetaObserver(result: Result<SongMeta, LyricsApiException>) {
        when (result) {
            is Failure -> setNewState(NoMusicState())
            is Success -> setNewState(LoadingState(result.value))
        }
    }

    private fun songWithLyricsObserver(result: Result<SongWithLyrics, LyricsApiException>) {
        when (result) {
            is Failure -> setNewState(
                ErrorState(lyricsViewModel.songMeta.value?.valueOrNull(), result.reason)
            )

            is Success -> setNewState(
                TextState(lyricsViewModel.songMeta.value?.valueOrNull(), result.value)
            )
        }
    }

    private fun showSynced(show: Boolean) {
        val synced = show && lyricsViewModel.state.hasSyncedLyrics()

        binding.lyricsView.textLyrics.setVisible(!synced)
        binding.lyricsView.lyricViewX.setVisible(synced)
        lyricsViewModel.setupPositionPolling(synced)
    }

    private fun setTime(time: Long) {
        binding.lyricsView.lyricViewX.updateTime(time)
    }

    private fun toggleOverlayMode() {
        if (isOverlayRunning) {
            stopOverlayIfRunning()
            updateOverlayButtonState()
            return
        }

        if (!AndroidSettings.canDrawOverlays(requireContext())) {
            Toast.makeText(requireContext(), getString(R.string.overlay_permission_required), Toast.LENGTH_LONG).show()
            startActivity(
                Intent(
                    AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${requireContext().packageName}")
                )
            )
            return
        }

        runCatching {
            val overlayIntent = Intent(requireContext(), LyricsOverlayService::class.java).apply {
                action = LyricsOverlayService.ACTION_START
                putExtra(LyricsOverlayService.EXTRA_LYRICS, lyricsViewModel.state.getLyrics())
            }
            requireContext().startService(overlayIntent)
            isOverlayRunning = true
            updateOverlayButtonState()
        }.onFailure {
            Log.e(TAG, "Failed to start overlay service", it)
            Toast.makeText(requireContext(), getString(R.string.overlay_start_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun stopOverlayIfRunning() {
        if (!isOverlayRunning) return

        runCatching {
            val stopIntent = Intent(requireContext(), LyricsOverlayService::class.java).apply {
                action = LyricsOverlayService.ACTION_STOP
            }
            requireContext().startService(stopIntent)
            isOverlayRunning = false
        }.onFailure {
            Log.e(TAG, "Failed to stop overlay service", it)
            isOverlayRunning = false
        }
    }

    private fun pushOverlayLyricsIfRunning(lyrics: String) {
        if (!isOverlayRunning || lyrics.isBlank()) return

        val updateIntent = Intent(requireContext(), LyricsOverlayService::class.java).apply {
            action = LyricsOverlayService.ACTION_UPDATE_LYRICS
            putExtra(LyricsOverlayService.EXTRA_LYRICS, lyrics)
        }
        requireContext().startService(updateIntent)
    }

    private fun updateOverlayButtonState() {
        binding.lyricsView.toggleOverlay.apply {
            setIconResource(if (isOverlayRunning) R.drawable.baseline_close_24 else R.drawable.baseline_drag_handle_24)
            text = getString(if (isOverlayRunning) R.string.toggle_overlay_stop else R.string.toggle_overlay_start)
        }
    }

    companion object {
        private const val TAG = "FastLyricsOverlay"
    }

}
