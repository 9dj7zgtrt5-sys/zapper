package dev.yash.zapper.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import dev.yash.zapper.R
import dev.yash.zapper.data.PlayRequest
import dev.yash.zapper.data.Repo
import dev.yash.zapper.data.ResumeStore
import dev.yash.zapper.data.VodCategory
import dev.yash.zapper.data.VodItem
import dev.yash.zapper.data.VodSource
import java.util.concurrent.Executors

/** Hands objects between the VOD screens (single process, so a simple holder is enough). */
object VodSession {
    var source: VodSource? = null
    var item: VodItem? = null
    var request: PlayRequest? = null
    val exec = Executors.newSingleThreadExecutor()

    fun play(ctx: Context, r: PlayRequest) {
        request = r
        ctx.startActivity(Intent(ctx, VodPlayerActivity::class.java))
    }
}

class PosterAdapter : BaseAdapter() {
    val items = ArrayList<VodItem>()

    override fun getCount() = items.size
    override fun getItem(position: Int) = items[position]
    override fun getItemId(position: Int) = position.toLong()

    private class Holder(v: View) {
        val image: ImageView = v.findViewById(R.id.posterImage)
        val fallback: TextView = v.findViewById(R.id.posterFallback)
        val title: TextView = v.findViewById(R.id.posterTitle)
        val progress: ProgressBar = v.findViewById(R.id.posterProgress)
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_poster, parent, false)
            .also { Fonts.apply(it); it.tag = Holder(it) }
        val h = v.tag as Holder
        val item = items[position]
        h.title.text = item.title
        h.fallback.text = item.title
        Logos.into(h.image, item.poster, Logos.POSTER_PX)
        val r = item.resume
        if (r != null && r.duration > 0) {
            h.progress.visibility = View.VISIBLE
            h.progress.progress = ((r.position * 1000) / r.duration).toInt()
        } else h.progress.visibility = View.GONE
        return v
    }
}

/** TiviMate-style poster browser for movies or series. */
class VodActivity : Activity() {
    private lateinit var categoriesList: ListView
    private lateinit var grid: GridView
    private lateinit var loading: View
    private lateinit var empty: TextView
    private lateinit var subtitle: TextView
    private lateinit var searchBox: android.widget.EditText
    private var query = ""
    private val posters = PosterAdapter()
    private var categories: List<VodCategory> = emptyList()
    private var source: VodSource? = null
    private var series = false

