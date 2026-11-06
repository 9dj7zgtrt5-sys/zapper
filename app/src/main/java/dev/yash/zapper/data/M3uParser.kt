package dev.yash.zapper.data

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

class Playlist(val channels: List<Channel>, val epgUrls: List<String>)

object M3uParser {
    private val attrRegex = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")
    private val vodExt = setOf("mp4", "mkv", "avi", "mov", "m4v", "wmv", "flv", "webm")

    fun parse(input: InputStream): Playlist {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8), 64 * 1024)
        val out = ArrayList<Channel>()
        val epgUrls = ArrayList<String>()
        var headerCatchup: String? = null
        var headerCatchupSource: String? = null
        var headerCatchupDays = 0

        var attrs: Map<String, String>? = null
        var name: String? = null
        var extGroup: String? = null
        var ua: String? = null

        reader.forEachLine { raw ->
            val line = raw.trim().removePrefix("﻿")
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val a = attrs(line)
                    (a["url-tvg"] ?: a["x-tvg-url"])?.split(',')?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }?.let { epgUrls.addAll(it) }
                    headerCatchup = a["catchup"]
                    headerCatchupSource = a["catchup-source"]
                    headerCatchupDays = a["catchup-days"]?.toIntOrNull() ?: 0
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    attrs = attrs(line)
                    name = displayName(line)
                    extGroup = null
                    ua = null
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> extGroup = line.substring(8).trim()
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = line.substring(11).trim()
                    if (opt.startsWith("http-user-agent=", ignoreCase = true)) ua = opt.substringAfter('=')
                }
                line.startsWith("#") -> Unit
                else -> {
                    val a = attrs ?: emptyMap()
                    val url = line
                    val group = a["group-title"]?.takeIf { it.isNotBlank() } ?: extGroup ?: "Uncategorized"
                    val days = a["catchup-days"]?.toIntOrNull() ?: a["timeshift"]?.toIntOrNull()
                        ?: a["tvg-rec"]?.toIntOrNull() ?: headerCatchupDays
                    val type = a["catchup"] ?: a["catchup-type"] ?: headerCatchup
                        ?: if (a.containsKey("timeshift") || a.containsKey("tvg-rec")) "shift" else null
                    val source = a["catchup-source"] ?: headerCatchupSource
                    val catchup = if (type != null && days > 0) CatchupInfo(type.lowercase(), source, days) else null
                    out.add(
                        Channel(
                            name = name?.takeIf { it.isNotBlank() } ?: a["tvg-name"] ?: url,
                            url = url,
                            group = group,
                            logo = a["tvg-logo"]?.takeIf { it.isNotBlank() },
                            tvgId = a["tvg-id"]?.takeIf { it.isNotBlank() },
                            tvgName = a["tvg-name"]?.takeIf { it.isNotBlank() },
                            catchup = catchup,
                            isVod = isVod(url),
                            userAgent = ua ?: a["http-user-agent"] ?: a["user-agent"],
                        )
                    )
                    attrs = null; name = null; extGroup = null; ua = null
                }
            }
        }
        var n = 0
        for (c in out) if (!c.isVod) c.number = ++n
        return Playlist(out, epgUrls)
    }

    private fun attrs(line: String): Map<String, String> {
        val m = HashMap<String, String>()
        for (r in attrRegex.findAll(line)) m[r.groupValues[1].lowercase()] = r.groupValues[2]
        return m
    }

    /** Text after the first comma that is not inside quotes. */
    private fun displayName(line: String): String {
        var inQuote = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') inQuote = !inQuote
            else if (c == ',' && !inQuote) return line.substring(i + 1).trim()
        }
        return ""
    }

    fun isVod(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        if (path.contains("/movie/") || path.contains("/series/")) return true
        return path.substringAfterLast('.', "") in vodExt
    }
}
