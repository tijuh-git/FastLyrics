package io.github.teccheck.fastlyrics.ui.fastlyrics

import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.squareup.picasso.Picasso
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
import io.github.teccheck.fastlyrics.utils.Utils
import io.github.teccheck.fastlyrics.utils.Utils.copyToClipboard
import io.github.teccheck.fastlyrics.utils.Utils.openLink
import io.github.teccheck.fastlyrics.utils.Utils.setVisible
import io.github.teccheck.fastlyrics.utils.Utils.share

import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import io.github.teccheck.fastlyrics.MainActivity

class FastLyricsFragment : Fragment() {

    private lateinit var lyricsViewModel: FastLyricsViewModel
    private var _binding: FragmentFastLyricsBinding? = null

    // This property is only valid between onCreateView and onDestroyView.
    private val binding get() = _binding!!

    private lateinit var settings: Settings

    private var toolbarTitle: TextView? = null
    private var toolbarArtist: TextView? = null
    private var toolbarSyncSwitch: SwitchCompat? = null
    private var toolbarSyncContainer: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu, inflater: android.view.MenuInflater) {
        inflater.inflate(R.menu.menu_main, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        val state = lyricsViewModel.state
        return when (item.itemId) {
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
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        lyricsViewModel = ViewModelProvider(this)[FastLyricsViewModel::class.java]
        _binding = FragmentFastLyricsBinding.inflate(inflater, container, false)

        settings = Settings(requireContext())

        lyricsViewModel.songMeta.observe(viewLifecycleOwner, this::songMetaObserver)
        lyricsViewModel.songWithLyrics.observe(viewLifecycleOwner, this::songWithLyricsObserver)
        lyricsViewModel.songPosition.observe(viewLifecycleOwner, this::setTime)

        binding.refresher.setOnRefreshListener { loadLyricsForCurrentSong() }
        binding.refresher.setColorSchemeResources(R.color.theme_primary, R.color.theme_secondary)

        setupToolbarViews()

        return binding.root
    }

    private fun setupToolbarViews() {
        val activity = requireActivity()
        toolbarTitle = activity.findViewById(R.id.toolbar_title)
        toolbarArtist = activity.findViewById(R.id.toolbar_artist)
        toolbarSyncSwitch = activity.findViewById(R.id.toolbar_sync_switch)
        toolbarSyncContainer = activity.findViewById(R.id.toolbar_sync_container)

        toolbarSyncSwitch?.isChecked = settings.getSyncedLyricsByDefault()
        toolbarSyncSwitch?.setOnCheckedChangeListener { _, checked ->
            showSynced(checked)
        }

        toolbarTitle?.setOnClickListener {
            openLink(requireContext(), lyricsViewModel.state.getSourceUrl())
        }
        toolbarArtist?.setOnClickListener {
            openLink(requireContext(), lyricsViewModel.state.getSourceUrl())
        }
    }

    override fun onResume() {
        super.onResume()

        lyricsViewModel.setupSongMetaListener()
        setNewState(lyricsViewModel.state)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        toolbarTitle = null
        toolbarArtist = null
        toolbarSyncSwitch = null
        toolbarSyncContainer = null
    }

    private fun setNewState(state: UiState) {
        lyricsViewModel.state = state

        binding.refresher.isRefreshing = state.isRefreshing

        // Toolbar
        toolbarTitle?.text = state.getSongTitle()
        toolbarArtist?.text = state.getSongArtist()
        toolbarSyncContainer?.setVisible(state.hasSyncedLyrics())

        // Error
        binding.errorView.root.setVisible(state.showError)
        state.getErrorText()?.let { binding.errorView.errorText.setText(it) }
        state.getErrorIcon()?.let { binding.errorView.errorIcon.setImageResource(it) }

        // Lyrics
        binding.lyricsView.root.setVisible(state.showText)
        binding.lyricsView.textLyrics.text = state.getLyrics()
        state.getSyncedLyrics()?.let { binding.lyricsView.lyricViewX.loadLyric(it) }

        showSynced(toolbarSyncSwitch?.isChecked ?: false)

        if (state.startRefresh) loadLyricsForCurrentSong()

        // Apply settings
        lyricsViewModel.autoRefresh = settings.getIsAutoRefreshEnabled()

        val textSize = settings.getTextSize().toFloat()
        val textSizeFocusAdd = 2f

        binding.lyricsView.lyricViewX.apply {
            setNormalTextSize(
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    textSize,
                    resources.displayMetrics
                )
            )
            setCurrentColor(resources.getColor(R.color.theme_primary))
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

    companion object {
        private const val TAG = "FastLyricsFragment"
    }
}