    private var current: VodCategory? = null
    private var page = 0
    private var pages = 1
    private var busy = false
    private var generation = 0
    private val main get() = Repo.main

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(this)
        Logos.init(this)
        setContentView(R.layout.activity_vod)
        Fonts.apply(window.decorView)
        series = intent.getBooleanExtra(EXTRA_SERIES, false)
        searchBox = findViewById(R.id.vodSearch)
        findViewById<TextView>(R.id.vodHint).text =
            Display.hints("OK" to "Open", "◀" to "Categories", "Back" to "Home")
        searchBox.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                event.action == android.view.KeyEvent.ACTION_DOWN
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE || enter
            ) { runSearch(searchBox.text.toString()); true } else false
        }
        findViewById<TextView>(R.id.vodTitle).apply {
            text = if (series) "Series" else "Movies"
            setCompoundDrawablesRelativeWithIntrinsicBounds(if (series) R.drawable.ic_series else R.drawable.ic_movie, 0, 0, 0)
            compoundDrawablePadding = (14 * resources.displayMetrics.density).toInt()
        }
        subtitle = findViewById(R.id.vodSubtitle)
        categoriesList = findViewById(R.id.vodCategories)
        grid = findViewById(R.id.vodGrid)
        loading = findViewById(R.id.vodLoading)
        empty = findViewById(R.id.vodEmpty)
        grid.adapter = posters

        categoriesList.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                main.removeCallbacks(openPending)
                pending = position
                main.postDelayed(openPending, 350)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        categoriesList.setOnItemClickListener { _, _, position, _ ->
            pending = position
            openPending.run()
            if (categories.getOrNull(position)?.id == SEARCH) openKeyboard()
            else if (posters.count > 0) grid.requestFocus()
        }
        grid.setOnItemClickListener { _, _, position, _ -> open(posters.items.getOrNull(position)) }
        grid.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) {}
            override fun onScroll(view: AbsListView?, first: Int, visible: Int, total: Int) {
                if (total > 0 && first + visible >= total - 8) loadMore()
            }
        })
        grid.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position >= posters.count - 8) loadMore()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val src = Repo.vod()
        source = src
        VodSession.source = src
        if (src == null) {
            showEmpty("No movies or series are available.")
            return
        }
        showLoading(true)
        VodSession.exec.execute {
            var err: String? = null
            val cats = try { src.categories(series) } catch (e: Exception) { err = e.message ?: e.javaClass.simpleName; null }
            val noSeriesSection = cats != null && series && !src.hasSeriesSection
            main.post { onCategories(cats, noSeriesSection, err) }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh "Continue watching" progress after returning from the player.
        if (current?.id == CONTINUE) {
            posters.items.clear()
            posters.items.addAll(continueItems())
            posters.notifyDataSetChanged()
        }
    }

    private fun iconFor(id: String) = when (id) {
        CONTINUE -> R.drawable.ic_history
        SEARCH -> R.drawable.ic_search
        else -> 0
    }

    /**
     * Things to pick up again in this section, newest first: movies here, episodes under Series
     * (one per show, the latest episode, titled "Show · S2 E4").
     */
    private fun continueItems(): List<VodItem> {
        val seen = HashSet<String>()
        return ResumeStore.all().filter { e ->
            val isEpisode = e.series != null || (e.subtitle?.contains("Episode", true) == true)
            val finished = e.duration > 0 && e.position >= e.duration - 60_000
            isEpisode == series && !finished && seen.add(e.title)
        }.map {
            val short = it.subtitle?.let { s -> shortEpisode(s) }
            VodItem(it.key, if (series && short != null) "${it.title} · $short" else it.title, it.poster, it.subtitle,
                null, null, null, 0, false, it.cmd, resume = it)
        }
    }

    /** "Season 2 · Episode 4" → "S2 E4". */
    private fun shortEpisode(s: String): String? {
        val season = Regex("(?i)season\\s*(\\d+)").find(s)?.groupValues?.get(1)
        val ep = Regex("(?i)episode\\s*(\\d+)").find(s)?.groupValues?.get(1)
        return when {
            season != null && ep != null -> "S$season E$ep"
            ep != null -> "Ep $ep"
            else -> null
        }
    }

    private fun onCategories(cats: List<VodCategory>?, noSeriesSection: Boolean, err: String? = null) {
        showLoading(false)
        if (cats == null) { showEmpty("Couldn't load the list.\nCheck the internet and VPN, then try again.\n\n(${err ?: "unknown error"})"); return }
        if (noSeriesSection) { showEmpty("On this service, series are listed under Movies."); return }
        val list = ArrayList<VodCategory>()
        val resume = continueItems()
        if (resume.isNotEmpty()) list.add(VodCategory(CONTINUE, "Continue watching"))
        list.add(VodCategory(SEARCH, "Search"))
        list.addAll(cats.filter { it.id == "*" || Repo.vodCategoryVisible(series, it.title) }.map { VodCategory(it.id, Display.pretty(it.title)) })
        categories = list
        if (cats.isEmpty()) { showEmpty("Nothing here yet."); return }
        categoriesList.adapter = RowAdapter(list.map { it.title }, list.map { iconFor(it.id) })
        // Land on "Continue watching" when there is something to resume (first poster focused: OK resumes);
        // otherwise on the first real category.
        val start = if (resume.isNotEmpty()) 0 else list.indexOfFirst { it.id != SEARCH }.takeIf { it >= 0 } ?: 0
        categoriesList.setSelection(start)
        pending = start
        openPending.run()
        if (resume.isNotEmpty()) { grid.requestFocus(); grid.setSelection(0) } else categoriesList.requestFocus()
    }

    private var pending = 0
    private val openPending = Runnable {
        val cat = categories.getOrNull(pending) ?: return@Runnable
        if (cat.id == current?.id && (posters.count > 0 || cat.id == SEARCH)) return@Runnable
        current = cat
        val isSearch = cat.id == SEARCH
        searchBox.visibility = if (isSearch) View.VISIBLE else View.GONE
        val d = resources.displayMetrics.density
        grid.setPadding(grid.paddingLeft, ((if (isSearch) 84 else 8) * d).toInt(), grid.paddingRight, grid.paddingBottom)
        subtitle.text = when {
            isSearch -> if (query.isEmpty()) "Find a title" else "Results for “$query”"
            cat.id == CONTINUE -> "Pick up where you left off"
            else -> cat.title
        }
        generation++
        posters.items.clear()
        posters.notifyDataSetChanged()
        page = 0
        pages = 1
        busy = false
        empty.visibility = View.GONE
        if (cat.id == CONTINUE) {
            posters.items.addAll(continueItems())
            posters.notifyDataSetChanged()
            pages = 0
        } else if (isSearch) {
            if (query.isEmpty()) { pages = 0; showEmpty("Press OK on Search, then type a name\nor hold the mic button and say it.") } else loadMore()
        } else loadMore()
    }

    private fun loadMore() {
        val src = source ?: return
        val cat = current ?: return
        if (busy || cat.id == CONTINUE || page >= pages) return
        if (cat.id == SEARCH && query.isEmpty()) return
        val q = query
        busy = true
        val gen = generation
        val next = page + 1
        if (posters.count == 0) showLoading(true)
        VodSession.exec.execute {
            var err: String? = null
            val result = try {
                if (cat.id == SEARCH) src.search(series, q, next) else src.page(series, cat, next)
            } catch (e: Exception) { err = e.message; null }
            main.post {
                if (gen != generation) return@post
                busy = false
                showLoading(false)
                if (result == null) {
                    if (posters.count == 0) showEmpty("Couldn't load this category.\n\n(${err ?: "unknown error"})")
                    pages = page
                    return@post
                }
                page = next
                pages = result.pages.coerceAtLeast(next).let { if (result.items.isEmpty()) next else it }
                posters.items.addAll(result.items)
                posters.notifyDataSetChanged()
                if (next == 1 && result.total > 0 && cat.id != SEARCH) {
                    subtitle.text = cat.title + "      " + "%,d".format(result.total) + " titles"
                }
                if (posters.count == 0) showEmpty(
                    if (cat.id == SEARCH) "Nothing found for “$q”.\nTry a shorter name, like one word from the title." else "Nothing in this category."
                )
            }
        }
    }

    private fun openKeyboard() {
        searchBox.requestFocus()
        searchBox.setSelection(searchBox.text.length)
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(searchBox, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun runSearch(text: String) {
        val q = text.trim()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(searchBox.windowToken, 0)
        if (q.isEmpty()) return
        query = q
        val idx = categories.indexOfFirst { it.id == SEARCH }
        current = null
        pending = idx
        openPending.run()
        main.postDelayed({ if (posters.count > 0) grid.requestFocus() }, 600)
    }

    private fun open(item: VodItem?) {
        item ?: return
        val r = item.resume
        if (r != null) {
            VodSession.play(this, r.toRequest())
            return
        }
        VodSession.item = item
        startActivity(Intent(this, VodDetailActivity::class.java))
    }

    private fun showLoading(b: Boolean) {
        loading.visibility = if (b) View.VISIBLE else View.GONE
    }

    private fun showEmpty(msg: String) {
        empty.text = msg
        empty.visibility = View.VISIBLE
    }

    companion object {
        const val EXTRA_SERIES = "series"
        private const val CONTINUE = "__continue__"
        private const val SEARCH = "__search__"
    }
}
