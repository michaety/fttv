package com.fishtank.tv

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/**
 * Plays what a phone casts to [UpnpRenderer]: a native ExoPlayer in a
 * full-screen view on top of the WebView. Native rather than the page's own
 * <video>, so it neither depends on the site nor runs into the Amazon
 * WebView's broken video overlay (see MainActivity.createOverlayHolder).
 *
 * The renderer calls in from its network threads; everything that touches
 * the player is posted to the main thread, and status() returns a snapshot
 * refreshed twice a second so the renderer never has to wait on it.
 *
 * Created on first cast and released on Stop/BACK, so it holds no decoder
 * while nothing is being cast.
 */
@OptIn(UnstableApi::class)
class CastPlayer(
    private val activity: Activity,
    private val root: FrameLayout,
    /** Sent as Referer/Origin -- CDNs (Bunny) can be locked to the site. */
    private val siteUrl: String,
    private val userAgent: String,
    /** Called when a cast starts, to pause anything playing in the page. */
    private val onCastStart: () -> Unit,
) : UpnpRenderer.Player {

    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var view: PlayerView? = null
    private var volume = 100
    private var muted = false
    @Volatile private var currentUri = ""
    @Volatile private var snapshot = UpnpRenderer.Status("NO_MEDIA_PRESENT", "", 0, 0, 100, false)

    val isActive: Boolean get() = view != null

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            if (player != null) main.postDelayed(this, 500)
        }
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val origin = Uri.parse(siteUrl).let { "${it.scheme}://${it.host}" }
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("Referer" to siteUrl, "Origin" to origin))
        val p = ExoPlayer.Builder(activity)
            .setMediaSourceFactory(DefaultMediaSourceFactory(activity).setDataSourceFactory(http))
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) = refresh()
            override fun onIsPlayingChanged(isPlaying: Boolean) = refresh()
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(activity, "Couldn't play the cast video (${error.errorCodeName})", Toast.LENGTH_LONG).show()
                refresh()
            }
        })
        val v = PlayerView(activity).apply {
            this.player = p
            setBackgroundColor(Color.BLACK)
            useController = true
            controllerShowTimeoutMs = 3000
            setShowNextButton(false)
            setShowPreviousButton(false)
            isFocusable = false
        }
        root.addView(v, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        player = p
        view = v
        onCastStart()
        main.post(ticker)
        return p
    }

    private fun release() {
        main.removeCallbacks(ticker)
        player?.release()
        player = null
        view?.let { root.removeView(it) }
        view = null
    }

    private fun refresh() {
        val p = player
        val state = when {
            p == null -> if (currentUri.isEmpty()) "NO_MEDIA_PRESENT" else "STOPPED"
            p.playbackState == Player.STATE_ENDED -> "STOPPED"
            p.playbackState == Player.STATE_IDLE -> "STOPPED"
            p.playbackState == Player.STATE_BUFFERING -> if (p.playWhenReady) "TRANSITIONING" else "PAUSED_PLAYBACK"
            p.playWhenReady -> "PLAYING"
            else -> "PAUSED_PLAYBACK"
        }
        val duration = p?.duration?.takeIf { it > 0 } ?: 0L
        snapshot = UpnpRenderer.Status(state, currentUri, p?.currentPosition ?: 0L, duration, volume, muted)
    }

    // ---- UpnpRenderer.Player (any thread) ---------------------------------

    override fun load(uri: String, title: String?) {
        currentUri = uri
        main.post {
            val p = ensurePlayer()
            val item = MediaItem.Builder()
                .setUri(uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
                .apply { if (uri.contains("m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8) }
                .build()
            p.setMediaItem(item)
            p.prepare()
            refresh()
        }
    }

    override fun play() = main.post { ensurePlayer().play(); refresh() }.let { }
    override fun pause() = main.post { player?.pause(); refresh() }.let { }
    override fun stop() = main.post { release(); refresh() }.let { }
    override fun seekTo(positionMs: Long) = main.post { player?.seekTo(positionMs); refresh() }.let { }
    override fun setVolume(percent: Int) = main.post {
        volume = percent.coerceIn(0, 100)
        player?.volume = if (muted) 0f else volume / 100f
        refresh()
    }.let { }
    override fun setMuted(muted: Boolean) = main.post {
        this.muted = muted
        player?.volume = if (muted) 0f else volume / 100f
        refresh()
    }.let { }
    override fun status(): UpnpRenderer.Status = snapshot

    // ---- remote (main thread) --------------------------------------------

    /**
     * Remote keys while a cast is on screen: Select / Play-Pause toggle,
     * left/right and FF/RW seek (by [stepSeconds], which grows while held),
     * up/down show the progress bar, BACK stops the cast. Returns true if
     * the key was used.
     */
    fun handleKey(event: KeyEvent, stepSeconds: Int): Boolean {
        val p = player ?: return false
        if (event.action != KeyEvent.ACTION_DOWN) return event.keyCode != KeyEvent.KEYCODE_HOME
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (event.repeatCount == 0) { if (p.playWhenReady) p.pause() else p.play() }
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND ->
                p.seekTo((p.currentPosition - stepSeconds * 1000L).coerceAtLeast(0))
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ->
                p.seekTo(p.currentPosition + stepSeconds * 1000L)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU -> Unit
            KeyEvent.KEYCODE_BACK -> { if (event.repeatCount == 0) { release(); refresh() }; return true }
            else -> return false
        }
        view?.showController()
        refresh()
        return true
    }

    /** App going to the background: stop, and give the decoder back. */
    fun stopNow() { release(); refresh() }
}
