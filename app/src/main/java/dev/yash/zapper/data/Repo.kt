package dev.yash.zapper.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/** App-wide state: settings, playlist, EPG and favorites. */
object Repo {
    private const val STALE_MS = 12 * TimeUtil.HOUR
    const val DEFAULT_UA = "Zapper/1.0 (Linux; Android)"

    private lateinit var prefs: SharedPreferences
    private lateinit var dir: File
    val io = Executors.newSingleThreadExecutor()
    val main = Handler(Looper.getMainLooper())

    @Volatile var channels: List<Channel> = emptyList(); private set
    @Volatile private var playlistEpgUrls: List<String> = emptyList()
    /** channel url -> programmes */
    @Volatile var epg: Map<String, List<Programme>> = emptyMap(); private set

    private val favorites = ArrayList<String>()

    fun store(): SharedPreferences = prefs

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.getSharedPreferences("zapper", Context.MODE_PRIVATE)
        dir = File(ctx.filesDir, "cache").apply { mkdirs() }
        favorites.clear()
        prefs.getString("favorites", "")!!.split('\n').filter { it.isNotEmpty() }.forEach { favorites.add(it) }
    }

    // ---- settings ----
    val m3uUrl: String get() = prefs.getString("m3u", "")!!
    /** "m3u" or "portal" */
    val source: String get() = prefs.getString("source", "m3u")!!
    val isPortal: Boolean get() = source == "portal"
    val portalUrl: String get() = prefs.getString("portal", "")!!
    /** Optional box identity overrides (serial, device IDs, signature) for MAC-locked portals. */
    val stbSn: String get() = prefs.getString("stb_sn", "")!!
    val stbDeviceId: String get() = prefs.getString("stb_dev1", "")!!
    val stbDeviceId2: String get() = prefs.getString("stb_dev2", "")!!
    val stbSignature: String get() = prefs.getString("stb_sig", "")!!

    private val profileKey get() = "stb_profile|$portalUrl|$mac"

    fun newStalker() = StalkerClient(
        portalUrl, mac, stbSn, stbDeviceId, stbDeviceId2, stbSignature,
        remembered = prefs.getString(profileKey, "")!!,
    )

    /** Remembers which API path / box type the portal accepted, so later launches don't probe. */
    fun rememberStalker(c: StalkerClient) {
        c.workingProfile?.let { prefs.edit().putString(profileKey, it).apply() }
    }
    /** MAC used with the portal: the one entered in settings, else this TV's own generated MAC. */
    val mac: String get() = prefs.getString("mac", "")!!.ifBlank { deviceMac }
    val isConfigured: Boolean
        get() = if (isPortal) portalUrl.isNotBlank() else m3uUrl.isNotBlank()

    /** A MAG-style MAC (00:1A:79:xx:xx:xx) created once for this TV, to give to the provider. */
    val deviceMac: String
        @Synchronized get() {
            prefs.getString("devmac", null)?.let { return it }
            val m = generateMac()
            prefs.edit().putString("devmac", m).commit()
            return m
        }

    fun generateMac(): String {
        val b = ByteArray(3)
        java.security.SecureRandom().nextBytes(b)
        return "00:1A:79:" + b.joinToString(":") { "%02X".format(it) }
    }

    /** Replaces this TV's MAC with a new random one and starts using it. */
    @Synchronized
    fun regenerateMac(): String {
        val m = generateMac()
        prefs.edit().putString("devmac", m).putString("mac", m).putInt("cfgver", configVersion + 1).commit()
        return m
    }
    val epgUrl: String get() = prefs.getString("epg", "")!!
    val userAgent: String get() = prefs.getString("ua", "")!!.ifBlank { DEFAULT_UA }
    val customUserAgent: String get() = prefs.getString("ua", "")!!
    val tzOffsetHours: Float get() = prefs.getFloat("tz", 0f)
    val tzOffsetMs: Long get() = (tzOffsetHours * TimeUtil.HOUR).toLong()
    val reverseZap: Boolean get() = prefs.getBoolean("reverse", false)
    val autoStart: Boolean get() = prefs.getBoolean("autostart", true)
    val requireVpn: Boolean get() = prefs.getBoolean("requirevpn", true)
    val hideVod: Boolean get() = prefs.getBoolean("hidevod", false)
    val vpnPackage: String get() = prefs.getString("vpnpkg", "")!!
    val hiddenGroups: Set<String>
        get() = prefs.getString("hidden", "")!!.split('\n').filter { it.isNotEmpty() }.toSet()
    /** Adult categories (live, movies, series) are hidden unless this is ticked on the phone page. */
    val showAdult: Boolean get() = prefs.getBoolean("showadult", false)
    /** Movie/series categories hidden on the phone page, as "m:<title>" / "s:<title>". */
    val hiddenVod: Set<String>
        get() = prefs.getString("hiddenvod", "")!!.split('\n').filter { it.isNotEmpty() }.toSet()

    private val adultWords = Regex(
        "(?i)(?<![a-z])(xxx|adults?|18\\s*\\+|\\+\\s*18|porn\\w*|erotic\\w*|sexy?|playboy|hustler|brazzers|for adults|nsfw|hot ?club)(?![a-z])",
    )

    fun isAdult(name: String): Boolean = adultWords.containsMatchIn(name)

    /** Live categories not shown on the TV: the ones unticked on the phone, plus adult ones unless allowed. */
    val effectiveHidden: Set<String>
        get() {
            val h = hiddenGroups
            if (showAdult) return h
            val out = HashSet(h)
            for (c in channels) if (!c.isVod && isAdult(c.group)) out.add(c.group)
            return out
        }

    /** Whether a movie/series category should be listed on the TV. */
    fun vodCategoryVisible(series: Boolean, title: String): Boolean =
        (showAdult || !isAdult(title)) && (if (series) "s:" else "m:") + title !in hiddenVod

    /** Movie and series categories, fetched once for the phone page. Background thread only. */
    @Volatile private var vodCatsCache: Pair<List<VodCategory>, List<VodCategory>>? = null

    fun vodCategoriesForSettings(): Pair<List<VodCategory>, List<VodCategory>> {
        vodCatsCache?.let { return it }
        val src = vod() ?: return emptyList<VodCategory>() to emptyList()
        val m = try { src.categories(false) } catch (_: Exception) { return emptyList<VodCategory>() to emptyList() }
        val s = try { if (src.hasSeriesSection) src.categories(true) else emptyList() } catch (_: Exception) { emptyList() }
        val pair = m.filter { it.id != "*" } to s.filter { it.id != "*" }
        vodCatsCache = pair
        return pair
    }

    /** Bumped when the playlist/EPG source changes (needs re-download). */
    val configVersion: Int get() = prefs.getInt("cfgver", 0)
    /** Bumped when only display settings change (no download). */
    val displayVersion: Int get() = prefs.getInt("dispver", 0)
    /** Packages shown under "Apps" in Zapper; null = not chosen yet (use defaults). */
    val shownApps: List<String>?
        get() = prefs.getString("apps", null)?.split('\n')?.filter { it.isNotEmpty() }

    fun saveShownApps(pkgs: List<String>) {
        prefs.edit().putString("apps", pkgs.joinToString("\n")).putInt("dispver", displayVersion + 1).apply()
    }

    var helpShown: Boolean
        get() = prefs.getBoolean("helpshown", false)
        set(v) { prefs.edit().putBoolean("helpshown", v).apply() }

    class Settings(
        val source: String = Repo.source,
        val portal: String = portalUrl,
        val mac: String = Repo.mac,
        val m3u: String = m3uUrl,
        val epg: String = epgUrl,
        val ua: String = customUserAgent,
        val tz: Float = tzOffsetHours,
        val reverse: Boolean = reverseZap,
        val autoStart: Boolean = Repo.autoStart,
        val requireVpn: Boolean = Repo.requireVpn,
        val hideVod: Boolean = Repo.hideVod,
        val vpnPackage: String = Repo.vpnPackage,
        val stbSn: String = Repo.stbSn,
        val stbDeviceId: String = Repo.stbDeviceId,
        val stbDeviceId2: String = Repo.stbDeviceId2,
        val stbSignature: String = Repo.stbSignature,
        val hiddenGroups: Set<String> = Repo.hiddenGroups,
        val showAdult: Boolean = Repo.showAdult,
        val hiddenVod: Set<String> = Repo.hiddenVod,
        val forceReload: Boolean = false,
    )

    @Synchronized
    fun saveConfig(s: Settings) {
        val mac = StalkerClient.normalizeMac(s.mac)
        val sourceChanged = s.stbSn.trim() != stbSn || s.stbDeviceId.trim() != stbDeviceId ||
            s.stbDeviceId2.trim() != stbDeviceId2 || s.stbSignature.trim() != stbSignature ||
            s.m3u.trim() != m3uUrl || s.epg.trim() != epgUrl || s.ua.trim() != customUserAgent ||
            s.source != source || s.portal.trim() != portalUrl || mac != this.mac
        val e = prefs.edit()
            .putString("source", s.source)
            .putString("portal", s.portal.trim())
            .putString("mac", mac)
            .putString("m3u", s.m3u.trim())
            .putString("epg", s.epg.trim())
            .putString("ua", s.ua.trim())
            .putFloat("tz", s.tz)
            .putBoolean("reverse", s.reverse)
            .putBoolean("autostart", s.autoStart)
            .putBoolean("requirevpn", s.requireVpn)
            .putBoolean("hidevod", s.hideVod)
            .putString("vpnpkg", s.vpnPackage)
            .putString("stb_sn", s.stbSn.trim())
            .putString("stb_dev1", s.stbDeviceId.trim())
            .putString("stb_dev2", s.stbDeviceId2.trim())
            .putString("stb_sig", s.stbSignature.trim())
            .putString("hidden", s.hiddenGroups.joinToString("\n"))
            .putBoolean("showadult", s.showAdult)
            .putString("hiddenvod", s.hiddenVod.joinToString("\n"))
        if (sourceChanged || s.forceReload) e.putInt("cfgver", configVersion + 1)
        else e.putInt("dispver", displayVersion + 1)
        e.commit()
    }

    var lastChannelUrl: String?
        get() = prefs.getString("last_url", null)
        set(v) { prefs.edit().putString("last_url", v).apply() }
    var lastGroup: String?
        get() = prefs.getString("last_group", null)
        set(v) { prefs.edit().putString("last_group", v).apply() }

    // ---- favorites ----
    fun isFavorite(ch: Channel) = favorites.contains(ch.url)

    fun toggleFavorite(ch: Channel): Boolean {
        val added = if (favorites.remove(ch.url)) false else { favorites.add(ch.url); true }
        prefs.edit().putString("favorites", favorites.joinToString("\n")).apply()
        return added
    }

    // ---- recently watched ----
    private const val RECENT_MAX = 12

    val recentUrls: List<String>
        get() = prefs.getString("recent", "")!!.split('\n').filter { it.isNotEmpty() }

    /** Moves a channel to the front of "Recently watched". */
    fun addRecent(url: String) {
        val list = recentUrls.toMutableList()
        list.remove(url)
        list.add(0, url)
        prefs.edit().putString("recent", list.take(RECENT_MAX).joinToString("\n")).apply()
    }

    // ---- groups ----
    /** Visible groups: Favorites, All channels, then playlist groups (minus hidden ones). */
    fun groups(): List<Group> {
        val all = channels
        val hidden = effectiveHidden
        val allLive = all.filter { !it.isVod }
        val live = allLive.filter { it.group !in hidden }
        // Number visible channels 1..N so the numbers stay small and gap-free.
        var n = 0
        for (c in live) c.number = ++n
        val byUrl = HashMap<String, Channel>()
        for (c in allLive) if (!byUrl.containsKey(c.url)) byUrl[c.url] = c
        val out = ArrayList<Group>()
        // Movies, Series and Help live in the section tabs above the lists, not among channel categories.
        val visible = live.mapTo(HashSet()) { it.url }
        out.add(Group(RECENT_TITLE, recentUrls.filter { it in visible }.mapNotNull { byUrl[it] }, Group.Kind.RECENT))
        out.add(Group(FAVORITES_TITLE, favorites.mapNotNull { byUrl[it] }, Group.Kind.FAVORITES))
        out.add(Group(ALL_TITLE, live, Group.Kind.ALL))
        val liveGroups = LinkedHashMap<String, ArrayList<Channel>>()
        for (c in all) {
            if (c.isVod || c.group in hidden) continue
            liveGroups.getOrPut(c.group) { ArrayList() }.add(c)
        }
        for ((k, v) in liveGroups) out.add(Group(k, v, Group.Kind.LIVE))
        return out
    }

    const val RECENT_TITLE = "Recently watched"
    const val FAVORITES_TITLE = "Favorites"
    const val ALL_TITLE = "All channels"

    /** Whether Movies and Series exist for this source and aren't switched off. */
    val hasVod: Boolean get() = !hideVod && (isPortal || channels.any { it.isVod })

    /** All group names in the playlist with channel counts (for the phone settings page). */
    fun allGroupNames(): List<Pair<String, Int>> {
        val m = LinkedHashMap<String, Int>()
        for (c in channels) if (!c.isVod) m[c.group] = (m[c.group] ?: 0) + 1
        return m.toList()
    }

    val epgChannelCount: Int get() = epg.size

    fun programmes(ch: Channel): List<Programme> = epg[ch.url] ?: emptyList()

    val maxCatchupDays: Int get() = channels.maxOfOrNull { it.catchup?.days ?: 0 } ?: 0

    // ---- loading (call on io thread) ----
    private fun cacheFile(prefix: String, url: String): File {
        val md = MessageDigest.getInstance("MD5").digest(url.toByteArray())
        return File(dir, prefix + "_" + md.joinToString("") { "%02x".format(it) })
    }

    /** Returns a usable local copy, downloading if missing/stale/forced. Throws if nothing usable. */
    private fun fetch(prefix: String, url: String, force: Boolean): File {
        val f = cacheFile(prefix, url)
        val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < STALE_MS
        if (force || !fresh) {
            try {
                Http.download(url, userAgent, f)
            } catch (e: Exception) {
                if (!f.exists()) throw e
            }
        }
        return f
    }

    /** @return error message or null */
    fun loadPlaylist(force: Boolean): String? {
        vodCatsCache = null
        if (isPortal) return loadPortal()
        stalker = null
        val url = m3uUrl
        if (url.isBlank()) return "No playlist URL set"
        return try {
            val f = fetch("m3u", url, force)
            val pl = Http.openMaybeGzip(f).use { M3uParser.parse(it) }
            channels = pl.channels
            playlistEpgUrls = pl.epgUrls
            dir.listFiles()?.filter { it.name.startsWith("m3u_") && it != f }?.forEach { it.delete() }
            if (pl.channels.isEmpty()) "Playlist has no channels" else null
        } catch (e: Exception) {
            "Playlist failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    // ---- Stalker portal ----
    @Volatile private var stalker: StalkerClient? = null
    @Volatile var subscriptionEnd: String? = null; private set
    /** Separate thread for stream links so they never wait behind a long guide download. */
    val linkExec = Executors.newSingleThreadExecutor()

    private fun loadPortal(): String? {
        if (portalUrl.isBlank()) return "Portal URL missing"
        return try {
            val c = newStalker()
            val list = c.channels()
            stalker = c
            rememberStalker(c)
            channels = list
            playlistEpgUrls = emptyList()
            subscriptionEnd = try { c.subscriptionEnd() } catch (_: Exception) { null }
            if (list.isEmpty()) "The portal sent no channels. Check the MAC address is the one your provider activated." else null
        } catch (e: StalkerClient.BlockedException) {
            "Your provider has temporarily blocked this internet connection (${e.message}).\n\n" +
                "Restart your Wi-Fi router (unplug it for 30 seconds), or wait about 30 minutes,\nthen press OK."
        } catch (e: StalkerClient.AuthException) {
            "The portal refused MAC $mac (${e.message}).\nIf your provider needs a VPN, turn it on. Otherwise check the MAC matches what they activated."
        } catch (e: Exception) {
            "Couldn't connect to the portal: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Stream URL to play for a channel (asks the portal for a fresh link if needed). Background thread only. */
    fun resolveStream(ch: Channel, attempt: Int = 0): String? {
        val cmd = ch.cmd ?: return ch.url
        val c = stalker ?: return null
        // Links are tied to the address we signed in from: after two failures, sign in again.
        if (attempt == 2) c.resetSession()
        return try { c.createLink(cmd) } catch (e: Exception) { android.util.Log.w("Zapper", "create_link failed", e); null }
    }

    /** The network changed (VPN reconnected, new location, Wi-Fi back): the portal session is no longer valid. */
    fun networkChanged() {
        stalker?.resetSession()
    }

    /** Movies & series for the current source, or null if there are none. */
    fun vod(): VodSource? {
        if (isPortal) return stalker?.let { PortalVod(it) }
        val all = channels
        return if (all.any { it.isVod }) M3uVod(all) else null
    }

    /** Stream URL for a movie/episode. Background thread only. */
    fun vodLink(r: PlayRequest, attempt: Int = 0): String? {
        if (r.direct) return r.cmd
        val c = stalker ?: return null
        if (attempt == 2) c.resetSession()
        return try { PortalVod.link(c, r) } catch (_: Exception) { null }
    }

    fun keepAlive() {
        try { stalker?.keepAlive() } catch (_: Exception) {}
    }

    fun loadEpg(force: Boolean): String? {
        val portal = stalker
        if (isPortal && portal != null && epgUrl.isBlank()) {
            return try {
                epg = portal.epg(36)
                if (epg.isEmpty()) "The portal has no TV guide" else null
            } catch (e: Exception) {
                "EPG failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        val urls = epgUrl.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { playlistEpgUrls }
        if (urls.isEmpty()) return null
        val live = channels.filter { !it.isVod }
        val wantedIds = live.mapNotNull { it.tvgId?.lowercase() }.toHashSet()
        val wantedNames = HashSet<String>()
        for (c in live) {
            c.tvgName?.let { wantedNames.add(XmlTvParser.norm(it)) }
            wantedNames.add(XmlTvParser.norm(c.name))
        }
        val now = System.currentTimeMillis()
        val from = now - maxOf(maxCatchupDays * TimeUtil.DAY, 6 * TimeUtil.HOUR)
        val to = now + 4 * TimeUtil.DAY

        val merged = HashMap<String, List<Programme>>()
        var error: String? = null
        val keep = HashSet<File>()
        for (u in urls) {
            try {
                val f = fetch("epg", u, force)
                keep.add(f)
                val parsed = Http.openMaybeGzip(f).use { XmlTvParser.parse(it, wantedIds, wantedNames, from, to) }
                for (c in live) {
                    if (merged.containsKey(c.url)) continue
                    val id = c.tvgId?.lowercase()?.takeIf { parsed.programmes.containsKey(it) }
                        ?: c.tvgName?.let { parsed.nameToId[XmlTvParser.norm(it)] }
                        ?: parsed.nameToId[XmlTvParser.norm(c.name)]
                    val list = id?.let { parsed.programmes[it] }
                    if (!list.isNullOrEmpty()) merged[c.url] = list
                }
            } catch (e: Exception) {
                error = "EPG failed: ${e.message ?: e.javaClass.simpleName}"
            } catch (e: OutOfMemoryError) {
                error = "EPG too large for this device"
            }
        }
        dir.listFiles()?.filter { it.name.startsWith("epg_") && it !in keep && !it.name.endsWith(".part") }?.forEach { it.delete() }
        epg = merged
        return error
    }
}
