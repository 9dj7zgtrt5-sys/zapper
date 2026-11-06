package dev.yash.zapper.data

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable

/** Other TV apps that can be opened from inside Zapper (e.g. YouTube, the VPN app). */
object Apps {
    class Entry(val pkg: String, val label: String)

    /** Every app that can be opened from the TV home screen, except Zapper itself. */
    fun launchable(ctx: Context): List<Entry> {
        val pm = ctx.packageManager
        val seen = LinkedHashMap<String, Entry>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val list = try {
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0)
            } catch (_: Exception) { emptyList() }
            for (ri in list) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (pkg == ctx.packageName || seen.containsKey(pkg)) continue
                seen[pkg] = Entry(pkg, ri.loadLabel(pm)?.toString() ?: pkg)
            }
        }
        return seen.values.sortedBy { it.label.lowercase() }
    }

    /** Apps chosen on the settings page; by default YouTube and the VPN app. */
    fun shown(ctx: Context): List<Entry> {
        val all = launchable(ctx)
        val chosen = Repo.shownApps
        if (chosen != null) return chosen.mapNotNull { p -> all.firstOrNull { it.pkg == p } }
        val vpn = Vpn.chosen(ctx)?.pkg
        return all.filter { it.label.contains("YouTube", ignoreCase = true) || it.pkg == vpn }
    }

    fun icon(ctx: Context, pkg: String): Drawable? = try {
        val pm = ctx.packageManager
        val ai = pm.getApplicationInfo(pkg, 0)
        pm.getApplicationBanner(ai) ?: pm.getApplicationIcon(ai)
    } catch (_: Exception) { null }

    fun open(ctx: Context, pkg: String): Boolean {
        val i = Vpn.launchIntent(ctx, pkg) ?: return false
        return try { ctx.startActivity(i); true } catch (_: Exception) { false }
    }
}
