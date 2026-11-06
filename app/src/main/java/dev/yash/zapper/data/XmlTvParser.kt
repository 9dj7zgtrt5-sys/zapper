package dev.yash.zapper.data

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream

class ParsedEpg(
    /** lower-cased channel id -> programmes sorted by start */
    val programmes: Map<String, List<Programme>>,
    /** normalized display name -> lower-cased channel id */
    val nameToId: Map<String, String>,
)

object XmlTvParser {
    fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Streams an XMLTV document, keeping only programmes for channels that the playlist can use
     * and that fall inside [from, to].
     */
    fun parse(
        input: InputStream,
        wantedIds: Set<String>,
        wantedNames: Set<String>,
        from: Long,
        to: Long,
    ): ParsedEpg {
        val factory = XmlPullParserFactory.newInstance()
        val p = factory.newPullParser()
        p.setInput(input, null)

        val nameToId = HashMap<String, String>()
        val accepted = HashSet<String>()
        val result = HashMap<String, ArrayList<Programme>>()

        var channelId: String? = null
        var progChannel: String? = null
        var progStart = 0L
        var progStop = 0L
        var title: String? = null
        var desc: String? = null
        var keep = false

        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (p.name) {
                    "channel" -> {
                        channelId = p.getAttributeValue(null, "id")?.lowercase()
                        if (channelId != null && channelId in wantedIds) accepted.add(channelId)
                    }
                    "display-name" -> if (channelId != null) {
                        val n = norm(p.nextText())
                        if (n.isNotEmpty()) {
                            if (!nameToId.containsKey(n)) nameToId[n] = channelId
                            if (n in wantedNames) accepted.add(channelId)
                        }
                    }
                    "programme" -> {
                        channelId = null
                        progChannel = p.getAttributeValue(null, "channel")?.lowercase()
                        progStart = TimeUtil.parseXmltv(p.getAttributeValue(null, "start") ?: "")
                        progStop = TimeUtil.parseXmltv(p.getAttributeValue(null, "stop") ?: "")
                        keep = progChannel != null && progChannel in accepted &&
                            progStart > 0 && progStop > progStart && progStop >= from && progStart <= to
                        title = null
                        desc = null
                    }
                    "title" -> if (keep && title == null) title = p.nextText()
                    "desc" -> if (keep && desc == null) desc = p.nextText()
                }
            } else if (event == XmlPullParser.END_TAG) {
                when (p.name) {
                    "channel" -> channelId = null
                    "programme" -> {
                        if (keep) {
                            result.getOrPut(progChannel!!) { ArrayList() }
                                .add(Programme(progStart, progStop, title?.trim() ?: "", desc?.trim()))
                        }
                        keep = false
                    }
                }
            }
            event = p.next()
        }
        for (list in result.values) list.sortBy { it.start }
        return ParsedEpg(result, nameToId)
    }
}
