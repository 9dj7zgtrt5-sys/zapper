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
import android.widget.ArrayAdapter
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
            .also { it.tag = Holder(it) }
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
        series = intent.getBooleanExtra(EXTRA_SERIES, false)
        findViewById<TextView>(R.id.vodTitle).text = if (series) "Series" else "Movies"
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
            if (posters.count > 0) grid.requestFocus()
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
            val cats = try { src.categories(series) } catch (_: Exception) { null }
            val noSeriesSection = series && !src.hasSeriesSection
            main.post { onCategories(cats, noSeriesSection) }
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

    private fun continueItems() = ResumeStore.asItems()

    private fun onCategories(cats: List<VodCategory>?, noSeriesSection: Boolean) {
        showLoading(false)
        if (cats == null) { showEmpty("Couldn't load the list.\nCheck the internet and VPN, then try again."); return }
        if (noSeriesSection) { showEmpty("On this service, series are listed under Movies."); return }
        val list = ArrayList<VodCategory>()
        if (ResumeStore.all().isNotEmpty()) list.add(VodCategory(CONTINUE, "▶ Continue watching"))
        list.addAll(cats)
        categories = list
        if (list.isEmpty()) { showEmpty("Nothing here yet."); return }
        categoriesList.adapter = ArrayAdapter(this, R.layout.item_row, list.map { it.title })
        categoriesList.setSelection(0)
        categoriesList.requestFocus()
        pending = 0
        openPending.run()
    }

    private var pending = 0
    private val openPending = Runnable {
        val cat = categories.getOrNull(pending) ?: return@Runnable
        if (cat.id == current?.id && posters.count > 0) return@Runnable
        current = cat
        subtitle.text = cat.title.removePrefix("▶ ")
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
        } else loadMore()
    }

    private fun loadMore() {
        val src = source ?: return
        val cat = current ?: return
        if (busy || cat.id == CONTINUE || page >= pages) return
        busy = true
        val gen = generation
        val next = page + 1
        if (posters.count == 0) showLoading(true)
        VodSession.exec.execute {
            val result = try { src.page(series, cat, next) } catch (_: Exception) { null }
            main.post {
                if (gen != generation) return@post
                busy = false
                showLoading(false)
                if (result == null) {
                    if (posters.count == 0) showEmpty("Couldn't load this category.")
                    pages = page
                    return@post
                }
                page = next
                pages = result.pages.coerceAtLeast(next).let { if (result.items.isEmpty()) next else it }
                posters.items.addAll(result.items)
                posters.notifyDataSetChanged()
                if (posters.count == 0) showEmpty("Nothing in this category.")
            }
        }
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
    }
}
