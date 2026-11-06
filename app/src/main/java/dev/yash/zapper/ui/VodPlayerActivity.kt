package dev.yash.zapper.ui

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.media3.ui.PlayerView
import dev.yash.zapper.R
import dev.yash.zapper.data.PlayRequest
import dev.yash.zapper.data.Repo
import dev.yash.zapper.data.ResumeStore
import dev.yash.zapper.data.StalkerClient
import dev.yash.zapper.player.TvPlayer

/** Full-screen movie/episode player with a progress bar, simple remote controls and resume. */
class VodPlayerActivity : Activity(), TvPlayer.Callback {
    private lateinit var req: PlayRequest
    private lateinit var player: TvPlayer
    private lateinit var view: PlayerView
    private lateinit var title: TextView
    private lateinit var msg: TextView
    private val main get() = Repo.main

    private val saver = object : Runnable {
        override fun run() {
            save()
            main.postDelayed(this, 10_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(this)
        val r = VodSession.request
        if (r == null) { finish(); return }
        req = r
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_vod_player)
        Fonts.apply(window.decorView)
        view = findViewById(R.id.vodPlayerView)
        title = findViewById(R.id.vodPlayerTitle)
        msg = findViewById(R.id.vodPlayerMsg)
        title.text = listOfNotNull(req.title, req.subtitle).joinToString("\n")

        player = TvPlayer(this, view, this)
        view.useController = true
        view.controllerShowTimeoutMs = 4000
        view.setShowNextButton(false)
        view.setShowPreviousButton(false)
        view.setShowSubtitleButton(true)
        view.setControllerVisibilityListener(object : PlayerView.ControllerVisibilityListener {
            override fun onVisibilityChanged(visibility: Int) {
                title.visibility = visibility
            }
        })

        val ua = if (req.direct) Repo.userAgent else StalkerClient.UA
        val request = req
        player.play(request.cmd, ua, live = false, startPositionMs = request.startPositionMs, resolver = { Repo.vodLink(request) })
        view.showController()
    }

    override fun onStart() {
        super.onStart()
        main.postDelayed(saver, 10_000)
    }

    override fun onStop() {
        super.onStop()
        main.removeCallbacks(saver)
        if (::player.isInitialized) {
            save()
            player.stop()
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::player.isInitialized) player.release()
    }

    private fun save() {
        val pos = player.exo.currentPosition
        val dur = player.exo.duration
        if (pos > 0) ResumeStore.update(req, pos, if (dur > 0) dur else 0)
    }

    override fun onPlayerMessage(msg: String?) {
        if (msg == null) this.msg.visibility = View.GONE
        else {
            this.msg.text = msg.replace("Press ▲ or ▼ to watch another channel.", "Press Back to choose something else.")
                .replace("This channel isn't", "This video isn't")
            this.msg.visibility = View.VISIBLE
        }
    }

    override fun onEnded() {
        val dur = player.exo.duration
        if (dur > 0) ResumeStore.update(req, dur, dur)
        finish()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) {
            return when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_REWIND,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MENU -> true
                else -> super.dispatchKeyEvent(e)
            }
        }
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (e.repeatCount == 0) player.togglePause()
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> player.seekBy(-10_000)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> player.seekBy(30_000)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU -> Unit
            else -> return super.dispatchKeyEvent(e)
        }
        view.showController()
        return true
    }
}
