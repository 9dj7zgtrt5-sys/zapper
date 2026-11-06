package dev.yash.zapper.data

import android.content.Context
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * Settings web page served by the TV on port 8090, so everything can be managed from a phone on the
 * same Wi-Fi while the TV keeps playing. Runs for as long as the app process is alive.
 */
object ConfigServer {
    const val PORT = 8090

    /** Called on the main thread after settings are saved from the phone. */
    @Volatile var listener: (() -> Unit)? = null

    @Volatile private var started = false
    private lateinit var app: Context

    fun start(ctx: Context) {
        if (started) return
        started = true
        app = ctx.applicationContext
        thread(name = "config-server", isDaemon = true) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(PORT))
                while (!ss.isClosed) {
                    val client = ss.accept()
                    thread(isDaemon = true) { handle(client) }
                }
            } catch (_: Exception) {
                started = false
            }
        }
    }

    fun address(): String? = localIp()?.let { "http://$it:$PORT" }

    private fun handle(s: Socket) {
        try {
            s.soTimeout = 15_000
            val input = BufferedInputStream(s.getInputStream())
            val requestLine = readLine(input) ?: return
            var length = 0
            while (true) {
                val h = readLine(input) ?: break
                if (h.isEmpty()) break
                if (h.startsWith("content-length:", ignoreCase = true)) length = h.substringAfter(':').trim().toIntOrNull() ?: 0
            }
            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0) ?: "GET"
            val path = parts.getOrNull(1) ?: "/"
            val body = if (length in 1..(1024 * 1024)) {
                val buf = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(buf, read, length - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read, Charsets.UTF_8)
            } else ""

            val html = if (method == "POST" && path.startsWith("/save")) {
                val form = parseForm(body)
                save(form)
                if (form.any { it.first == "newmac" })
                    page("<h2>New MAC created ✓</h2><p style='font-size:22px'><b>${esc(Repo.mac)}</b></p><p>Send this to your provider to activate it.</p><p><a href='/'>Back to settings</a></p>")
                else page("<h2>Saved ✓</h2><p>The TV has the new settings.</p><p><a href='/'>Back to settings</a></p>")
            } else form()

            val bytes = html.toByteArray(Charsets.UTF_8)
            val out = s.getOutputStream()
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
            out.write(bytes)
            out.flush()
        } catch (_: Exception) {
        } finally {
            try { s.close() } catch (_: Exception) {}
        }
    }

    private fun save(f: List<Pair<String, String>>) {
        fun v(k: String) = f.firstOrNull { it.first == k }?.second

        fun on(k: String) = v(k) == "on"
        val hidden = if (v("groups_present") != null) {
            val shown = f.filter { it.first == "g" }.map { it.second }.toSet()
            Repo.allGroupNames().map { it.first }.filter { it !in shown }.toSet()
        } else Repo.hiddenGroups
        Repo.saveConfig(
            Repo.Settings(
                source = if (v("source") == "portal") "portal" else "m3u",
                portal = v("portal") ?: "",
                mac = v("mac") ?: "",
                m3u = v("m3u") ?: "",
                epg = v("epg") ?: "",
                ua = v("ua") ?: "",
                tz = v("tz")?.toFloatOrNull() ?: 0f,
                reverse = on("reverse"),
                autoStart = on("autostart"),
                requireVpn = on("requirevpn"),
                hideVod = on("hidevod"),
                vpnPackage = v("vpnpkg") ?: "",
                hiddenGroups = hidden,
                forceReload = v("reload") != null,
            )
        )
        if (v("newmac") != null) Repo.regenerateMac()
        Repo.main.post { listener?.invoke() }
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > 16 * 1024) break
        }
        return buf.toString("UTF-8")
    }

    private fun parseForm(body: String): List<Pair<String, String>> = body.split('&').mapNotNull {
        val i = it.indexOf('=')
        if (i <= 0) null else URLDecoder.decode(it.substring(0, i), "UTF-8") to URLDecoder.decode(it.substring(i + 1), "UTF-8")
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
    private fun checked(b: Boolean) = if (b) "checked" else ""

    private fun form(): String {
        val vpnApps = Vpn.apps(app)
        val vpnOn = Vpn.isActive(app)
        val hidden = Repo.hiddenGroups
        val groups = Repo.allGroupNames()
        val vpnOptions = buildString {
            append("<option value=''>Automatic${vpnApps.firstOrNull()?.let { " (" + esc(it.label) + ")" } ?: ""}</option>")
            for (a in vpnApps) append("<option value='${esc(a.pkg)}' ${if (a.pkg == Repo.vpnPackage) "selected" else ""}>${esc(a.label)}</option>")
        }
        val groupRows = if (groups.isEmpty()) "<p class='muted'>Groups appear here once the playlist has loaded.</p>" else buildString {
            append("<input type='hidden' name='groups_present' value='1'>")
            append("<div class='tools'><button type='button' onclick='setAll(true)'>Show all</button><button type='button' onclick='setAll(false)'>Hide all</button></div>")
            append("<div class='groups'>")
            for ((name, count) in groups) {
                append("<label class='row'><input type='checkbox' name='g' value='${esc(name)}' ${checked(name !in hidden)}> ${esc(name)} <span class='muted'>$count</span></label>")
            }
            append("</div>")
        }
        return page(
            """
            <h2>Zapper settings</h2>
            <div class="status">
              <div>Channels: <b>${Repo.channels.count { !it.isVod }}</b> live · <b>${Repo.channels.count { it.isVod }}</b> movies/episodes</div>
              <div>TV guide: <b>${Repo.epgChannelCount}</b> channels have programme info</div>
              <div>This TV's MAC address: <b style="font-size:18px;letter-spacing:.03em">${esc(Repo.mac)}</b></div>
              <div>Source: <b>${if (Repo.isPortal) "Portal (MAC ${esc(Repo.mac)})" else "M3U playlist"}</b>${Repo.subscriptionEnd?.let { " · subscription until <b>" + esc(it) + "</b>" } ?: ""}</div>
              <div>VPN: <b>${if (vpnOn) "connected ✓" else if (vpnApps.isEmpty()) "no VPN app found" else "off"}</b></div>
            </div>
            <form method="post" action="/save">
              <h3>Channels source</h3>
              <label class="row"><input type="radio" name="source" value="portal" onchange="src()" ${checked(Repo.isPortal)}> Portal + MAC address <small>(MAG / Stalker)</small></label>
              <label class="row"><input type="radio" name="source" value="m3u" onchange="src()" ${checked(!Repo.isPortal)}> M3U playlist link</label>
              <div id="portalBox">
                <label>Portal URL <small>(e.g. http://example.com:8080/c/)</small></label>
                <input name="portal" value="${esc(Repo.portalUrl)}" placeholder="http://server:port/c/" autocapitalize="off" autocorrect="off">
                <label>MAC address <small>(the one your provider activated, e.g. 00:1A:79:12:34:56)</small></label>
                <input name="mac" value="${esc(Repo.mac)}" placeholder="00:1A:79:XX:XX:XX" autocapitalize="characters" autocorrect="off">
                <p class="muted">Zapper made this MAC for this TV. Give it to your provider to activate, or type the one they gave you.</p>
                <button type="submit" name="newmac" value="1" class="secondary" style="margin-top:4px" onclick="return confirm('Create a new MAC address? Your provider will need to activate the new one.')">Generate new MAC</button>
              </div>
              <div id="m3uBox">
                <label>M3U playlist URL</label>
                <textarea name="m3u" rows="3">${esc(Repo.m3uUrl)}</textarea>
              </div>
              <label>XMLTV EPG URL <small>(optional — leave blank to use the portal's or playlist's own guide)</small></label>
              <textarea name="epg" rows="2">${esc(Repo.epgUrl)}</textarea>

              <h3>Startup &amp; VPN</h3>
              <label class="row"><input type="checkbox" name="autostart" ${checked(Repo.autoStart)}> Open Zapper when the TV turns on</label>
              <label class="row"><input type="checkbox" name="requirevpn" ${checked(Repo.requireVpn)}> Only play when the VPN is connected</label>
              <label>VPN app</label>
              <select name="vpnpkg">$vpnOptions</select>

              <h3>What your parents see</h3>
              <label class="row"><input type="checkbox" name="hidevod" ${checked(Repo.hideVod)}> Hide movies &amp; series</label>
              <p class="muted">Untick categories to hide them. Hidden channels disappear from lists and the guide; favorites still work.</p>
              $groupRows

              <h3>Advanced</h3>
              <label class="row"><input type="checkbox" name="reverse" ${checked(Repo.reverseZap)}> Reverse ▲/▼ channel switching</label>
              <label>User agent <small>(only if the provider needs one)</small></label>
              <input name="ua" value="${esc(Repo.customUserAgent)}" placeholder="${esc(Repo.DEFAULT_UA)}">
              <label>Catch-up time offset, hours <small>(only if catch-up starts at the wrong time)</small></label>
              <input name="tz" type="number" step="0.5" value="${Repo.tzOffsetHours}">

              <div class="buttons">
                <button type="submit">Save</button>
                <button type="submit" name="reload" value="1" class="secondary">Save &amp; reload channels</button>
              </div>
            </form>
            <script>function src(){var p=document.querySelector("input[name=source][value=portal]").checked;document.getElementById("portalBox").style.display=p?"":"none";document.getElementById("m3uBox").style.display=p?"none":""}src();
            function setAll(v){document.querySelectorAll("input[name=g]").forEach(function(c){c.checked=v})}</script>
            """.trimIndent()
        )
    }

    private fun page(body: String) = """
        <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Zapper settings</title><style>
        body{font-family:system-ui,sans-serif;background:#0f1216;color:#e8eaed;margin:0 auto;padding:20px;max-width:640px}
        h2{margin:0 0 12px}h3{margin:28px 0 4px;color:#8ab4f8;font-size:15px;text-transform:uppercase;letter-spacing:.05em}
        label{display:block;margin:14px 0 6px;font-weight:600}small,.muted{color:#9aa0a6;font-weight:400}
        textarea,input,select{width:100%;box-sizing:border-box;padding:10px;border-radius:8px;border:1px solid #3c4043;background:#1b1f24;color:#e8eaed;font-size:15px}
        input[type=radio]{width:auto}
        .row{display:flex;gap:10px;align-items:center;font-weight:400;margin:10px 0}.row input{width:auto;transform:scale(1.3)}
        .status{background:#1b1f24;border-radius:10px;padding:12px 14px;line-height:1.7}
        .groups{max-height:50vh;overflow:auto;border:1px solid #3c4043;border-radius:8px;padding:2px 12px}
        .tools{display:flex;gap:8px;margin:8px 0}.tools button{width:auto;margin:0;padding:8px 12px;font-size:14px;background:#262b31}
        .buttons{display:flex;flex-direction:column;gap:10px;margin-top:24px}
        button{width:100%;padding:14px;border:0;border-radius:8px;background:#3d7eff;color:#fff;font-size:16px;font-weight:600}
        button.secondary{background:#262b31}a{color:#8ab4f8}
        </style></head><body>$body</body></html>
    """.trimIndent()

    fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback && !it.name.startsWith("tun") && !it.name.startsWith("ppp") }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    } catch (_: Exception) {
        null
    }
}
