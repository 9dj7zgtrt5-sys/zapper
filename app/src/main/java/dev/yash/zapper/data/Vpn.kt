package dev.yash.zapper.data

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object Vpn {
    class App(val pkg: String, val label: String)

    fun isActive(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    } catch (_: Exception) {
        false
    }

    /** Installed apps that provide a VPN service. */
    fun apps(ctx: Context): List<App> {
        val pm = ctx.packageManager
        return pm.queryIntentServices(Intent("android.net.VpnService"), 0)
            .map { it.serviceInfo.packageName }
            .distinct()
            .filter { it != ctx.packageName }
            .map { pkg ->
                val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
                App(pkg, label)
            }
    }

    /** The VPN app chosen in settings, or the first one installed. */
    fun chosen(ctx: Context): App? {
        val list = apps(ctx)
        return list.firstOrNull { it.pkg == Repo.vpnPackage } ?: list.firstOrNull()
    }

    fun launchIntent(ctx: Context, pkg: String): Intent? {
        val pm = ctx.packageManager
        return (pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg))
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
