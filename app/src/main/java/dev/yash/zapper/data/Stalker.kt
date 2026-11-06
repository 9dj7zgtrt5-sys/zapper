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
class StalkerClient(portal: String, mac: String) {
    class AuthException(msg: String) : IOException(msg)

    val mac: String = normalizeMac(mac)
    private val portalUrl = portal.trim().let { if (it.startsWith("http", ignoreCase = true)) it else "http://$it" }
    @Volatile private var api: String? = null
    @Volatile private var token: String? = null
    private val sn = hash("MD5", this.mac).take(13)
    private val deviceId = hash("SHA-256", this.mac)
    private val signature = hash("SHA-256", sn + this.mac)

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
        list.add("$r/server/load.php")
        list.add("$r/portal.php")
        list.add("$r/stalker_portal/server/load.php")
        return list.distinct()
    }

    private fun get(apiUrl: String, query: String): JSONObject {
        val c = URL("$apiUrl?$query&JsHttpRequest=1-xml").openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 45_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("X-User-Agent", "Model: MAG250; Link: WiFi")
        c.setRequestProperty("Referer", root() + "/c/")
        c.setRequestProperty("Accept", "*/*")
        c.setRequestProperty(
            "Cookie",
            "mac=${URLEncoder.encode(mac, "UTF-8")}; stb_lang=en; timezone=${URLEncoder.encode(TimeZone.getDefault().id, "UTF-8")}",
        )
        token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        try {
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == 401 || code == 403) throw AuthException("Portal refused access (HTTP $code)")
            if (code !in 200..299) throw IOException("Portal error (HTTP $code)")
            if (body.contains("Authorization failed", ignoreCase = true)) throw AuthException("Authorization failed")
            val start = body.indexOf('{')
            if (start < 0) throw IOException("Unexpected reply from portal")
            return JSONObject(body.substring(start))
        } finally {
            c.disconnect()
        }
    }

    /** Handshake + profile. Tries the usual API paths until one answers. */
    @Synchronized
    fun connect() {
        var lastErr: Exception? = null
        for (a in api?.let { listOf(it) } ?: candidates()) {
            try {
                token = null
                val hs = get(a, "type=stb&action=handshake&token=&prehash=0")
                val t = hs.optJSONObject("js")?.optString("token").orEmpty()
                if (t.isEmpty()) throw IOException("Portal did not return a session")
                token = t
                api = a
                val profile = get(
                    a,
                    "type=stb&action=get_profile&hd=1&ver=ImageDescription%3A%200.2.18-r23-250&num_banks=2" +
                        "&sn=$sn&stb_type=MAG250&image_version=218&video_out=hdmi&device_id=$deviceId" +
                        "&device_id2=$deviceId&signature=$signature&auth_second_step=1&hw_version=1.7-BD-00" +
                        "&not_valid_token=0&api_signature=262&prehash=",
                )
                val js = profile.optJSONObject("js")
                val block = js?.optString("block_msg").orEmpty()
                if (block.isNotBlank()) throw AuthException(block)
                return
            } catch (e: Exception) {
                lastErr = e
                if (e is AuthException && api != null) break
            }
        }
        api = null
        throw lastErr ?: IOException("Portal not reachable")
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
