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

    override fun getCount() = items.size
    override fun getItem(position: Int) = items[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_group, parent, false)
        val g = items[position]
        v.findViewById<TextView>(R.id.groupName).text = g.title
        v.findViewById<TextView>(R.id.groupCount).text = g.channels.size.toString()
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
            .also { it.tag = Holder(it) }
        val h = v.tag as Holder
        val ch = items[position]
        h.num.text = if (ch.isVod) "" else ch.number.toString()
        Logos.into(h.logo, ch.logo)
        val prefix = (if (ch.url == playingUrl) "▶ " else "") + (if (Repo.isFavorite(ch)) "★ " else "")
        h.name.text = prefix + ch.name
        val now = System.currentTimeMillis()
        val p = if (ch.isVod) null else Epg.at(Repo.programmes(ch), now)
        if (p != null) {
            h.now.text = p.title
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
