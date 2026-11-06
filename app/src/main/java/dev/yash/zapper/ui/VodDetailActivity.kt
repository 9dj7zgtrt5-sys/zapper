package dev.yash.zapper.ui

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import dev.yash.zapper.R
import dev.yash.zapper.data.Episode
import dev.yash.zapper.data.PlayRequest
import dev.yash.zapper.data.Repo
import dev.yash.zapper.data.ResumeStore
import dev.yash.zapper.data.Season
import dev.yash.zapper.data.VodItem
import dev.yash.zapper.data.VodSource

/** Movie / series details: poster, info, Play/Resume, or seasons + episodes. */
class VodDetailActivity : Activity() {
    private lateinit var item: VodItem
    private lateinit var source: VodSource
    private lateinit var playButton: Button
    private lateinit var restartButton: Button
    private lateinit var episodesList: ListView
    private lateinit var loading: View
    private lateinit var hint: TextView
    private var seasons: List<Season> = emptyList()
    private var episodes: List<Episode> = emptyList()
    private var seasonIdx = -1
    private var generation = 0
    private val main get() = Repo.main

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(this)
        val it = VodSession.item
        val src = VodSession.source
        if (it == null || src == null) { finish(); return }
        item = it
        source = src
        setContentView(R.layout.activity_vod_detail)
        Fonts.apply(window.decorView)

        Logos.into(findViewById<ImageView>(R.id.detailPoster), item.poster, Logos.POSTER_PX)
        findViewById<TextView>(R.id.detailTitle).text = item.title
        val meta = listOfNotNull(
            item.year,
            item.rating?.let { "★ $it" },
            if (item.minutes > 0) formatMinutes(item.minutes) else null,
            item.genre,
        ).joinToString("      ")
        findViewById<TextView>(R.id.detailMeta).text = meta
        val desc = findViewById<TextView>(R.id.detailDesc)
        desc.text = item.description ?: ""
        desc.visibility = if (item.description.isNullOrBlank()) View.GONE else View.VISIBLE

        playButton = findViewById(R.id.playButton)
        restartButton = findViewById(R.id.restartButton)
        episodesList = findViewById(R.id.episodesList)
        loading = findViewById(R.id.detailLoading)
        hint = findViewById(R.id.detailHint)

