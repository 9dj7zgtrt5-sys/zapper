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
