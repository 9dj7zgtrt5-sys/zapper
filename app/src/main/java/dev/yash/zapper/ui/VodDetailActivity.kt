package dev.yash.zapper.ui

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
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
    private lateinit var seasonsList: ListView
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

        Logos.into(findViewById<ImageView>(R.id.detailPoster), item.poster, Logos.POSTER_PX)
        findViewById<TextView>(R.id.detailTitle).text = item.title
        val meta = listOfNotNull(
            item.year,
            item.rating?.let { "★ $it" },
            if (item.minutes > 0) formatMinutes(item.minutes) else null,
            item.genre,
        ).joinToString("   ·   ")
        findViewById<TextView>(R.id.detailMeta).text = meta
        val desc = findViewById<TextView>(R.id.detailDesc)
        desc.text = item.description ?: ""
        desc.visibility = if (item.description.isNullOrBlank()) View.GONE else View.VISIBLE

        playButton = findViewById(R.id.playButton)
        restartButton = findViewById(R.id.restartButton)
        seasonsList = findViewById(R.id.seasonsList)
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

    private fun setUpSeries(desc: TextView) {
        desc.maxLines = 3
        findViewById<View>(R.id.movieButtons).visibility = View.GONE
        findViewById<View>(R.id.seriesBox).visibility = View.VISIBLE
        hint.text = "▲ ▼  Choose      ▶  Episodes      OK  Play      Back  Return"
        loading.visibility = View.VISIBLE
        VodSession.exec.execute {
            val s = try { source.seasons(item) } catch (_: Exception) { null }
            main.post { onSeasons(s) }
        }
        seasonsList.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = selectSeason(position)
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        seasonsList.setOnItemClickListener { _, _, position, _ ->
            selectSeason(position)
            episodesList.requestFocus()
        }
        episodesList.setOnItemClickListener { _, _, position, _ -> playEpisode(position) }
    }

    private fun onSeasons(s: List<Season>?) {
        loading.visibility = View.GONE
        if (s.isNullOrEmpty()) {
            hint.text = if (s == null) "Couldn't load the episodes. Check the internet and VPN." else "No episodes found."
            return
        }
        seasons = s
        seasonsList.adapter = ArrayAdapter(this, R.layout.item_row, s.map { it.title })
        seasonsList.setSelection(0)
        if (s.size == 1) seasonsList.visibility = View.GONE
        selectSeason(0)
    }

    private fun selectSeason(i: Int) {
        if (i == seasonIdx) return
        seasonIdx = i
        val season = seasons.getOrNull(i) ?: return
        val gen = ++generation
        episodesList.adapter = null
        loading.visibility = View.VISIBLE
        VodSession.exec.execute {
            val eps = try { source.episodes(item, season) } catch (_: Exception) { null }
            main.post {
                if (gen != generation) return@post
                loading.visibility = View.GONE
                episodes = eps ?: emptyList()
                refreshEpisodes()
                if (seasons.size == 1 || !seasonsList.hasFocus()) episodesList.requestFocus()
            }
        }
    }

    private fun refreshEpisodes() {
        val season = seasons.getOrNull(seasonIdx) ?: return
        val labels = episodes.map { ep ->
            val saved = ResumeStore.get(source.episodeRequest(item, season, ep).key)
            if (saved != null) "${ep.title}      ▶ resume ${formatTime(saved.position)}" else ep.title
        }
        val sel = episodesList.selectedItemPosition
        episodesList.adapter = ArrayAdapter(this, R.layout.item_row, labels)
        if (sel >= 0) episodesList.setSelection(sel)
        if (episodes.isEmpty()) hint.text = "No episodes in this season."
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
