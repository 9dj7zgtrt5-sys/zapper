package dev.yash.zapper.data

import org.json.JSONArray
import org.json.JSONObject

class VodCategory(val id: String, val title: String)

/** @param total number of titles across all pages, or -1 if the source doesn't say */
class VodPage(val items: List<VodItem>, val pages: Int, val total: Int = -1)

class VodItem(
    val id: String,
    val title: String,
    val poster: String?,
    val description: String?,
    val year: String?,
    val rating: String?,
    val genre: String?,
    val minutes: Int,
    val isSeries: Boolean,
    val cmd: String?,
    /** Old-style portal series list their episode numbers directly. */
    val episodeNumbers: List<Int> = emptyList(),
    /** Portal API type this came from ("vod" or "series"). */
    val sourceType: String = "vod",
    /** Set for "Continue watching" entries. */
    val resume: ResumeEntry? = null,
)

class Season(val id: String, val title: String, val cmd: String?, val episodeNumbers: List<Int>)

class Episode(val id: String, val title: String, val cmd: String, val number: Int?)

/** Something the VOD player can open. `cmd` is a portal command, or the stream URL itself when `direct`. */
class PlayRequest(
    val key: String,
    val title: String,
    val subtitle: String?,
    val poster: String?,
    val cmd: String,
    val series: Int?,
    val direct: Boolean,
    val startPositionMs: Long,
)

interface VodSource {
    /** Whether series have their own section (otherwise they appear among movies). */
    val hasSeriesSection: Boolean
    fun categories(series: Boolean): List<VodCategory>
    fun page(series: Boolean, category: VodCategory, page: Int): VodPage
    fun search(series: Boolean, query: String, page: Int): VodPage
    /** Total titles, or null if unknown. */
    fun total(series: Boolean): Int?
    fun seasons(item: VodItem): List<Season>
    fun episodes(item: VodItem, season: Season): List<Episode>
    fun movieRequest(item: VodItem): PlayRequest?
    fun episodeRequest(item: VodItem, season: Season, ep: Episode): PlayRequest
}

/** Movies & series from a MAC portal. */
class PortalVod(private val c: StalkerClient) : VodSource {
    private val seriesCats: List<VodCategory> by lazy {
        try { c.vodCategories("series") } catch (_: Exception) { emptyList() }
    }

    override val hasSeriesSection: Boolean get() = seriesCats.isNotEmpty()

    override fun categories(series: Boolean): List<VodCategory> =
        if (series) seriesCats else c.vodCategories("vod")

    override fun page(series: Boolean, category: VodCategory, page: Int): VodPage =
        c.vodPage(if (series) "series" else "vod", category.id, page)

    override fun search(series: Boolean, query: String, page: Int): VodPage =
        c.vodPage(if (series && hasSeriesSection) "series" else "vod", "*", page, query)

    override fun total(series: Boolean): Int? {
        if (series && !hasSeriesSection) return null
        val t = c.vodPage(if (series) "series" else "vod", "*", 1).total
        return if (t > 0) t else null
    }

    override fun seasons(item: VodItem) = c.seasons(item)
    override fun episodes(item: VodItem, season: Season) = c.episodes(item, season)

    override fun movieRequest(item: VodItem): PlayRequest? {
        val cmd = item.cmd ?: return null
        return PlayRequest("p:${item.id}", item.title, item.year, item.poster, cmd, null, false, 0)
    }

    override fun episodeRequest(item: VodItem, season: Season, ep: Episode) = PlayRequest(
        "p:${item.id}:${season.id}:${ep.id}:${ep.number}", item.title, "${season.title} · ${ep.title}", item.poster,
        ep.cmd, ep.number, false, 0,
    )

    companion object {
        fun link(c: StalkerClient, r: PlayRequest): String? = c.vodLink(r.cmd, r.series)
    }
}

/** Movies & series from the VOD entries of an M3U playlist. */
class M3uVod(all: List<Channel>) : VodSource {
    private val epRegex = Regex("""(?i)^(.*?)[\s._\-:]*S(\d{1,2})\s*[._\-]?\s*E(\d{1,3})""")

    private class Ep(val show: String, val season: Int, val number: Int, val ch: Channel)

    private val movies = ArrayList<Channel>()
    private val episodes = ArrayList<Ep>()

    init {
        for (c in all) {
            if (!c.isVod) continue
            val m = epRegex.find(c.name)
            if (m != null) {
                val show = m.groupValues[1].trim().trimEnd('-', ':', '|').trim().ifEmpty { c.group }
                episodes.add(Ep(show, m.groupValues[2].toInt(), m.groupValues[3].toInt(), c))
            } else if (c.url.contains("/series/")) {
                episodes.add(Ep(c.name, 1, episodes.count { it.show == c.name } + 1, c))
            } else movies.add(c)
        }
    }

    override val hasSeriesSection: Boolean get() = episodes.isNotEmpty()

    override fun categories(series: Boolean): List<VodCategory> {
        val groups = if (series) episodes.map { it.ch.group } else movies.map { it.group }
        val distinct = groups.distinct()
        return (if (distinct.size > 1) listOf(VodCategory("*", "All")) else emptyList()) + distinct.map { VodCategory(it, it) }
    }

