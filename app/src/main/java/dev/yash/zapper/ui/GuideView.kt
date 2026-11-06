package dev.yash.zapper.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import dev.yash.zapper.data.Catchup
import dev.yash.zapper.data.Channel
import dev.yash.zapper.data.Epg
import dev.yash.zapper.data.Programme
import dev.yash.zapper.data.Repo
import dev.yash.zapper.data.TimeUtil
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** TiviMate-style timeline guide, drawn on a canvas so it stays fast on low-end sticks. */
class GuideView(ctx: Context, attrs: AttributeSet?) : View(ctx, attrs) {
    interface Listener {
        fun onGuidePick(ch: Channel, p: Programme?)
        fun onGuideFavorite(ch: Channel)
    }

    var listener: Listener? = null

    private var channels: List<Channel> = emptyList()
    private var row = 0
    private var topRow = 0
    private var selTime = 0L
    private var viewStart = 0L
    private val span = 2 * TimeUtil.HOUR
    private val half = 30 * TimeUtil.MIN

    private val dp = resources.displayMetrics.density
    private val rowH = 60 * dp
    private val colW = 310 * dp
    private val headerH = 38 * dp
    private val detailH = 150 * dp
    private val pad = 10 * dp

    private val timeFmt: DateFormat = android.text.format.DateFormat.getTimeFormat(ctx)
    private val dayFmt = SimpleDateFormat("EEE d MMM", Locale.getDefault())

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.TEXT; textSize = 20 * dp }
    private val textOnFocus = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.ON_FOCUS; textSize = 20 * dp }
    private val dim = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.TEXT2; textSize = 17 * dp }
    private val big = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.TEXT; textSize = 30 * dp }
    private val descPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC9CFDB.toInt(); textSize = 18 * dp }

    private val cBg = 0xF710141F.toInt()
    private val cRowAlt = 0x0AF4F1EC
    private val cProg = Palette.RAISED
    private val cPast = Palette.SURFACE
    private val cNow = 0xFF2F3A52.toInt()
    private val cSel = Palette.FOCUS
    private val cChanSel = Palette.RAISED
    private val cLine = Palette.LIVE
    private val cAccent = Palette.FOCUS

    init {
        Fonts.init(ctx)
        text.typeface = Fonts.mediumFace
        textOnFocus.typeface = Fonts.semiboldFace
        dim.typeface = Fonts.regularFace
        big.typeface = Fonts.semiboldFace
        descPaint.typeface = Fonts.regularFace
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun show(list: List<Channel>, selected: Channel?) {
        channels = list
        row = list.indexOfFirst { it.url == selected?.url }.coerceAtLeast(0)
        topRow = 0
        val now = System.currentTimeMillis()
        selTime = now
        viewStart = TimeUtil.floorHalfHour(now)
        requestFocus()
        invalidate()
    }

    fun selectedChannel(): Channel? = channels.getOrNull(row)

    private fun visibleRows() = ((height - detailH - headerH) / rowH).toInt().coerceAtLeast(1)

    private fun selectedProgramme(): Programme? =
        channels.getOrNull(row)?.let { Epg.at(Repo.programmes(it), selTime) }

    private fun minTime(now: Long) = now - maxOf(Repo.maxCatchupDays * TimeUtil.DAY, 2 * TimeUtil.HOUR)

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val now = System.currentTimeMillis()
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> moveRows(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> moveRows(1)
            KeyEvent.KEYCODE_MEDIA_REWIND -> moveRows(-visibleRows())
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> moveRows(visibleRows())
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val p = selectedProgramme()
                val t = p?.stop ?: (TimeUtil.floorHalfHour(selTime) + half)
                selTime = t.coerceAtMost(now + 4 * TimeUtil.DAY)
                while (selTime >= viewStart + span) viewStart += half
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                val ch = channels.getOrNull(row)
                val p = selectedProgramme()
                val t = if (p != null && ch != null) {
                    Epg.at(Repo.programmes(ch), p.start - 1)?.start ?: (p.start - half)
                } else selTime - half
                selTime = t.coerceAtLeast(minTime(now))
                if (selTime < viewStart) viewStart = TimeUtil.floorHalfHour(selTime)
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                channels.getOrNull(row)?.let { listener?.onGuidePick(it, selectedProgramme()) }
            }
            KeyEvent.KEYCODE_MENU -> channels.getOrNull(row)?.let { listener?.onGuideFavorite(it) }
            else -> return super.onKeyDown(keyCode, event)
        }
        invalidate()
        return true
    }

    private fun moveRows(delta: Int) {
        if (channels.isEmpty()) return
        row = (row + delta).coerceIn(0, channels.size - 1)
        if (selTime < viewStart) selTime = viewStart
    }

    private fun fmt(t: Long) = timeFmt.format(Date(t))

    private fun ellipsize(s: String, p: TextPaint, w: Float): String =
        if (w <= 0) "" else TextUtils.ellipsize(s, p, w, TextUtils.TruncateAt.END).toString()

    override fun onDraw(c: Canvas) {
        val now = System.currentTimeMillis()
        val w = width.toFloat()
        val vis = visibleRows()
        if (row < topRow) topRow = row
        if (row >= topRow + vis) topRow = row - vis + 1
        c.drawColor(cBg)

        // ---- details header ----
        val ch = channels.getOrNull(row)
        val sel = selectedProgramme()
        val left = pad * 2
        val hint = "OK Watch      ☰ Favorite      Back Close"
        val hintW = dim.measureText(hint) + pad * 3
        c.drawText(hint, w - left - dim.measureText(hint), pad + 30 * dp, dim)
        if (ch == null) {
            c.drawText("No channels in this group", left, detailH / 2, big)
        } else {
            val title = sel?.title?.ifBlank { null } ?: Display.pretty(ch.name)
            c.drawText(ellipsize(title, big, w - left * 2 - hintW), left, pad + 34 * dp, big)
            val meta = StringBuilder()
            if (sel != null) {
                meta.append(dayFmt.format(Date(sel.start))).append("  ·  ")
                    .append(fmt(sel.start)).append(" – ").append(fmt(sel.stop)).append("  ·  ")
            }
            meta.append(ch.number).append("  ").append(Display.pretty(ch.name))
            c.drawText(ellipsize(meta.toString(), dim, w - left * 2), left, pad + 62 * dp, dim)
            if (sel != null) {
                val tag = when {
                    sel.start <= now && now < sel.stop -> "ON NOW"
                    sel.stop <= now && Catchup.available(ch, sel, now) -> "Press OK to watch from the start"
                    sel.stop <= now -> "Already finished"
                    else -> "Coming up later"
                }
                if (tag != null) {
                    dim.color = cAccent
                    c.drawText(tag, w - left - dim.measureText(tag), pad + 62 * dp, dim)
                    dim.color = Palette.TEXT2
                }
            }
            val desc = sel?.desc
            if (!desc.isNullOrBlank()) {
                @Suppress("DEPRECATION")
                val layout = StaticLayout(desc, descPaint, (w - left * 2).toInt(), Layout.Alignment.ALIGN_NORMAL, 1.1f, 0f, false)
                c.save()
                c.translate(left, pad + 74 * dp)
                c.clipRect(0f, 0f, w - left * 2, detailH - pad - 76 * dp)
                layout.draw(c)
                c.restore()
            }
        }

        // ---- time header ----
        val gx = colW
        val gw = w - gx
        val pxPerMs = gw / span
        val viewEnd = viewStart + span
        val hy = detailH
        fill.color = Palette.INK_DEEP
        c.drawRect(0f, hy, w, hy + headerH, fill)
        c.drawText(dayFmt.format(Date(maxOf(selTime, viewStart))), left, hy + headerH - 10 * dp, dim)
        var t = viewStart
        fill.color = 0x33FFFFFF
        while (t < viewEnd) {
            val x = gx + (t - viewStart) * pxPerMs
            c.drawRect(x, hy + 6 * dp, x + 1, hy + headerH, fill)
            c.drawText(fmt(t), x + 6 * dp, hy + headerH - 10 * dp, dim)
            t += half
        }

        // ---- rows ----
        val gy = hy + headerH
        for (i in 0 until vis) {
            val idx = topRow + i
            if (idx >= channels.size) break
            val channel = channels[idx]
            val y = gy + i * rowH
            if (i % 2 == 1) { fill.color = cRowAlt; c.drawRect(0f, y, w, y + rowH, fill) }

            // channel cell
            if (idx == row) { fill.color = cChanSel; c.drawRect(0f, y, gx, y + rowH, fill) }
            val numStr = channel.number.toString()
            c.drawText(numStr, pad, y + rowH / 2 + 7 * dp, dim)
            var nx = pad + 50 * dp
            val logo = Logos.cached(channel.logo)
            if (logo != null) {
                val lh = rowH - 14 * dp
                val lw = (lh * logo.width / logo.height.coerceAtLeast(1)).coerceAtMost(64 * dp)
                rect.set(nx, y + 7 * dp, nx + lw, y + 7 * dp + lh)
                c.drawBitmap(logo, null, rect, null)
                nx += 72 * dp
            } else if (channel.logo != null) {
                Logos.get(channel.logo) { if (it != null) invalidate() }
            }
            val fav = if (Repo.isFavorite(channel)) "★ " else ""
            c.drawText(ellipsize(fav + Display.pretty(channel.name), text, gx - nx - pad), nx, y + rowH / 2 + 7 * dp, text)

            // programmes
            val list = Repo.programmes(channel)
            var k = Epg.firstEndingAfter(list, viewStart)
            var drew = false
            while (k < list.size && list[k].start < viewEnd) {
                val p = list[k]
                val x1 = gx + (maxOf(p.start, viewStart) - viewStart) * pxPerMs
                val x2 = gx + (minOf(p.stop, viewEnd) - viewStart) * pxPerMs
                val isSel = idx == row && p === sel
                fill.color = when {
                    isSel -> cSel
                    p.stop <= now -> cPast
                    p.start <= now -> cNow
                    else -> cProg
                }
                rect.set(x1 + 2 * dp, y + 3 * dp, x2 - 2 * dp, y + rowH - 3 * dp)
                if (rect.width() > 0) c.drawRoundRect(rect, 8 * dp, 8 * dp, fill)
                val label = (if (p.stop <= now && Catchup.available(channel, p, now)) "⟲ " else "") + p.title
                val tp = if (isSel) textOnFocus else text
                c.drawText(ellipsize(label, tp, x2 - x1 - 2 * pad), x1 + pad, y + rowH / 2 + 7 * dp, tp)
                drew = true
                k++
            }
            if (!drew) {
                fill.color = if (idx == row) cSel else cProg
                rect.set(gx + 2 * dp, y + 3 * dp, w - 2 * dp, y + rowH - 3 * dp)
                c.drawRoundRect(rect, 8 * dp, 8 * dp, fill)
                val np = if (idx == row) textOnFocus else dim
                c.drawText(if (list.isEmpty()) "No programme information" else "", gx + pad, y + rowH / 2 + 7 * dp, np)
            }
            fill.color = 0x22FFFFFF
            c.drawRect(gx, y, gx + 1, y + rowH, fill)
        }

        // ---- now line ----
        if (now in viewStart until viewEnd) {
            val x = gx + (now - viewStart) * pxPerMs
            fill.color = cLine
            c.drawRect(x - 1 * dp, hy, x + 1 * dp, height.toFloat(), fill)
        }
    }
}
