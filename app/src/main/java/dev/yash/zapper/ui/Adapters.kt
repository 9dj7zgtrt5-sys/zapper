package dev.yash.zapper.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import dev.yash.zapper.R
import dev.yash.zapper.data.Channel
import dev.yash.zapper.data.Epg
import dev.yash.zapper.data.Group
import dev.yash.zapper.data.Repo

class GroupAdapter : BaseAdapter() {
    var items: List<Group> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }
    /** Category whose channels are showing; marked even when focus is on the channel list. */
    var activeIndex = -1
        set(v) { if (field != v) { field = v; notifyDataSetChanged() } }
    var movieCount: Int? = null
    var seriesCount: Int? = null

    override fun getCount() = items.size
    override fun getItem(position: Int) = items[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_group, parent, false)
            .also { Fonts.apply(it) }
        val g = items[position]
        v.findViewById<TextView>(R.id.groupName).text = Display.pretty(g.title)
        v.findViewById<TextView>(R.id.groupCount).text = when (g.kind) {
            Group.Kind.MOVIES -> movieCount?.let { "%,d".format(it) } ?: "›"
            Group.Kind.SERIES -> seriesCount?.let { "%,d".format(it) } ?: "›"
            Group.Kind.APPS, Group.Kind.HELP -> "›"
            else -> "%,d".format(g.channels.size)
        }
        val lastFixed = position + 1 < items.size && g.kind != Group.Kind.LIVE && items[position + 1].kind == Group.Kind.LIVE
        v.setBackgroundResource(
            when {
                position == activeIndex -> R.drawable.group_active
                lastFixed -> R.drawable.group_divider
                else -> 0
            },
        )
        return v
    }
}

class ChannelAdapter : BaseAdapter() {
    var items: List<Channel> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }
    var playingUrl: String? = null

    override fun getCount() = items.size
    override fun getItem(position: Int) = items[position]
    override fun getItemId(position: Int) = position.toLong()

    private class Holder(v: View) {
        val num: TextView = v.findViewById(R.id.chNum)
        val logo: ImageView = v.findViewById(R.id.chLogo)
        val name: TextView = v.findViewById(R.id.chName)
        val now: TextView = v.findViewById(R.id.chNow)
        val progress: ProgressBar = v.findViewById(R.id.chProgress)
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
            .also { Fonts.apply(it); it.tag = Holder(it) }
        val h = v.tag as Holder
        val ch = items[position]
        h.num.text = if (ch.isVod) "" else ch.number.toString()
        Logos.into(h.logo, ch.logo, name = if (ch.isVod) null else ch.name)
        val prefix = (if (ch.url == playingUrl) "▶ " else "") + (if (Repo.isFavorite(ch)) "★ " else "")
        h.name.text = if (ch.isVod) prefix + Display.pretty(ch.name) else Display.channelTitle(ch.name, prefix)
        val now = System.currentTimeMillis()
        val p = if (ch.isVod) null else Epg.at(Repo.programmes(ch), now)
        if (p != null) {
            h.now.text = "Now:  " + p.title + "  ·  until " + Display.clock(v.context, p.stop)
            h.now.visibility = View.VISIBLE
            h.progress.visibility = View.VISIBLE
            h.progress.progress = (((now - p.start) * 1000) / (p.stop - p.start)).toInt()
        } else {
            h.now.text = if (ch.catchup != null) "Catch-up · ${ch.catchup.days} days" else ""
            h.now.visibility = if (h.now.text.isEmpty()) View.GONE else View.VISIBLE
            h.progress.visibility = View.GONE
        }
        return v
    }
}

/** Simple text rows (categories, seasons, episodes) in Lexend. */
class RowAdapter(private val rows: List<String>) : BaseAdapter() {
    override fun getCount() = rows.size
    override fun getItem(position: Int) = rows[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = (convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false)
            .also { Fonts.apply(it) }) as TextView
        v.text = rows[position]
        return v
    }
}

/** "Apps" rows in the channel list: icon + name, OK opens the app. */
class AppsAdapter(private val ctx: android.content.Context) : BaseAdapter() {
    var items: List<dev.yash.zapper.data.Apps.Entry> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }

    override fun getCount() = items.size
    override fun getItem(position: Int) = items[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
            .also { Fonts.apply(it) }
        val e = items[position]
        v.findViewById<TextView>(R.id.chNum).text = ""
        v.findViewById<ImageView>(R.id.chLogo).apply {
            tag = null
            setImageDrawable(dev.yash.zapper.data.Apps.icon(ctx, e.pkg))
        }
        v.findViewById<TextView>(R.id.chName).text = e.label
        v.findViewById<TextView>(R.id.chNow).apply { text = "Press OK to open"; visibility = View.VISIBLE }
        v.findViewById<ProgressBar>(R.id.chProgress).visibility = View.GONE
        return v
    }
}
