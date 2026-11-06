package dev.yash.zapper.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.TimeZone

/**
 * Client for MAC-address based "Stalker" / Ministra middleware portals (the MAG set-top box system).
 * Signs in like a MAG250 box using the MAC the provider registered.
 */
class StalkerClient(
    portal: String,
    mac: String,
    /** Optional overrides for providers that lock a MAC to one box's serial / device IDs. */
    snOverride: String = "",
    deviceIdOverride: String = "",
    deviceId2Override: String = "",
    signatureOverride: String = "",
    /** "apiUrl|model" that worked last time; tried first so we don't probe the portal again. */
    private val remembered: String = "",
) {
    open class AuthException(msg: String) : IOException(msg)

    /** The portal has temporarily banned this connection; stop all attempts immediately. */
    class BlockedException(msg: String) : AuthException(msg)

    /** "apiUrl|model" after a successful sign-in, for the caller to save. */
    val workingProfile: String? get() = api?.let { "$it|${variant.model}" }

    /** How the client identifies itself; tried in order until the portal accepts one. */
    private class Variant(val model: String, val ua: String, val xua: String, val encodeCookie: Boolean, val randomToken: Boolean)

    private val variants = listOf(
        Variant("MAG250", UA, "Model: MAG250; Link: WiFi", true, false),
        Variant(
            "MAG254",
            "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 4 rev: 2116 Mobile Safari/533.3",
            "Model: MAG254; Link: Ethernet", false, true,
        ),
        Variant(
            "MAG322",
            "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3",
            "Model: MAG322; Link: Ethernet,WiFi", true, true,
        ),
    )

    val mac: String = normalizeMac(mac)
    private val portalUrl = portal.trim().let { if (it.startsWith("http", ignoreCase = true)) it else "http://$it" }
    @Volatile private var api: String? = null
    @Volatile private var token: String? = null
    @Volatile private var variant: Variant = variants[0]
    private val sn = snOverride.trim().ifEmpty { hash("MD5", this.mac).take(13) }
    private val deviceId = deviceIdOverride.trim().ifEmpty { hash("SHA-256", this.mac) }
    private val deviceId2 = deviceId2Override.trim().ifEmpty { deviceId }
    private val signature = signatureOverride.trim().ifEmpty { hash("SHA-256", sn + this.mac) }

    /** When set, every request is described here (used by the connection test). */
    private var log: MutableList<String>? = null

    private fun root(): String {
        val u = portalUrl.trimEnd('/')
        return when {
            u.endsWith(".php") -> u.substringBeforeLast('/').removeSuffix("/server").removeSuffix("/stalker_portal")
            u.endsWith("/c") -> u.removeSuffix("/c").removeSuffix("/stalker_portal")
            u.contains("/c/") -> u.substringBefore("/c/").removeSuffix("/stalker_portal")
            else -> u.removeSuffix("/stalker_portal")
        }
    }

    private fun candidates(): List<String> {
        val u = portalUrl.trimEnd('/')
        val r = root()
        val list = ArrayList<String>()
        if (u.endsWith(".php")) list.add(u)
        if (u.contains("/stalker_portal")) list.add("$r/stalker_portal/server/load.php")
        list.add("$r/portal.php")
        list.add("$r/server/load.php")
        list.add("$r/stalker_portal/server/load.php")
        return list.distinct()
    }

    private fun referer(apiUrl: String) =
        if (apiUrl.contains("/stalker_portal/")) root() + "/stalker_portal/c/" else root() + "/c/"

    private fun get(apiUrl: String, query: String): JSONObject {
        val v = variant
        val c = URL("$apiUrl?$query&JsHttpRequest=1-xml").openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 45_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", v.ua)
        c.setRequestProperty("X-User-Agent", v.xua)
        c.setRequestProperty("Referer", referer(apiUrl))
        c.setRequestProperty("Accept", "*/*")
        val macCookie = if (v.encodeCookie) URLEncoder.encode(mac, "UTF-8") else mac
        c.setRequestProperty(
            "Cookie",
            "mac=$macCookie; stb_lang=en; timezone=${URLEncoder.encode(TimeZone.getDefault().id, "UTF-8")}",
        )
        token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        val action = Regex("action=([a-z_]+)").find(query)?.groupValues?.get(1) ?: "?"
        try {
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            log?.add("${v.model}  $action  →  HTTP $code   ${apiUrl.substringAfter("://").substringAfter('/')}   ${snippet(body)}")
            if (Regex("(?i)you (have been|are) blocked|ip (is )?(banned|blocked)|too many (requests|attempts)").containsMatchIn(body) || code == 429)
                throw BlockedException(snippet(body).ifEmpty { "Too many attempts" })
            if (code == 401 || code == 403) throw AuthException("HTTP $code at $action" + snippet(body).let { if (it.isNotEmpty()) " — $it" else "" })
            if (code !in 200..299) throw IOException("Portal error (HTTP $code at $action)")
            if (body.contains("Authorization failed", ignoreCase = true)) throw AuthException("Authorization failed at $action")
            val start = body.indexOf('{')
            if (start < 0) throw IOException("Unexpected reply from portal at $action")
            return JSONObject(body.substring(start))
        } catch (e: IOException) {
            if (log != null && e !is AuthException && !(e.message ?: "").startsWith("Portal error") && !(e.message ?: "").startsWith("Unexpected"))
                log?.add("${v.model}  $action  →  ${e.javaClass.simpleName}: ${e.message}")
            throw e
        } finally {
            c.disconnect()
        }
    }

    /** One sign-in attempt (handshake + profile) with a given path and box identity. */
    private fun signIn(a: String, v: Variant) {
        variant = v
        token = null
        api = null
        val tok = if (v.randomToken) hash("MD5", "$mac${System.nanoTime()}").take(32) else ""
        val hs = get(a, "type=stb&action=handshake&token=$tok&prehash=0")
        val t = hs.optJSONObject("js")?.optString("token").orEmpty().ifEmpty { tok }
        if (t.isEmpty()) throw IOException("Portal did not return a session")
        token = t
        api = a
        val profile = get(
            a,
            "type=stb&action=get_profile&hd=1&ver=ImageDescription%3A%200.2.18-r23-250&num_banks=2" +
                "&sn=$sn&stb_type=${v.model}&image_version=218&video_out=hdmi&device_id=$deviceId" +
                "&device_id2=$deviceId2&signature=$signature&auth_second_step=1&hw_version=1.7-BD-00" +
                "&not_valid_token=0&api_signature=262&prehash=",
        )
        val block = profile.optJSONObject("js")?.optString("block_msg").orEmpty()
        if (block.isNotBlank()) { api = null; throw AuthException(block) }
    }

    private fun isNotFound(e: Exception) = (e.message ?: "").contains("HTTP 404") ||
        e is java.net.UnknownHostException || e is java.net.ConnectException

    /**
     * Signs in, gently: reuses what worked before, otherwise finds the API path with one identity and
     * only tries other box identities on that path. Stops at once if the portal says we're blocked.
     */
    @Synchronized
    fun connect() {
        // 1. Same session details as last time (normal case: a single request pair).
        val known = api?.let { it to variant } ?: remembered.split('|').takeIf { it.size == 2 }?.let { (u, m) ->
            variants.firstOrNull { it.model == m }?.let { u to it }
        }
        var lastErr: Exception? = null
        if (known != null) {
            try { signIn(known.first, known.second); return } catch (e: BlockedException) { throw e } catch (e: Exception) { lastErr = e }
        }
        // 2. Find the API path using the first identity; skip paths that don't exist.
        var found: String? = null
        for (a in candidates()) {
            try { signIn(a, variants[0]); return } catch (e: BlockedException) { throw e } catch (e: Exception) {
                lastErr = e
                if (!isNotFound(e)) { found = a; break }
            }
        }
        // 3. The portal lives at `found` but refused: try the other identities there only, slowly.
        if (found != null) {
            for (v in variants.drop(1)) {
                Thread.sleep(1500)
                try { signIn(found, v); return } catch (e: BlockedException) { throw e } catch (e: Exception) { lastErr = e }
            }
        }
        api = null
        throw lastErr ?: IOException("Portal not reachable")
    }

    /** Step-by-step connection test for the phone settings page. */
    fun diagnose(): List<String> {
        val lines = ArrayList<String>()
        lines.add("Portal: $portalUrl")
        lines.add("MAC: $mac   (serial $sn)")
        log = lines
        try {
            connect()
            lines.add("✓ Signed in as ${variant.model} via ${api?.substringAfter("://")?.substringAfter('/')}")
            val n = try { channels().size } catch (e: Exception) { lines.add("✗ Channel list failed: ${e.message}"); -1 }
            if (n >= 0) lines.add("✓ $n channels")
            for ((type, label) in listOf("vod" to "Movies", "series" to "Series")) {
                try {
                    val cats = vodCategories(type)
                    lines.add("✓ $label: ${cats.size} categories")
                    val first = cats.firstOrNull()
                    if (first != null) {
                        val p = vodPage(type, first.id, 1)
                        lines.add("   '${first.title}': ${p.items.size} items on page 1 of ${p.pages}" +
                            (p.items.firstOrNull()?.let { " — e.g. ${it.title}" } ?: ""))
                    }
                } catch (e: Exception) {
                    lines.add("✗ $label: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        } catch (e: Exception) {
            lines.add("✗ ${e.message ?: e.javaClass.simpleName}")
        } finally {
            log = null
        }
        return lines
    }

    private fun snippet(body: String): String {
        val text = body.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
        return if (text.length > 140) text.take(140) + "…" else text
    }

    /** Runs a portal call, signing in again once if the session expired. */
    private fun call(query: String): JSONObject {
        if (token == null || api == null) connect()
        return try {
            get(api!!, query)
        } catch (e: AuthException) {
            token = null
            connect()
            get(api!!, query)
        }
    }

    private fun genres(): Map<String, String> {
        val out = HashMap<String, String>()
        val arr = call("type=itv&action=get_genres").optJSONArray("js") ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out[o.optString("id")] = o.optString("title")
        }
        return out
    }

    fun channels(): List<Channel> {
        val g = try { genres() } catch (_: Exception) { emptyMap() }
        var data: JSONArray? = call("type=itv&action=get_all_channels").optJSONObject("js")?.optJSONArray("data")
        if (data == null || data.length() == 0) data = pagedChannels()
        val rows = ArrayList<JSONObject>()
        for (i in 0 until data.length()) data.optJSONObject(i)?.let { rows.add(it) }
        rows.sortBy { it.optString("number").toIntOrNull() ?: Int.MAX_VALUE }
        return rows.mapNotNull { o ->
            val id = o.optString("id")
            val cmd = o.optString("cmd")
            if (id.isEmpty() || cmd.isEmpty()) return@mapNotNull null
            Channel(
                name = o.optString("name").ifBlank { "Channel $id" },
                url = "stalker:$id",
                group = g[o.optString("tv_genre_id")]?.takeIf { it.isNotBlank() && !it.equals("all", true) } ?: "Other",
                logo = logoUrl(o.optString("logo")),
                tvgId = o.optString("xmltv_id").takeIf { it.isNotBlank() },
                tvgName = null,
                catchup = null,
                isVod = false,
                userAgent = UA,
                cmd = cmd,
            )
        }
    }

    /** Older portals only support paging through channels. */
    private fun pagedChannels(): JSONArray {
        val out = JSONArray()
        var page = 1
        var total = Int.MAX_VALUE
        while (out.length() < total && page <= 400) {
            val js = call("type=itv&action=get_ordered_list&genre=*&force_ch_link_check=&fav=0&sortby=number&hd=0&p=$page")
                .optJSONObject("js") ?: break
            total = js.optInt("total_items", 0)
            val d = js.optJSONArray("data") ?: break
            if (d.length() == 0) break
            for (i in 0 until d.length()) out.put(d.get(i))
            page++
        }
        return out
    }

    private fun logoUrl(logo: String): String? {
        if (logo.isBlank()) return null
        if (logo.startsWith("http", ignoreCase = true)) return logo
        if (logo.startsWith("/")) return root() + logo
        return root() + "/stalker_portal/misc/logos/320/" + logo
    }

    /** Asks the portal for a fresh, playable stream URL for a channel. */
    fun createLink(cmd: String): String? {
        val js = call(
            "type=itv&action=create_link&cmd=${URLEncoder.encode(cmd, "UTF-8")}&series=&forced_storage=undefined" +
                "&disable_ad=0&download=0&force_ch_link_check=0",
        ).optJSONObject("js")
        val fresh = js?.optString("cmd").orEmpty()
        return extractUrl(fresh) ?: extractUrl(cmd)?.takeIf { !it.contains("localhost") }
    }

    /** Programme guide straight from the portal, keyed by channel url ("stalker:<id>"). */
    fun epg(hours: Int): Map<String, List<Programme>> {
        val data = call("type=itv&action=get_epg_info&period=$hours").optJSONObject("js")?.optJSONObject("data")
            ?: return emptyMap()
        val out = HashMap<String, List<Programme>>()
        val keys = data.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val arr = data.optJSONArray(id) ?: continue
            val list = ArrayList<Programme>(arr.length())
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val s = p.optLong("start_timestamp") * 1000
                val e = p.optLong("stop_timestamp") * 1000
                if (s <= 0 || e <= s) continue
                list.add(Programme(s, e, p.optString("name"), p.optString("descr").takeIf { it.isNotBlank() }))
            }
            if (list.isNotEmpty()) {
                list.sortBy { it.start }
                out["stalker:$id"] = list
            }
        }
        return out
    }

    /** Some portals drop the session if the box stops checking in. */
    fun keepAlive() {
        call("type=watchdog&action=get_events&event_active_id=0&init=0&cur_play_type=1")
    }

    // ---------------- Movies & series ----------------

    /** @param type "vod" (movies) or "series" */
    fun vodCategories(type: String): List<VodCategory> {
        val arr = call("type=$type&action=get_categories").optJSONArray("js") ?: return emptyList()
        val out = ArrayList<VodCategory>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isEmpty()) continue
            val title = o.optString("title").ifBlank { o.optString("alias") }
            out.add(VodCategory(id, if (id == "*") "All" else title))
        }
        return out
    }

    fun vodPage(type: String, category: String, page: Int): VodPage {
        val js = call(
            "type=$type&action=get_ordered_list&category=${URLEncoder.encode(category, "UTF-8")}" +
                "&genre=*&p=$page&sortby=added&fav=0&hd=0&not_ended=0&abc=*&years=*",
        ).optJSONObject("js") ?: return VodPage(emptyList(), 0)
        val total = js.optInt("total_items", 0)
        val perPage = js.optInt("max_page_items", 14).coerceAtLeast(1)
        val dataLen = js.optJSONArray("data")?.length() ?: 0
        // Some portals don't report a total: keep paging while pages come back full.
        if (total <= 0) {
            val items = ArrayList<VodItem>()
            val d = js.optJSONArray("data") ?: JSONArray()
            for (i in 0 until d.length()) d.optJSONObject(i)?.let { items.add(vodItem(it, type)) }
            return VodPage(items, if (dataLen >= perPage || dataLen >= 10) page + 1 else page)
        }
        val data = js.optJSONArray("data") ?: JSONArray()
        val items = ArrayList<VodItem>()
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            items.add(vodItem(o, type))
        }
        return VodPage(items, (total + perPage - 1) / perPage)
    }

    private fun vodItem(o: JSONObject, type: String): VodItem {
        val seriesArr = o.optJSONArray("series")
        val nums = ArrayList<Int>()
        if (seriesArr != null) for (i in 0 until seriesArr.length()) seriesArr.optString(i).toIntOrNull()?.let { nums.add(it) }
        val isSeries = type == "series" || o.optString("is_series") == "1" || nums.isNotEmpty()
        val poster = o.optString("screenshot_uri").ifBlank { o.optString("pic") }.ifBlank { o.optString("cover_big") }
        val minutes = o.optString("time").toIntOrNull() ?: 0
        return VodItem(
            id = o.optString("id"),
            title = o.optString("name").ifBlank { o.optString("o_name") },
            poster = logoUrl(poster),
            description = o.optString("description").takeIf { it.isNotBlank() && it != "null" },
            year = o.optString("year").takeIf { it.isNotBlank() && it != "null" && it != "N/A" },
            rating = (o.optString("rating_imdb").takeIf { it.isNotBlank() && it != "null" && it != "0" && it != "N/A" }
                ?: o.optString("rating_kinopoisk").takeIf { it.isNotBlank() && it != "null" && it != "0" }),
            genre = o.optString("genres_str").takeIf { it.isNotBlank() && it != "null" },
            minutes = minutes,
            isSeries = isSeries,
            cmd = o.optString("cmd").takeIf { it.isNotBlank() },
            episodeNumbers = nums,
            sourceType = type,
        )
    }

    fun seasons(item: VodItem): List<Season> {
        // Old style: the item itself lists its episode numbers.
        if (item.episodeNumbers.isNotEmpty() && item.cmd != null) {
            return listOf(Season("1", "Episodes", item.cmd, item.episodeNumbers))
        }
        val js = call("type=${item.sourceType}&action=get_ordered_list&movie_id=${enc(item.id)}&season_id=0&episode_id=0&p=1")
            .optJSONObject("js") ?: return emptyList()
        val data = js.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<Season>()
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            val nums = ArrayList<Int>()
            o.optJSONArray("series")?.let { a -> for (k in 0 until a.length()) a.optString(k).toIntOrNull()?.let { nums.add(it) } }
            out.add(Season(o.optString("id"), o.optString("name").ifBlank { "Season ${i + 1}" }, o.optString("cmd").takeIf { it.isNotBlank() }, nums))
        }
        return out
    }

    fun episodes(item: VodItem, season: Season): List<Episode> {
        if (season.episodeNumbers.isNotEmpty() && season.cmd != null) {
            return season.episodeNumbers.map { Episode("${item.id}:${season.id}:$it", "Episode $it", season.cmd, it) }
        }
        val out = ArrayList<Episode>()
        var page = 1
        var pages = 1
        while (page <= pages && page <= 30) {
            val js = call(
                "type=${item.sourceType}&action=get_ordered_list&movie_id=${enc(item.id)}&season_id=${enc(season.id)}&episode_id=0&p=$page",
            ).optJSONObject("js") ?: break
            val per = js.optInt("max_page_items", 14).coerceAtLeast(1)
            pages = (js.optInt("total_items", 0) + per - 1) / per
            val data = js.optJSONArray("data") ?: break
            for (i in 0 until data.length()) {
                val o = data.optJSONObject(i) ?: continue
                val cmd = o.optString("cmd").takeIf { it.isNotBlank() } ?: continue
                val num = o.optString("series_number").toIntOrNull()
                out.add(Episode(o.optString("id"), o.optString("name").ifBlank { "Episode ${num ?: (out.size + 1)}" }, cmd, num))
            }
            page++
        }
        return out
    }

    /** Fresh playable link for a movie, or for one episode of a series. */
    fun vodLink(cmd: String, series: Int?): String? {
        val js = call(
            "type=vod&action=create_link&cmd=${enc(cmd)}&series=${series ?: ""}&forced_storage=&disable_ad=0" +
                "&download=0&force_ch_link_check=0",
        ).optJSONObject("js")
        return extractUrl(js?.optString("cmd").orEmpty()) ?: extractUrl(cmd)?.takeIf { !it.contains("localhost") }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun subscriptionEnd(): String? {
        val js = call("type=account_info&action=get_main_info").optJSONObject("js") ?: return null
        return js.optString("end_date").takeIf { it.isNotBlank() && it != "null" }
            ?: js.optString("phone").takeIf { it.isNotBlank() && it != "null" }
    }

    companion object {
        const val UA = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"

        fun normalizeMac(m: String) = m.trim().uppercase().replace('-', ':')

        fun isValidMac(m: String) = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$").matches(normalizeMac(m))

        fun extractUrl(s: String): String? {
            val i = s.indexOf("http", ignoreCase = true)
            return if (i >= 0) s.substring(i).trim().substringBefore(' ') else null
        }

        private fun hash(alg: String, s: String) =
            MessageDigest.getInstance(alg).digest(s.toByteArray()).joinToString("") { "%02X".format(it) }
    }
}
