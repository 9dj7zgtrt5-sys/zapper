package dev.yash.zapper.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/** ExoPlayer wrapper tuned for IPTV: per-stream user agent, auto-retry, HLS fallback. */
class TvPlayer(private val ctx: Context, view: PlayerView, private val cb: Callback) {
    interface Callback {
        /** null clears the message */
        fun onPlayerMessage(msg: String?)
        fun onEnded()
    }

    private val handler = Handler(Looper.getMainLooper())
    val exo: ExoPlayer = ExoPlayer.Builder(
        ctx,
        DefaultRenderersFactory(ctx).setEnableDecoderFallback(true),
    ).build()

    private var url: String? = null
    private var ua: String = ""
    private var live = true
    private var forceHls = false
    private var retries = 0

    init {
        view.player = exo
        view.useController = false
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> { retries = 0; cb.onPlayerMessage(null) }
                    Player.STATE_ENDED -> if (live) retry("Stream ended") else cb.onEnded()
                    else -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                when {
                    error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                        exo.seekToDefaultPosition()
                        exo.prepare()
                    }
                    !forceHls && (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                        error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED) -> {
                        forceHls = true
                        start(0)
                    }
                    else -> retry(error.errorCodeName)
                }
            }
        })
    }

    private fun retry(reason: String) {
        if (retries >= MAX_RETRIES) {
            cb.onPlayerMessage("This channel isn't working right now.\nPress ▲ or ▼ to watch another channel.")
            return
        }
        retries++
        cb.onPlayerMessage("Reconnecting…")
        val pos = if (live) 0L else exo.currentPosition
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ start(pos) }, 1500L * retries)
    }

    fun play(url: String, userAgent: String, live: Boolean, startPositionMs: Long = 0) {
        handler.removeCallbacksAndMessages(null)
        this.url = url
        this.ua = userAgent
        this.live = live
        this.retries = 0
        this.forceHls = url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
        cb.onPlayerMessage(null)
        start(startPositionMs)
    }

    private fun start(positionMs: Long) {
        val u = url ?: return
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(20_000)
        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, http))
        val item = MediaItem.Builder().setUri(u).apply {
            if (forceHls) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        val source = factory.createMediaSource(item)
        if (live || positionMs <= 0) exo.setMediaSource(source) else exo.setMediaSource(source, positionMs)
        exo.prepare()
        exo.playWhenReady = true
    }

    /** Re-starts the current stream (e.g. after returning from background). */
    fun resume() {
        if (url != null) start(if (live) 0 else exo.currentPosition)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        exo.stop()
    }

    fun seekBy(ms: Long) {
        if (live) return
        val dur = exo.duration
        var p = exo.currentPosition + ms
        if (dur > 0) p = p.coerceAtMost(dur - 1000)
        exo.seekTo(p.coerceAtLeast(0))
    }

    fun togglePause() {
        exo.playWhenReady = !exo.playWhenReady
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        exo.release()
    }

    companion object {
        private const val MAX_RETRIES = 5
    }
}
