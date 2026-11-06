package dev.yash.zapper.data

/** One playable entry from the M3U playlist (live channel or VOD item). */
class Channel(
    val name: String,
    val url: String,
    val group: String,
    val logo: String?,
    val tvgId: String?,
    val tvgName: String?,
    val catchup: CatchupInfo?,
    val isVod: Boolean,
    val userAgent: String?,
    /** Stalker portal command; when set, a fresh stream link is requested at play time. */
    val cmd: String? = null,
) {
    /** 1-based number within the full live list (0 for VOD). */
    var number: Int = 0
}

class CatchupInfo(val type: String, val source: String?, val days: Int)

class Programme(val start: Long, val stop: Long, val title: String, val desc: String?)

class Group(val title: String, val channels: List<Channel>, val kind: Kind) {
    enum class Kind { FAVORITES, ALL, LIVE, MOVIES, SERIES, APPS, HELP }
}

/** Binary-search helpers over a start-sorted programme list. */
object Epg {
    /** Index of the last programme with start <= t, or -1. */
    fun lastStartingBefore(list: List<Programme>, t: Long): Int {
        var lo = 0
        var hi = list.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].start <= t) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    fun at(list: List<Programme>, t: Long): Programme? {
        val i = lastStartingBefore(list, t)
        if (i < 0) return null
        val p = list[i]
        return if (p.stop > t) p else null
    }

    /** First index whose programme ends after t. */
    fun firstEndingAfter(list: List<Programme>, t: Long): Int {
        var i = lastStartingBefore(list, t)
        if (i < 0) return 0
        if (list[i].stop <= t) i++
        return i
    }

    fun next(list: List<Programme>, t: Long): Programme? {
        val i = lastStartingBefore(list, t) + 1
        return list.getOrNull(i)
    }
}
