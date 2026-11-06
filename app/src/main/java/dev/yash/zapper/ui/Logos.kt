package dev.yash.zapper.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import dev.yash.zapper.data.Http
import dev.yash.zapper.data.Repo
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.Executors

/** Small logo loader with memory + disk cache (no third-party image library). */
object Logos {
    const val LOGO_PX = 96
    const val POSTER_PX = 330
    private val mem = object : LruCache<String, Bitmap>(28 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val failed = Collections.synchronizedSet(HashSet<String>())
    private val pending = HashMap<String, ArrayList<(Bitmap?) -> Unit>>()
    private val pool = Executors.newFixedThreadPool(3)
    private var dir: File? = null

    fun init(ctx: Context) {
        if (dir == null) dir = File(ctx.cacheDir, "logos").apply { mkdirs() }
    }

    fun cached(url: String?, px: Int = LOGO_PX): Bitmap? = url?.let { mem.get("$px|$it") }

    /** Main thread only. */
    fun get(url: String?, px: Int = LOGO_PX, cb: (Bitmap?) -> Unit) {
        if (url.isNullOrBlank() || failed.contains(url)) { cb(null); return }
        val key = "$px|$url"
        mem.get(key)?.let { cb(it); return }
        val waiting = pending[key]
        if (waiting != null) { waiting.add(cb); return }
        pending[key] = arrayListOf(cb)
        pool.execute {
            val bmp = try { load(url, px) } catch (_: Throwable) { null }
            Repo.main.post {
                if (bmp != null) mem.put(key, bmp) else failed.add(url)
                pending.remove(key)?.forEach { it(bmp) }
            }
        }
    }

    /**
     * Shows the logo at [url]. While it loads, or if there is none, shows a lettered tile made from
     * [name] instead (when given), so no row ever has an empty box.
     */
    fun into(iv: ImageView, url: String?, px: Int = LOGO_PX, name: String? = null) {
        val key = url ?: name
        iv.tag = key
        val hit = cached(url, px)
        val fallback = name?.let { monogram(it) }
        iv.setImageBitmap(hit ?: fallback)
        if (hit == null && !url.isNullOrBlank()) get(url, px) { b -> if (iv.tag == key) iv.setImageBitmap(b ?: fallback) }
    }

    private val monograms = LruCache<String, Bitmap>(300)
    /** Muted tones that sit well on the dark indigo UI; picked by name so a channel keeps its colour. */
    private val tones = intArrayOf(
        0xFF3A4E7A.toInt(), 0xFF5B4A7A.toInt(), 0xFF2F6466.toInt(), 0xFF6A4A3C.toInt(),
        0xFF4A5E3A.toInt(), 0xFF6B3F55.toInt(), 0xFF3C5A70.toInt(), 0xFF5E5634.toInt(),
    )

    fun monogram(name: String): Bitmap {
        val letters = Display.initials(name)
        monograms.get(name)?.let { return it }
        val w = 168
        val h = 108
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val bg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = tones[(Display.channelName(name).hashCode() and 0x7fffffff) % tones.size]
        }
        c.drawRoundRect(android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat()), 14f, 14f, bg)
        val tp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Palette.TEXT
            typeface = Fonts.semiboldFace
            textSize = 52f
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val fm = tp.fontMetrics
        c.drawText(letters, w / 2f, h / 2f - (fm.ascent + fm.descent) / 2, tp)
        monograms.put(name, bmp)
        return bmp
    }

    private fun load(url: String, px: Int): Bitmap? {
        val d = dir ?: return null
        val md = MessageDigest.getInstance("MD5").digest(url.toByteArray())
        val f = File(d, md.joinToString("") { "%02x".format(it) })
        if (!f.exists()) Http.download(url, Repo.userAgent, f)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        if (bounds.outWidth <= 0) { f.delete(); return null }
        var sample = 1
        while (bounds.outHeight / (sample * 2) >= px && bounds.outWidth / (sample * 2) >= px / 2) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            if (px > LOGO_PX) inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(f.path, opts)
    }
}