        if (item.isSeries) setUpSeries(desc) else setUpMovie()
    }

    override fun onResume() {
        super.onResume()
        if (!::item.isInitialized) return
        if (item.isSeries) refreshEpisodes() else refreshMovieButtons()
    }

    // ---------------- movie ----------------

    private fun setUpMovie() {
        findViewById<View>(R.id.movieButtons).visibility = View.VISIBLE
        playButton.setOnClickListener { playMovie(fromStart = false) }
        restartButton.setOnClickListener { playMovie(fromStart = true) }
        refreshMovieButtons()
        playButton.requestFocus()
    }

    private fun refreshMovieButtons() {
        val req = source.movieRequest(item) ?: return
        val saved = ResumeStore.get(req.key)
        playButton.text = if (saved != null) "▶  Resume from ${formatTime(saved.position)}" else "▶  Play"
        restartButton.visibility = if (saved != null) View.VISIBLE else View.GONE
    }

    private fun playMovie(fromStart: Boolean) {
        val req = source.movieRequest(item) ?: return
        val pos = if (fromStart) 0 else ResumeStore.get(req.key)?.position ?: 0
        VodSession.play(this, req.withStart(pos))
    }

    // ---------------- series ----------------

    private val tabs = ArrayList<TextView>()
    private val pickSeason = Runnable { selectSeason(pendingSeason) }
    private var pendingSeason = 0

    private fun setUpSeries(desc: TextView) {
        desc.maxLines = 3
        findViewById<View>(R.id.movieButtons).visibility = View.GONE
        findViewById<View>(R.id.seriesBox).visibility = View.VISIBLE
        hint.text = Display.hints("◀ ▶" to "Season", "▲ ▼" to "Episode", "OK" to "Play", "Back" to "Return")
        loading.visibility = View.VISIBLE
        VodSession.exec.execute {
            val s = try { source.seasons(item) } catch (_: Exception) { null }
            main.post { onSeasons(s) }
        }
        episodesList.setOnItemClickListener { _, _, position, _ -> playEpisode(position) }
        // ◀ ▶ in the episode list switches season without leaving the list.
        episodesList.setOnKeyListener { _, code, e ->
            if (e.action != android.view.KeyEvent.ACTION_DOWN || seasons.size < 2) return@setOnKeyListener false
            val d = when (code) {
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> -1
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                else -> return@setOnKeyListener false
            }
            val next = (seasonIdx + d).coerceIn(0, seasons.size - 1)
            if (next != seasonIdx) {
                selectSeason(next)
                tabs.getOrNull(next)?.let { t -> (t.parent?.parent as? HorizontalScrollView)?.smoothScrollTo(t.left, 0) }
            }
            true
        }
    }

    private fun onSeasons(s: List<Season>?) {
        loading.visibility = View.GONE
        if (s.isNullOrEmpty()) {
            showEpisodesMessage(if (s == null) "Couldn't load this series.\nCheck the internet and VPN, then try again." else "This series has no episodes yet.")
            return
        }
        seasons = s
        val row = findViewById<LinearLayout>(R.id.seasonTabs)
        row.removeAllViews()
        tabs.clear()
        val d = resources.displayMetrics.density
        s.forEachIndexed { i, season ->
            val tab = TextView(this).apply {
                text = season.title
                textSize = 20f
                @Suppress("DEPRECATION") setTextColor(resources.getColorStateList(R.color.text_tab_sel))
                typeface = Fonts.mediumFace
                setBackgroundResource(R.drawable.season_tab)
                gravity = Gravity.CENTER
                minHeight = (56 * d).toInt()
                setPadding((26 * d).toInt(), 0, (26 * d).toInt(), 0)
                isFocusable = true
                nextFocusDownId = R.id.episodesList
                setOnFocusChangeListener { v, has ->
                    if (has) {
                        pendingSeason = i
                        main.removeCallbacks(pickSeason)
                        main.postDelayed(pickSeason, 220)
                        (v.parent?.parent as? HorizontalScrollView)?.smoothScrollTo((v.left - 40 * d).toInt().coerceAtLeast(0), 0)
                    }
                }
                setOnClickListener { selectSeason(i); episodesList.requestFocus() }
            }
            row.addView(tab, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) marginStart = (12 * d).toInt()
            })
            tabs.add(tab)
        }
        findViewById<View>(R.id.seasonScroll).visibility = if (s.size == 1) View.GONE else View.VISIBLE
        // Open the season that was being watched, else Season 1.
        val resumeKey = ResumeStore.all().firstOrNull { it.key.startsWith("p:${item.id}:") || it.title == item.title }?.key
        val start = s.indexOfFirst { resumeKey?.contains(":${it.id}:") == true }.takeIf { it >= 0 } ?: 0
        selectSeason(start)
    }

    private fun selectSeason(i: Int) {
        if (i == seasonIdx) return
        seasonIdx = i
        tabs.forEachIndexed { k, t -> t.isSelected = k == i }
        val season = seasons.getOrNull(i) ?: return
        val gen = ++generation
        episodes = emptyList()
        episodesList.adapter = null
        episodeCount.text = ""
        findViewById<View>(R.id.episodesEmpty).visibility = View.GONE
        loading.visibility = View.VISIBLE
        VodSession.exec.execute {
            val eps = try { source.episodes(item, season) } catch (_: Exception) { null }
            main.post {
                if (gen != generation) return@post
                loading.visibility = View.GONE
                if (eps == null) { showEpisodesMessage("Couldn't load the episodes.\nCheck the internet and VPN, then try again."); return@post }
                episodes = eps
                refreshEpisodes()
                if (tabs.none { it.hasFocus() }) {
                    if (episodes.isNotEmpty()) episodesList.requestFocus() else tabs.getOrNull(seasonIdx)?.requestFocus()
                }
            }
        }
    }

    private val episodeCount: TextView get() = findViewById(R.id.episodeCount)

    private fun showEpisodesMessage(msg: String) {
        episodesList.visibility = View.GONE
        findViewById<TextView>(R.id.episodesEmpty).apply { text = msg; visibility = View.VISIBLE }
        episodeCount.text = ""
        if (tabs.isEmpty()) findViewById<View>(R.id.seasonScroll).visibility = View.GONE
    }

    private fun refreshEpisodes() {
        val season = seasons.getOrNull(seasonIdx) ?: return
        if (episodes.isEmpty()) { showEpisodesMessage("No episodes in ${season.title} yet."); return }
        findViewById<View>(R.id.episodesEmpty).visibility = View.GONE
        episodesList.visibility = View.VISIBLE
        episodeCount.text = if (episodes.size == 1) "1 episode" else "${episodes.size} episodes"
        val saved = episodes.map { ResumeStore.get(source.episodeRequest(item, season, it).key) }
        val sel = episodesList.selectedItemPosition
        episodesList.adapter = EpisodeAdapter(episodes, saved)
        if (sel >= 0 && sel < episodes.size) episodesList.setSelection(sel)
        else {
            // Jump to the episode in progress, if any.
            val inProgress = saved.indexOfFirst { it != null && (it.duration <= 0 || it.position < it.duration - 60_000) }
            if (inProgress >= 0) episodesList.setSelection(inProgress)
        }
    }

    private class EpisodeAdapter(
        private val items: List<Episode>,
        private val saved: List<dev.yash.zapper.data.ResumeEntry?>,
    ) : android.widget.BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
            val v = convertView ?: android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_episode, parent, false)
                .also { Fonts.apply(it) }
            val ep = items[position]
            val s = saved[position]
            val n = ep.number ?: (position + 1)
            v.findViewById<TextView>(R.id.epNum).text = n.toString()
            // "Episode 4" alone says nothing new next to the big number: show the name when there is one.
            val name = ep.title.replace(Regex("(?i)^episode\\s*\\d+\\s*·?\\s*"), "").trim()
            v.findViewById<TextView>(R.id.epTitle).text = if (name.isEmpty()) "Episode $n" else name
            val sub = v.findViewById<TextView>(R.id.epSub)
            val bar = v.findViewById<android.widget.ProgressBar>(R.id.epProgress)
            val watched = s != null && s.duration > 0 && s.position >= s.duration - 60_000
            when {
                watched -> { sub.text = "Watched"; sub.visibility = View.VISIBLE; bar.visibility = View.GONE }
                s != null -> {
                    sub.text = "Resume from ${formatTime(s.position)}"
                    sub.visibility = View.VISIBLE
                    if (s.duration > 0) { bar.visibility = View.VISIBLE; bar.progress = ((s.position * 1000) / s.duration).toInt() }
                    else bar.visibility = View.GONE
                }
                else -> { sub.visibility = View.GONE; bar.visibility = View.GONE }
            }
            v.findViewById<TextView>(R.id.epPlay).text = if (watched) "✓" else "▶"
            return v
        }
    }

    private fun playEpisode(i: Int) {
        val season = seasons.getOrNull(seasonIdx) ?: return
        val ep = episodes.getOrNull(i) ?: return
        val req = source.episodeRequest(item, season, ep)
        val pos = ResumeStore.get(req.key)?.position ?: 0
        VodSession.play(this, req.withStart(pos))
    }

    companion object {
        fun formatMinutes(m: Int) = if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"

        fun formatTime(ms: Long): String {
            val s = ms / 1000
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        }
    }
}

fun PlayRequest.withStart(pos: Long) = PlayRequest(key, title, subtitle, poster, cmd, series, direct, pos)
