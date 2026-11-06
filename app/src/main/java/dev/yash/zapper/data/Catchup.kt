package dev.yash.zapper.data

import java.util.Calendar
import java.util.TimeZone

/** Builds archive (catch-up) URLs for the common IPTV catch-up conventions. */
object Catchup {
    private val placeholder = Regex("""\$?\{([a-zA-Z()]+)(?::([^}]*))?\}""")
    private val xcRegex = Regex("""^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\d+)(\.[a-z0-9]+)?$""")
    private val fsTsRegex = Regex("""^(https?://[^/]+/.*)/mpegts(\?.*)?$""")
    private val fsHlsRegex = Regex("""^(https?://[^/]+/.*)/([^/]+)\.m3u8(\?.*)?$""")

    fun available(ch: Channel, p: Programme, now: Long): Boolean {
        val c = ch.catchup ?: return false
        return p.start < now && p.start >= now - c.days * TimeUtil.DAY
    }

    /**
     * @param tzOffsetMs offset added to times when formatting dates (for providers that expect
     *        local server time in {Y}{m}{d} style placeholders or Xtream timeshift URLs).
     */
    fun build(ch: Channel, startMs: Long, endMs: Long, nowMs: Long, tzOffsetMs: Long = 0): String? {
        val c = ch.catchup ?: return null
        val url = ch.url
        val t = Times(startMs, minOf(endMs, nowMs).coerceAtLeast(startMs + TimeUtil.MIN), nowMs, tzOffsetMs)
        return when (c.type) {
            "append" -> c.source?.let { url + fillT(it, t) }
            "default" -> c.source?.let { src ->
                if (src.startsWith("http", ignoreCase = true)) fillT(src, t) else url + fillT(src, t)
            }
            "shift", "timeshift" -> url + (if ('?' in url) "&" else "?") + "utc=${t.start / 1000}&lutc=${t.now / 1000}"
            "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> flussonic(url, t)
            "xc" -> xtream(url, t)
            else -> c.source?.let { src ->
                if (src.startsWith("http", ignoreCase = true)) fillT(src, t) else url + fillT(src, t)
            } ?: xtream(url, t)
        }
    }

    private class Times(val start: Long, val end: Long, val now: Long, val tz: Long)

    private fun flussonic(url: String, t: Times): String? {
        val s = t.start / 1000
        val dur = (t.end - t.start) / 1000
        fsTsRegex.find(url)?.let { m ->
            return "${m.groupValues[1]}/timeshift_abs-$s.ts${m.groupValues[2]}"
        }
        fsHlsRegex.find(url)?.let { m ->
            return "${m.groupValues[1]}/${m.groupValues[2]}-$s-$dur.m3u8${m.groupValues[3]}"
        }
        return null
    }

    private fun xtream(url: String, t: Times): String? {
        val m = xcRegex.find(url) ?: return null
        val (host, user, pass, id) = m.destructured
        val ext = m.groupValues[5].ifEmpty { ".ts" }
        val minutes = ((t.end - t.start) / TimeUtil.MIN).coerceAtLeast(1)
        val start = format(t.start + t.tz, "Y-m-d:H-M")
        return "$host/timeshift/$user/$pass/$minutes/$start/$id$ext"
    }

    private fun fillT(template: String, t: Times): String = placeholder.replace(template) { m ->
        val key = m.groupValues[1].lowercase()
        val arg = m.groupValues[2]
        val dur = (t.end - t.start) / 1000
        val base: Long? = when (key) {
            "utc", "start", "(b)" -> t.start
            "utcend", "end", "(e)" -> t.end
            "lutc", "now", "timestamp" -> t.now
            else -> null
        }
        when {
            base != null && arg.isNotEmpty() && arg.any { it.isLetter() } -> format(base + t.tz, arg)
            base != null && key.startsWith("(") -> format(base + t.tz, arg.ifEmpty { "YmdHMS" })
            base != null -> (base / 1000).toString()
            key == "duration" -> (dur / (arg.toLongOrNull()?.coerceAtLeast(1) ?: 1)).toString()
            key == "offset" -> ((t.now - t.start) / 1000 / (arg.toLongOrNull()?.coerceAtLeast(1) ?: 1)).toString()
            key in setOf("y", "m", "d", "h", "s") || m.groupValues[1] == "M" ->
                format(t.start + t.tz, m.groupValues[1])
            else -> m.value
        }
    }

    // Overload so callers outside can use fill with raw numbers (used by tests).
    fun fill(template: String, start: Long, end: Long, now: Long, tz: Long = 0) = fillT(template, Times(start, end, now, tz))

    /** Formats epoch millis (already shifted) using strftime-like letters Y m d H M S. */
    fun format(ms: Long, pattern: String): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = ms
        val sb = StringBuilder()
        for (ch in pattern) {
            when (ch) {
                'Y' -> sb.append(cal.get(Calendar.YEAR))
                'm' -> sb.append(pad(cal.get(Calendar.MONTH) + 1))
                'd' -> sb.append(pad(cal.get(Calendar.DAY_OF_MONTH)))
                'H' -> sb.append(pad(cal.get(Calendar.HOUR_OF_DAY)))
                'M' -> sb.append(pad(cal.get(Calendar.MINUTE)))
                'S' -> sb.append(pad(cal.get(Calendar.SECOND)))
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun pad(n: Int) = if (n < 10) "0$n" else n.toString()
}
