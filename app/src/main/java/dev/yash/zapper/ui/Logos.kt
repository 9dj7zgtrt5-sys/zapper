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
    private const val TARGET_PX = 96
    private val mem = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val failed = Collections.synchronizedSet(HashSet<String>())
    private val pending = HashMap<String, ArrayList<(Bitmap?) -> Unit>>()
    private val pool = Executors.newFixedThreadPool(3)
    private var dir: File? = null

    fun init(ctx: Context) {
        if (dir == null) dir = File(ctx.cacheDir, "logos").apply { mkdirs() }
    }

    fun cached(url: String?): Bitmap? = url?.let { mem.get(it) }

    /** Main thread only. */
    fun get(url: String?, cb: (Bitmap?) -> Unit) {
        if (url.isNullOrBlank() || failed.contains(url)) { cb(null); return }
        mem.get(url)?.let { cb(it); return }
        val waiting = pending[url]
        if (waiting != null) { waiting.add(cb); return }
        pending[url] = arrayListOf(cb)
        pool.execute {
            val bmp = try { load(url) } catch (_: Throwable) { null }
            Repo.main.post {
                if (bmp != null) mem.put(url, bmp) else failed.add(url)
                pending.remove(url)?.forEach { it(bmp) }
            }
        }
    }

    fun into(iv: ImageView, url: String?) {
        iv.tag = url
        val hit = cached(url)
        iv.setImageBitmap(hit)
        if (hit == null && !url.isNullOrBlank()) get(url) { b -> if (iv.tag == url) iv.setImageBitmap(b) }
    }

    private fun load(url: String): Bitmap? {
        val d = dir ?: return null
        val md = MessageDigest.getInstance("MD5").digest(url.toByteArray())
        val f = File(d, md.joinToString("") { "%02x".format(it) })
        if (!f.exists()) Http.download(url, Repo.userAgent, f)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        if (bounds.outWidth <= 0) { f.delete(); return null }
        var sample = 1
        while (bounds.outHeight / (sample * 2) >= TARGET_PX && bounds.outWidth / (sample * 2) >= TARGET_PX) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(f.path, opts)
    }
}
