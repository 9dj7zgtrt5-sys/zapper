package dev.yash.zapper.ui

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/** Design tokens shared by views and canvas drawing. */
object Palette {
    const val INK = 0xFF10141F.toInt()
    const val INK_DEEP = 0xFF0B0F18.toInt()
    const val SURFACE = 0xFF1A2030.toInt()
    const val RAISED = 0xFF252D40.toInt()
    const val LINE = 0xFF2E3850.toInt()
    const val TEXT = 0xFFF4F1EC.toInt()
    const val TEXT2 = 0xFFA7AFC2.toInt()
    const val FOCUS = 0xFFF2A93B.toInt()
    const val ON_FOCUS = 0xFF1A1206.toInt()
    const val LIVE = 0xFFE5484D.toInt()
}

/** Lexend, chosen for reading ease. Bold text uses SemiBold; views tagged "medium" use Medium. */
object Fonts {
    private var regular: Typeface? = null
    private var medium: Typeface? = null
    private var semibold: Typeface? = null

    fun init(ctx: Context) {
        if (regular != null) return
        val am = ctx.applicationContext.assets
        regular = try { Typeface.createFromAsset(am, "fonts/Lexend-Regular.ttf") } catch (_: Exception) { Typeface.DEFAULT }
        medium = try { Typeface.createFromAsset(am, "fonts/Lexend-Medium.ttf") } catch (_: Exception) { Typeface.DEFAULT }
        semibold = try { Typeface.createFromAsset(am, "fonts/Lexend-SemiBold.ttf") } catch (_: Exception) { Typeface.DEFAULT_BOLD }
    }

    val regularFace: Typeface get() = regular ?: Typeface.DEFAULT
    val mediumFace: Typeface get() = medium ?: Typeface.DEFAULT
    val semiboldFace: Typeface get() = semibold ?: Typeface.DEFAULT_BOLD

    /** Applies Lexend to every TextView under [v]. */
    fun apply(v: View) {
        init(v.context)
        when (v) {
            is TextView -> {
                val cur = v.typeface
                if (cur != null && (cur === regular || cur === medium || cur === semibold)) return
                val bold = v.typeface?.isBold == true
                v.typeface = when {
                    bold -> semiboldFace
                    v.tag == "medium" -> mediumFace
                    else -> regularFace
                }
            }
            is ViewGroup -> for (i in 0 until v.childCount) apply(v.getChildAt(i))
        }
    }
}

object Display {
    private val qualityRank = listOf("8K", "4K", "UHD", "FHD", "HD", "SD")
    private val superscripts = mapOf("ᴴᴰ" to "HD", "ᶠᴴᴰ" to "FHD", "ᵁᴴᴰ" to "UHD", "⁴ᴷ" to "4K", "ᴿᴬᵂ" to "", "ˢᴰ" to "SD", "⁶⁰ᶠᵖˢ" to "")
    /** Provider prefixes like "CA|", "|IN| ", "UK: ", "[VIP]", "(Backup)". */
    private val prefix = Regex("^\\s*(\\|?[A-Z]{2,4}\\s*[|:]\\s*|\\[[^\\]]{1,12}]\\s*)+")
    private val bracketTags = Regex("[\\[(](VIP|PPV|BACKUP|MULTI|HEVC|H\\.?26[45]|RAW|60 ?FPS|50 ?FPS|ALT|LQ|HQ|S)[\\])]", RegexOption.IGNORE_CASE)
    private val qualityWord = Regex("(?i)(?<![A-Za-z0-9])(8K|4K|UHD|FHD|HD|SD|HEVC|H\\.?26[45]|60 ?FPS|50 ?FPS|RAW)(?![A-Za-z0-9])")

    private fun unsuper(raw: String): String {
        var s = raw
        for ((k, v) in superscripts) s = s.replace(k, " $v ")
        return s
    }

    /** Best picture quality mentioned in a channel name ("4K", "FHD", "HD"…), or null. */
    fun quality(raw: String): String? {
        val found = qualityWord.findAll(unsuper(raw)).map { it.value.uppercase() }.toSet()
        val q = qualityRank.firstOrNull { it in found } ?: return null
        return if (q == "UHD") "4K" else q
    }

