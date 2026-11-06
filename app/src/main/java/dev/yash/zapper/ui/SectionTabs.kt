package dev.yash.zapper.ui

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.yash.zapper.R
import dev.yash.zapper.data.Repo

/**
 * The row of big tabs at the top of Zapper's screens: Live TV · Movies · Series (· Help).
 * The same row appears in the channel list and in Movies/Series, so each section is always one
 * press of ▲ away and looks the same everywhere.
 */
object SectionTabs {
    enum class Section { LIVE, MOVIES, SERIES, HELP }

    /**
     * Fills [row] with tabs. [onPick] runs when OK is pressed on a tab other than the current one
     * (or on Help).
     */
    fun build(activity: Activity, row: LinearLayout, current: Section, help: Boolean, onPick: (Section) -> Unit): Map<Section, View> {
        row.removeAllViews()
        val d = activity.resources.displayMetrics.density
        val out = LinkedHashMap<Section, View>()
        val sections = buildList {
            add(Section.LIVE)
            if (Repo.hasVod) { add(Section.MOVIES); add(Section.SERIES) }
            if (help) add(Section.HELP)
        }
        for (s in sections) {
            val tab = LayoutInflater.from(activity).inflate(R.layout.section_tab, row, false)
            Fonts.apply(tab)
            tab.findViewById<ImageView>(R.id.tabIcon).setImageResource(
                when (s) {
                    Section.LIVE -> R.drawable.ic_tv
                    Section.MOVIES -> R.drawable.ic_movie
                    Section.SERIES -> R.drawable.ic_series
                    Section.HELP -> R.drawable.ic_help
                },
            )
            tab.findViewById<TextView>(R.id.tabLabel).text = when (s) {
                Section.LIVE -> "Live TV"
                Section.MOVIES -> "Movies"
                Section.SERIES -> "Series"
                Section.HELP -> "Help"
            }
            tab.isSelected = s == current
            tab.id = View.generateViewId()
            tab.setOnClickListener { if (s != current || s == Section.HELP) onPick(s) }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            if (row.childCount > 0) lp.marginStart = ((if (s == Section.HELP) 40 else 14) * d).toInt()
            row.addView(tab, lp)
            out[s] = tab
        }
        // ◀ ▶ stays inside the row.
        val views = out.values.toList()
        views.forEachIndexed { i, v ->
            v.nextFocusLeftId = views.getOrNull(i - 1)?.id ?: v.id
            v.nextFocusRightId = views.getOrNull(i + 1)?.id ?: v.id
        }
        return out
    }
}