    override fun page(series: Boolean, category: VodCategory, page: Int): VodPage {
        if (page > 1) return VodPage(emptyList(), 1)
        val inCat = { g: String -> category.id == "*" || g == category.id }
        val items = if (!series) {
            movies.filter { inCat(it.group) }.map {
                VodItem(it.url, it.name, it.logo, null, null, null, it.group, 0, false, it.url)
            }
        } else {
            episodes.filter { inCat(it.ch.group) }.groupBy { it.show }.map { (show, eps) ->
                VodItem("m3u:$show", show, eps.firstNotNullOfOrNull { it.ch.logo }, null, null, null,
                    eps.first().ch.group, 0, true, null)
            }
        }
        return VodPage(items, 1)
    }

    override fun search(series: Boolean, query: String, page: Int): VodPage {
        val q = query.trim().lowercase()
        val all = this.page(series, VodCategory("*", "All"), 1).items
        return VodPage(all.filter { it.title.lowercase().contains(q) }, 1)
    }

    override fun total(series: Boolean): Int? =
        if (series) episodes.map { it.show }.distinct().size else movies.size

    override fun seasons(item: VodItem): List<Season> {
        val show = item.id.removePrefix("m3u:")
        return episodes.filter { it.show == show }.map { it.season }.distinct().sorted()
            .map { Season(it.toString(), "Season $it", null, emptyList()) }
    }

    override fun episodes(item: VodItem, season: Season): List<Episode> {
        val show = item.id.removePrefix("m3u:")
        val s = season.id.toIntOrNull()
        return episodes.filter { it.show == show && it.season == s }.sortedBy { it.number }
            .map { Episode(it.ch.url, "Episode ${it.number}", it.ch.url, it.number) }
    }

    override fun movieRequest(item: VodItem): PlayRequest? {
        val url = item.cmd ?: return null
        return PlayRequest("m:$url", item.title, item.genre, item.poster, url, null, true, 0)
    }

    override fun episodeRequest(item: VodItem, season: Season, ep: Episode) = PlayRequest(
        "m:${ep.cmd}", item.title, "${season.title} · ${ep.title}", item.poster, ep.cmd, ep.number, true, 0,
    )
}

/** Saved playback positions for "Continue watching". */
class ResumeEntry(
    val key: String,
    val title: String,
    val subtitle: String?,
    val poster: String?,
    val cmd: String,
    val series: Int?,
    val direct: Boolean,
    val position: Long,
    val duration: Long,
    val updated: Long,
) {
    fun toRequest() = PlayRequest(key, title, subtitle, poster, cmd, series, direct, position)
}

object ResumeStore {
    private const val MAX = 40

    private fun load(): MutableList<ResumeEntry> {
        val raw = Repo.store().getString("resume", null) ?: return ArrayList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                ResumeEntry(
                    o.getString("key"), o.optString("title"), o.optString("subtitle").takeIf { it.isNotEmpty() },
                    o.optString("poster").takeIf { it.isNotEmpty() }, o.getString("cmd"),
                    if (o.has("series")) o.optInt("series") else null, o.optBoolean("direct"),
                    o.optLong("position"), o.optLong("duration"), o.optLong("updated"),
                )
            }.toMutableList()
        } catch (_: Exception) {
            ArrayList()
        }
    }

    private fun save(list: List<ResumeEntry>) {
        val arr = JSONArray()
        for (e in list.take(MAX)) {
            val o = JSONObject()
                .put("key", e.key).put("title", e.title).put("subtitle", e.subtitle ?: "")
                .put("poster", e.poster ?: "").put("cmd", e.cmd).put("direct", e.direct)
                .put("position", e.position).put("duration", e.duration).put("updated", e.updated)
            e.series?.let { o.put("series", it) }
            arr.put(o)
        }
        Repo.store().edit().putString("resume", arr.toString()).apply()
    }

    /** 3.6: episodes saved before the episode-file fix point at the wrong video; forget them once. */
    private fun migrate() {
        val prefs = Repo.store()
        if (prefs.getBoolean("resume_v2", false)) return
        save(load().filterNot { it.series != null && !it.direct && !it.cmd.startsWith(StalkerClient.EPISODE_PREFIX) })
        prefs.edit().putBoolean("resume_v2", true).apply()
    }

    @Synchronized
    fun all(): List<ResumeEntry> { migrate(); return load().sortedByDescending { it.updated } }

    @Synchronized
    fun get(key: String): ResumeEntry? = load().firstOrNull { it.key == key }

    @Synchronized
    fun update(r: PlayRequest, position: Long, duration: Long) {
        val list = load()
        list.removeAll { it.key == r.key }
        val finished = duration > 0 && position > duration * 0.95
        if (!finished && position > 60_000) {
            list.add(0, ResumeEntry(r.key, r.title, r.subtitle, r.poster, r.cmd, r.series, r.direct, position, duration, System.currentTimeMillis()))
        }
        save(list.sortedByDescending { it.updated })
    }

    fun asItems(): List<VodItem> = all().map {
        VodItem(it.key, it.title, it.poster, it.subtitle, null, null, null, 0, false, it.cmd, resume = it)
    }
}