    /** Channel name without provider tags or quality words: "CA| GLOBAL TORONTO 4K ᴴᴰ" → "Global Toronto". */
    fun channelName(raw: String): String {
        var s = unsuper(raw)
        s = prefix.replace(s, "")
        s = bracketTags.replace(s, " ")
        s = qualityWord.replace(s, " ")
        s = s.replace(Regex("[|•·]+"), " ").replace(Regex("\\s+"), " ").trim().trim('-', ':', '|').trim()
        return pretty(if (s.isEmpty()) raw.trim() else s)
    }

    /** Name followed by a small outlined quality badge, for lists and the info bar. */
    fun channelTitle(raw: String, prefixText: String = ""): CharSequence {
        val sb = SpannableStringBuilder(prefixText).append(channelName(raw))
        val q = quality(raw) ?: return sb
        sb.append("  ")
        val start = sb.length
        sb.append(q)
        sb.setSpan(BadgeSpan(), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    /** "9:30" / "9:30 PM" following the TV's 12/24-hour setting. */
    fun clock(ctx: Context, ms: Long): String = android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(ms))

    /** Up to two letters for a logo stand-in: "Global Toronto" → "GT", "Colors" → "CO". */
    fun initials(raw: String): String {
        val words = channelName(raw).split(' ', '-', '&').filter { w -> w.any { it.isLetterOrDigit() } }
        if (words.isEmpty()) return "TV"
        if (words.size == 1) return words[0].filter { it.isLetterOrDigit() }.take(2).uppercase()
        return (words[0].first { it.isLetterOrDigit() }.toString() + words[1].first { it.isLetterOrDigit() }).uppercase()
    }

    private val keepUpper = Regex("^(HD|FHD|UHD|SD|4K|8K|TV|UK|US|USA|UAE|BBC|CNN|HBO|ESPN|MTV|NDTV|ABP|DD|PTC|ETV|SAB|ZEE|SONY|VIP|PPV|NBA|NFL|EPL|F1|II|III|IV|24X7|24/7)$")

    /** "ENGLISH NEWS" → "English News", "STAR PLUS HD" → "Star Plus HD"; mixed-case names are left alone. */
    fun pretty(s: String): String {
        val letters = s.filter { it.isLetter() }
        if (letters.length < 4 || letters.any { it.isLowerCase() }) return s
        return s.split(' ').joinToString(" ") { w ->
            if (w.isEmpty() || keepUpper.matches(w.uppercase()) || w.length <= 2) w
            else w.lowercase().replaceFirstChar { it.uppercase() }
        }
    }

    /** Builds "OK  Watch     ◀  Categories" with the keys in the focus colour. */
    fun hints(vararg pairs: Pair<String, String>): CharSequence {
        val sb = SpannableStringBuilder()
        pairs.forEachIndexed { i, (key, action) ->
            if (i > 0) sb.append("        ")
            val start = sb.length
            sb.append(key)
            sb.setSpan(ForegroundColorSpan(Palette.FOCUS), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append("  ").append(action)
        }
        return sb
    }
}

/** Small outlined pill for "4K / FHD / HD", drawn in the text's current colour so it also reads on focus. */
class BadgeSpan : android.text.style.ReplacementSpan() {
    override fun getSize(paint: android.graphics.Paint, text: CharSequence, start: Int, end: Int, fm: android.graphics.Paint.FontMetricsInt?): Int {
        val p = android.graphics.Paint(paint).apply { textSize = paint.textSize * 0.62f; typeface = Fonts.semiboldFace }
        return (p.measureText(text, start, end) + paint.textSize * 0.7f).toInt()
    }

    override fun draw(c: android.graphics.Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: android.graphics.Paint) {
        val p = android.graphics.Paint(paint).apply { textSize = paint.textSize * 0.62f; typeface = Fonts.semiboldFace; isAntiAlias = true }
        val w = getSize(paint, text, start, end, null).toFloat()
        val h = paint.textSize * 0.95f
        val cy = y - paint.textSize * 0.35f
        val r = android.graphics.RectF(x + 2, cy - h / 2, x + w - 2, cy + h / 2)
        val stroke = android.graphics.Paint(paint).apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = paint.textSize * 0.07f; isAntiAlias = true; alpha = 200 }
        c.drawRoundRect(r, h * 0.28f, h * 0.28f, stroke)
        val tw = p.measureText(text, start, end)
        c.drawText(text, start, end, r.centerX() - tw / 2, cy + p.textSize * 0.36f, p)
    }
}
