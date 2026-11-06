package dev.yash.zapper.data

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

object Http {
    /** Opens a GET connection, following redirects across http/https. */
    fun open(url: String, userAgent: String): HttpURLConnection {
        var current = URL(url)
        repeat(6) {
            val c = current.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", userAgent)
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("Redirect without location")
                c.disconnect()
                current = URL(current, loc)
            } else if (code !in 200..299) {
                c.disconnect()
                throw IOException("HTTP $code")
            } else {
                return c
            }
        }
        throw IOException("Too many redirects")
    }

    fun download(url: String, userAgent: String, dest: File) {
        val tmp = File(dest.path + ".part")
        val c = open(url, userAgent)
        try {
            c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
        } finally {
            c.disconnect()
        }
        if (tmp.length() == 0L) { tmp.delete(); throw IOException("Empty response") }
        if (!tmp.renameTo(dest)) { dest.delete(); tmp.renameTo(dest) }
    }

    /** Opens a cached file, transparently un-gzipping if needed. */
    fun openMaybeGzip(file: File): InputStream {
        val buffered = BufferedInputStream(file.inputStream(), 64 * 1024)
        buffered.mark(2)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()
        return if (b1 == 0x1f && b2 == 0x8b) BufferedInputStream(GZIPInputStream(buffered, 64 * 1024), 64 * 1024) else buffered
    }
}
