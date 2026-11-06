package dev.yash.zapper.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.ui.PlayerView
import dev.yash.zapper.R
import dev.yash.zapper.WakeService
import dev.yash.zapper.data.Catchup
import dev.yash.zapper.data.Channel
import dev.yash.zapper.data.ConfigServer
import dev.yash.zapper.data.Epg
import dev.yash.zapper.data.Group
import dev.yash.zapper.data.Programme
import dev.yash.zapper.data.Repo
import dev.yash.zapper.data.Vpn
import dev.yash.zapper.player.TvPlayer
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity(), TvPlayer.Callback, GuideView.Listener {
    private enum class Mode { PLAYER, LIST, GUIDE }
    private enum class Card { NONE, WELCOME, LOADING, VPN_WAIT, VPN_OFF, ERROR }

    private lateinit var player: TvPlayer
    private lateinit var listPanel: View
    private lateinit var groupsList: ListView
    private lateinit var channelsList: ListView
    private lateinit var guide: GuideView
    private lateinit var help: View
    private lateinit var infoBar: View
    private lateinit var infoNum: TextView
    private lateinit var infoName: TextView
    private lateinit var infoNow: TextView
    private lateinit var infoNext: TextView
    private lateinit var infoClock: TextView
    private lateinit var infoLogo: ImageView
    private lateinit var infoProgress: ProgressBar
    private lateinit var playerMsg: TextView
    private lateinit var card: View
    private lateinit var cardTitle: TextView
    private lateinit var cardBody: TextView
    private lateinit var cardButton: TextView
    private lateinit var cardSpinner: View

    private val groupAdapter = GroupAdapter()
    private val channelAdapter = ChannelAdapter()
    private lateinit var timeFmt: DateFormat
    private val main get() = Repo.main

    private var mode = Mode.PLAYER
    private var cardKind = Card.NONE
    private var cardAction: (() -> Unit)? = null
    private var groups: List<Group> = emptyList()
    private var browseGroup = 0
    private var playGroup = 0
    private var current: Channel? = null
    private var previous: Channel? = null
    /** Non-null while playing VOD or catch-up (seekable content). */
    private var vodTitle: String? = null

    private var loadedConfig = -1
    private var loadedDisplay = -1
    private var loading = false
    private var needsResume = false
    private var started = false
    private var vpnBlocked = false
    private var vpnWaitSince = 0L
    private var menuDownAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(this)
        Logos.init(this)
        ConfigServer.start(this)
        if (Repo.autoStart) WakeService.start(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        timeFmt = android.text.format.DateFormat.getTimeFormat(this)

        val pv = findViewById<PlayerView>(R.id.playerView)
        pv.isFocusable = false
        pv.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        player = TvPlayer(this, pv, this)

        listPanel = findViewById(R.id.listPanel)
        groupsList = findViewById(R.id.groupsList)
        channelsList = findViewById(R.id.channelsList)
        guide = findViewById(R.id.guide)
        help = findViewById(R.id.help)
        infoBar = findViewById(R.id.infoBar)
        infoNum = findViewById(R.id.infoNum)
        infoName = findViewById(R.id.infoName)
        infoNow = findViewById(R.id.infoNow)
        infoNext = findViewById(R.id.infoNext)
        infoClock = findViewById(R.id.infoClock)
        infoLogo = findViewById(R.id.infoLogo)
        infoProgress = findViewById(R.id.infoProgress)
        playerMsg = findViewById(R.id.playerMsg)
        card = findViewById(R.id.card)
        cardTitle = findViewById(R.id.cardTitle)
        cardBody = findViewById(R.id.cardBody)
        cardButton = findViewById(R.id.cardButton)
        cardSpinner = findViewById(R.id.cardSpinner)

        guide.listener = this
        groupsList.adapter = groupAdapter
        channelsList.adapter = channelAdapter

        groupsList.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                main.removeCallbacks(applyGroup)
                pendingGroup = position
                main.postDelayed(applyGroup, 250)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        groupsList.setOnItemClickListener { _, _, position, _ ->
            val kind = groups.getOrNull(position)?.kind
            if (kind == Group.Kind.MOVIES || kind == Group.Kind.SERIES) {
                hideOverlays()
                startActivity(Intent(this, VodActivity::class.java).putExtra(VodActivity.EXTRA_SERIES, kind == Group.Kind.SERIES))
                return@setOnItemClickListener
            }
            pendingGroup = position
            applyGroup.run()
            channelsList.requestFocus()
        }
        channelsList.setOnItemClickListener { _, _, position, _ ->
            val ch = channelAdapter.items.getOrNull(position) ?: return@setOnItemClickListener
            hideOverlays()
            playChannel(ch, browseGroup)
        }
    }

    private var pendingGroup = 0
    private val applyGroup = Runnable {
        if (pendingGroup != browseGroup || channelAdapter.items !== groups.getOrNull(pendingGroup)?.channels) {
            browseGroup = pendingGroup
            channelAdapter.items = groups.getOrNull(browseGroup)?.channels ?: emptyList()
            updateEmptyHint()
            val idx = channelAdapter.items.indexOfFirst { it.url == current?.url }
            channelsList.setSelection(if (idx >= 0) idx else 0)
        }
    }

    private fun updateEmptyHint() {
        val g = groups.getOrNull(browseGroup)
        val empty = findViewById<TextView>(R.id.channelsEmpty)
        val text = when {
            g == null -> ""
            g.kind == Group.Kind.MOVIES -> "🎬\n\nPress OK to open Movies"
            g.kind == Group.Kind.SERIES -> "📺\n\nPress OK to open Series"
            g.kind == Group.Kind.FAVORITES && g.channels.isEmpty() -> "No favorites yet.\n\nPick a channel in another category\nand press ☰ to add it here."
            g.channels.isEmpty() -> "No channels here."
            else -> ""
        }
        empty.text = text
        empty.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun openVodFor(g: Group?): Boolean {
        if (g?.kind != Group.Kind.MOVIES && g?.kind != Group.Kind.SERIES) return false
        hideOverlays()
        startActivity(Intent(this, VodActivity::class.java).putExtra(VodActivity.EXTRA_SERIES, g.kind == Group.Kind.SERIES))
        return true
    }

    // ---------------- lifecycle ----------------

    override fun onStart() {
        super.onStart()
        if (started) needsResume = true
        started = true
        main.post(vpnWatch)
        main.postDelayed(keepAlive, 120_000)
    }

    /** Portal sessions time out unless the "box" checks in now and then. */
    private val keepAlive = object : Runnable {
        override fun run() {
            if (Repo.isPortal) Repo.linkExec.execute { Repo.keepAlive() }
            main.postDelayed(this, 120_000)
        }
    }

    override fun onResume() {
        super.onResume()
        ConfigServer.listener = { onSettingsChanged() }
        onSettingsChanged()
    }

    override fun onPause() {
        super.onPause()
        ConfigServer.listener = null
    }

    override fun onStop() {
        super.onStop()
        main.removeCallbacks(vpnWatch)
        main.removeCallbacks(keepAlive)
        player.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
        main.removeCallbacksAndMessages(null)
    }

    private fun onSettingsChanged() {
        if (Repo.autoStart) WakeService.start(this) else WakeService.stop(this)
        checkVpn()
        proceed()
    }

    /** Moves the app towards "playing TV" as far as current conditions allow. */
    private fun proceed() {
        if (!Repo.isConfigured) { showWelcome(); return }
        if (cardKind == Card.WELCOME) hideCard()
        if (vpnBlocked || loading) return
        when {
            loadedConfig != Repo.configVersion -> {
                val force = loadedConfig != -1
                loadedConfig = Repo.configVersion
                loadedDisplay = Repo.displayVersion
                reload(force)
            }
            loadedDisplay != Repo.displayVersion -> {
                loadedDisplay = Repo.displayVersion
                applyDisplaySettings()
            }
            needsResume -> {
                needsResume = false
                if (vodTitle != null) player.resume() else current?.let { playChannel(it, playGroup) }
            }
        }
    }

    // ---------------- VPN guard ----------------

    private val vpnWatch = object : Runnable {
        override fun run() {
            checkVpn()
            main.postDelayed(this, if (vpnBlocked) 1500 else 4000)
        }
    }

    private fun vpnOk(): Boolean = !Repo.requireVpn || Vpn.isActive(this) || Vpn.apps(this).isEmpty()

    private fun checkVpn() {
        if (!Repo.isConfigured) return
        val now = System.currentTimeMillis()
        if (vpnOk()) {
            if (vpnBlocked) {
                vpnBlocked = false
                hideCard()
                proceed()
            }
            return
        }
        if (!vpnBlocked) {
            vpnBlocked = true
            vpnWaitSince = now
            if (current != null || vodTitle != null) needsResume = true
            player.stop()
            hideOverlays()
            onPlayerMessage(null)
        }
        if (now - vpnWaitSince < 20_000) {
            if (cardKind != Card.VPN_WAIT) showCard(Card.VPN_WAIT, "Connecting securely…", "Waiting for the VPN to connect.", spinner = true)
        } else if (cardKind != Card.VPN_OFF) {
            val app = Vpn.chosen(this)
            val name = app?.label ?: "the VPN app"
            showCard(
                Card.VPN_OFF, "The VPN is off",
                "1.  Press OK to open $name\n2.  Choose Connect\n3.  Press Back to come back to TV",
                button = "Open $name",
            ) { app?.let { a -> Vpn.launchIntent(this, a.pkg)?.let { startActivity(it) } } }
        }
    }

    // ---------------- loading ----------------

    private fun reload(force: Boolean) {
        loading = true
        if (current == null) showCard(Card.LOADING, "Loading channels…", "This can take a moment the first time.", spinner = true)
        Repo.io.execute {
            val err = Repo.loadPlaylist(force)
            main.post { onPlaylistLoaded(err) }
            if (Repo.channels.isNotEmpty()) {
                val epgErr = Repo.loadEpg(force)
                main.post { onEpgLoaded(epgErr) }
            }
        }
    }

    private fun onPlaylistLoaded(err: String?) {
        loading = false
        if (Repo.channels.isEmpty()) {
            showCard(
                Card.ERROR, "Couldn't load the channels",
                (err ?: "The playlist is empty.").let { if (it.contains("press OK")) it else it + "\n\nCheck the internet connection, then press OK to try again." },
                button = "Try again",
            ) { hideCard(); loadedConfig = -1; proceed() }
            return
        }
        if (cardKind == Card.LOADING || cardKind == Card.ERROR) hideCard()
        groups = Repo.groups()
        groupAdapter.items = groups
        val lastUrl = current?.url ?: Repo.lastChannelUrl
        var gi = groups.indexOfFirst { it.title == Repo.lastGroup }
        if (gi < 0 || groups[gi].channels.none { it.url == lastUrl }) {
            gi = if (groups[0].channels.any { it.url == lastUrl }) 0 else 1
        }
        val ch = groups.getOrNull(gi)?.channels?.firstOrNull { it.url == lastUrl }
            ?: groups[0].channels.firstOrNull()?.also { gi = 0 }
            ?: groups.getOrNull(1)?.channels?.firstOrNull()?.also { gi = 1 }
        browseGroup = gi
        if (ch != null && vodTitle == null && !vpnBlocked) playChannel(ch, gi)
        if (!Repo.helpShown) {
            Repo.helpShown = true
            main.postDelayed({ if (cardKind == Card.NONE) showHelp() }, 1500)
        }
        proceed()
    }

    private fun onEpgLoaded(err: String?) {
        if (err != null && Repo.epg.isEmpty()) toast("TV guide unavailable right now")
        channelAdapter.notifyDataSetChanged()
        if (mode == Mode.GUIDE) guide.invalidate()
        if (infoBar.visibility == View.VISIBLE) updateInfo()
    }

    private fun applyDisplaySettings() {
        groups = Repo.groups()
        groupAdapter.items = groups
        playGroup = playGroup.coerceAtMost(groups.size - 1)
        browseGroup = browseGroup.coerceAtMost(groups.size - 1)
        channelAdapter.items = groups.getOrNull(browseGroup)?.channels ?: emptyList()
        if (mode == Mode.GUIDE) hideOverlays()
    }

    // ---------------- playback ----------------

    private fun playChannel(ch: Channel, groupIndex: Int) {
        if (ch.isVod) {
            playVod(ch.url, ch.name, ch.userAgent)
            return
        }
        if (current != null && current?.url != ch.url) previous = current
        current = ch
        playGroup = groupIndex
        vodTitle = null
        needsResume = false
        channelAdapter.playingUrl = ch.url
        if (vpnBlocked) return
        val resolver: (() -> String?)? = if (ch.cmd != null) ({ Repo.resolveStream(ch) }) else null
        player.play(ch.url, ch.userAgent ?: Repo.userAgent, live = true, resolver = resolver)
        Repo.lastChannelUrl = ch.url
        Repo.lastGroup = groups.getOrNull(groupIndex)?.title
        showInfo()
    }

    private fun playVod(url: String, title: String, ua: String?) {
        vodTitle = title
        needsResume = false
        player.play(url, ua ?: Repo.userAgent, live = false)
        showInfo()
    }

    private fun zap(delta: Int) {
        val list = groups.getOrNull(playGroup)?.channels?.filter { !it.isVod } ?: return
        if (list.isEmpty()) return
        val idx = list.indexOfFirst { it.url == current?.url }
        val d = if (Repo.reverseZap) -delta else delta
        val next = list[((if (idx < 0) 0 else idx + d) % list.size + list.size) % list.size]
        playChannel(next, playGroup)
    }

    override fun onPlayerMessage(msg: String?) {
        if (msg == null) playerMsg.visibility = View.GONE
        else { playerMsg.text = msg; playerMsg.visibility = View.VISIBLE }
    }

    override fun onEnded() {
        toast("Finished")
        exitVod()
    }

    private fun exitVod() {
        vodTitle = null
        current?.let { playChannel(it, playGroup) } ?: player.stop()
    }

    // ---------------- info banner ----------------

    private val hideInfo = Runnable { infoBar.visibility = View.GONE }
    private val tickInfo = object : Runnable {
        override fun run() {
            if (infoBar.visibility == View.VISIBLE) {
                updateInfo()
                main.postDelayed(this, 1000)
            }
        }
    }

    private fun showInfo(timeoutMs: Long = 5000) {
        if (mode != Mode.PLAYER) return
        infoBar.visibility = View.VISIBLE
        updateInfo()
        main.removeCallbacks(hideInfo)
        main.removeCallbacks(tickInfo)
        main.postDelayed(tickInfo, 1000)
        if (timeoutMs > 0) main.postDelayed(hideInfo, timeoutMs)
    }

    private fun fmt(t: Long) = timeFmt.format(Date(t))

    private fun fmtDur(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    private fun updateInfo() {
        val now = System.currentTimeMillis()
        infoClock.text = fmt(now)
        val vt = vodTitle
        if (vt != null) {
            infoNum.text = "▶"
            infoName.text = vt
            infoLogo.setImageBitmap(null)
            val pos = player.exo.currentPosition
            val dur = player.exo.duration
            val paused = !player.exo.playWhenReady
            infoNow.text = (if (paused) "Paused   " else "") + fmtDur(pos) + (if (dur > 0) " / " + fmtDur(dur) else "")
            infoNext.text = "◀ ▶  Skip      OK  Pause      Back  Return to live TV"
            infoProgress.progress = if (dur > 0) ((pos * 1000) / dur).toInt() else 0
            return
        }
        val ch = current ?: return
        infoNum.text = ch.number.toString()
        infoName.text = ch.name
        Logos.into(infoLogo, ch.logo)
        val list = Repo.programmes(ch)
        val p = Epg.at(list, now)
        if (p != null) {
            infoNow.text = "Now:  ${p.title}   (${fmt(p.start)} – ${fmt(p.stop)})"
            infoProgress.progress = (((now - p.start) * 1000) / (p.stop - p.start)).toInt()
            infoProgress.visibility = View.VISIBLE
        } else {
            infoNow.text = groups.getOrNull(playGroup)?.title ?: ""
            infoProgress.visibility = View.INVISIBLE
        }
        val n = Epg.next(list, now)
        infoNext.text = if (n != null) "Next:  ${n.title}  at ${fmt(n.start)}" else "OK  All channels      ▶  TV guide      ☰  Help"
    }

    // ---------------- overlays ----------------

    private val idleClose = Runnable { if (mode != Mode.PLAYER) hideOverlays() }

    private fun bumpIdle() {
        main.removeCallbacks(idleClose)
        if (mode != Mode.PLAYER) main.postDelayed(idleClose, if (mode == Mode.GUIDE) 90_000 else 45_000)
    }

    private fun openList() {
        if (groups.isEmpty()) return
        mode = Mode.LIST
        infoBar.visibility = View.GONE
        guide.visibility = View.GONE
        listPanel.visibility = View.VISIBLE
        val gi = if (vodTitle == null) playGroup else browseGroup
        browseGroup = gi
        pendingGroup = gi
        groupAdapter.items = groups
        channelAdapter.items = groups.getOrNull(gi)?.channels ?: emptyList()
        updateEmptyHint()
        groupsList.setSelection(gi)
        val idx = channelAdapter.items.indexOfFirst { it.url == current?.url }
        channelsList.setSelection(idx.coerceAtLeast(0))
        channelsList.requestFocus()
        bumpIdle()
    }

    private fun openGuide() {
        val g = groups.getOrNull(if (mode == Mode.LIST) browseGroup else playGroup) ?: return
        val live = g.channels.filter { !it.isVod }
        if (live.isEmpty()) { toast("No TV guide for this category"); return }
        mode = Mode.GUIDE
        infoBar.visibility = View.GONE
        listPanel.visibility = View.GONE
        guide.visibility = View.VISIBLE
        guide.show(live, current)
        bumpIdle()
    }

    private fun hideOverlays() {
        mode = Mode.PLAYER
        listPanel.visibility = View.GONE
        guide.visibility = View.GONE
        main.removeCallbacks(idleClose)
    }

    private fun showHelp() {
        hideOverlays()
        infoBar.visibility = View.GONE
        findViewById<TextView>(R.id.helpMac).text = "This TV's MAC address:  ${Repo.mac}" +
            (ConfigServer.address()?.let { "      Settings from a phone:  $it" } ?: "")
        help.visibility = View.VISIBLE
    }

    private fun showWelcome() {
        val addr = ConfigServer.address()
        showCard(
            Card.WELCOME, "Welcome to Zapper",
            "This TV's MAC address:  ${Repo.mac}\n(give this to your IPTV provider)\n\n" +
                (if (addr != null) "To add channels, open this address on a phone\nconnected to the same Wi-Fi:\n$addr\n\nor press OK to type it in with the remote."
                else "Connect the TV to Wi-Fi, then press OK to set up."),
            button = "Set up here",
        ) { startActivity(Intent(this, SetupActivity::class.java)) }
    }

    private fun showCard(kind: Card, title: String, body: String, button: String? = null, spinner: Boolean = false, action: (() -> Unit)? = null) {
        cardKind = kind
        cardAction = action
        cardTitle.text = title
        val withHint = if (kind == Card.ERROR || kind == Card.VPN_OFF || kind == Card.WELCOME)
            "$body\n\nPress Back for settings  ·  Home to leave" else body
        cardBody.text = withHint
        cardButton.text = button ?: ""
        cardButton.visibility = if (button != null) View.VISIBLE else View.GONE
        cardSpinner.visibility = if (spinner) View.VISIBLE else View.GONE
        card.visibility = View.VISIBLE
    }

    private fun hideCard() {
        cardKind = Card.NONE
        cardAction = null
        card.visibility = View.GONE
    }

    private fun toggleFavorite(ch: Channel) {
        val added = Repo.toggleFavorite(ch)
        toast(if (added) "★ Added ${ch.name} to Favorites" else "Removed ${ch.name} from Favorites")
        groups = Repo.groups()
        groupAdapter.items = groups
        if (mode == Mode.LIST) channelAdapter.items = groups.getOrNull(browseGroup)?.channels ?: emptyList()
        channelAdapter.notifyDataSetChanged()
        guide.invalidate()
    }

    // ---------------- guide callbacks ----------------

    override fun onGuidePick(ch: Channel, p: Programme?) {
        val now = System.currentTimeMillis()
        when {
            p == null || (p.start <= now && now < p.stop) -> {
                val gi = if (groups.getOrNull(playGroup)?.channels?.any { it.url == ch.url } == true) playGroup else 1
                hideOverlays()
                playChannel(ch, gi)
            }
            p.start > now -> toast("This starts at ${fmt(p.start)}")
            Catchup.available(ch, p, now) -> {
                val url = Catchup.build(ch, p.start, p.stop, now, Repo.tzOffsetMs)
                if (url == null) { toast("Can't replay this programme"); return }
                if (current?.url != ch.url) { if (current != null) previous = current; current = ch }
                hideOverlays()
                playVod(url, "${ch.name} · ${p.title}", ch.userAgent)
            }
            else -> toast("This programme has finished and can't be replayed")
        }
    }

    override fun onGuideFavorite(ch: Channel) = toggleFavorite(ch)

    // ---------------- keys ----------------

    private fun isPassThrough(code: Int) = code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN ||
        code == KeyEvent.KEYCODE_VOLUME_MUTE || code == KeyEvent.KEYCODE_HOME || code == KeyEvent.KEYCODE_POWER

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        if (isPassThrough(code)) return super.dispatchKeyEvent(e)
        bumpIdle()

        // Help screen: any key closes it.
        if (help.visibility == View.VISIBLE) {
            if (e.action == KeyEvent.ACTION_DOWN) help.visibility = View.GONE
            return true
        }

        // ☰ while watching: tap = help, hold 3 s = settings.
        if (code == KeyEvent.KEYCODE_MENU && (mode == Mode.PLAYER || cardKind != Card.NONE)) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) menuDownAt = e.eventTime
            if (e.action == KeyEvent.ACTION_UP) {
                if (e.eventTime - menuDownAt >= 3000) startActivity(Intent(this, SetupActivity::class.java))
                else if (cardKind == Card.NONE) showHelp()
            }
            return true
        }

        // Message card: OK runs its button, Back opens settings (so nobody gets stuck on it).
        if (cardKind != Card.NONE) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                when (code) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> cardAction?.invoke()
                    KeyEvent.KEYCODE_BACK -> if (cardKind != Card.LOADING && cardKind != Card.VPN_WAIT)
                        startActivity(Intent(this, SetupActivity::class.java))
                }
            }
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (mode) {
            Mode.LIST -> {
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && groupsList.hasFocus() &&
                    openVodFor(groups.getOrNull(groupsList.selectedItemPosition))) return true
                if (keyCode == KeyEvent.KEYCODE_MENU) {
                    if (event.repeatCount == 0 && channelsList.hasFocus()) {
                        (channelsList.selectedItem as? Channel)?.let { if (!it.isVod) toggleFavorite(it) }
                    }
                    return true
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && channelsList.hasFocus()) { openGuide(); return true }
            }
            Mode.GUIDE -> Unit
            Mode.PLAYER -> if (handlePlayerKey(keyCode)) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun handlePlayerKey(keyCode: Int): Boolean {
        if (vodTitle != null) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> player.seekBy(-10_000)
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> player.seekBy(30_000)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> player.togglePause()
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> Unit
                else -> return false
            }
            showInfo(timeoutMs = if (player.exo.playWhenReady) 4000 else 0)
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> zap(1)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> zap(-1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> openList()
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_GUIDE -> openGuide()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_LAST_CHANNEL -> {
                previous?.let { prev ->
                    val gi = if (groups.getOrNull(playGroup)?.channels?.any { it.url == prev.url } == true) playGroup else 1
                    playChannel(prev, gi)
                } ?: showInfo()
            }
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> showInfo()
            else -> return false
        }
        return true
    }

    /** Back never leaves the app (use Home for that) — it only closes things. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            mode != Mode.PLAYER -> hideOverlays()
            vodTitle != null -> exitVod()
            infoBar.visibility == View.VISIBLE -> infoBar.visibility = View.GONE
            else -> showInfo()
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
