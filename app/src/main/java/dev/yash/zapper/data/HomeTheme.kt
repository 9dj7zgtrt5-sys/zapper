package dev.yash.zapper.data

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import java.util.UUID

/**
 * Sends photos and the theme to the TV home screen (the CleanYourTV launcher, com.gathin.tvhome) through its
 * theme provider. The provider requires WRITE_SECURE_SETTINGS, granted once over ADB:
 *   adb shell pm grant dev.yash.zapper android.permission.WRITE_SECURE_SETTINGS
 */
object HomeTheme {
    const val LAUNCHER = "com.gathin.tvhome"
    private const val BASE = "content://$LAUNCHER.theme"
    private val DRAWINGS = listOf("morning.jpg", "evening.jpg", "night.jpg")

    fun launcherInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(LAUNCHER, 0); true
    } catch (_: Exception) { false }

    fun permitted(ctx: Context): Boolean =
        ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    fun ready(ctx: Context) = launcherInstalled(ctx) && permitted(ctx)

    private fun write(ctx: Context, path: String, bytes: ByteArray) {
        val out = ctx.contentResolver.openOutputStream(Uri.parse("$BASE/$path"), "w")
            ?: throw IllegalStateException("Home screen refused $path")
        out.use { it.write(bytes) }
    }

    private fun delete(ctx: Context, path: String) {
        try { ctx.contentResolver.delete(Uri.parse("$BASE/$path"), null, null) } catch (_: Exception) {}
    }

    /** Tells the home screen to rebuild with the new files the next time it opens. */
    private fun bump(ctx: Context) = write(ctx, "theme_revision", UUID.randomUUID().toString().replace("-", "").toByteArray())

    private val photoCount: Int get() = Repo.store().getInt("home_photos", 0)

    fun photos(): Int = photoCount

    /** Adds one JPEG (already resized by the phone). The first photo replaces the drawings. */
    @Synchronized
    fun addPhoto(ctx: Context, jpeg: ByteArray) {
        if (photoCount == 0) DRAWINGS.forEach { delete(ctx, "wallpapers/$it") }
        val n = photoCount + 1
        write(ctx, "wallpapers/photo-%03d-%d.jpg".format(n, System.currentTimeMillis() % 100000), jpeg)
        Repo.store().edit().putInt("home_photos", n).apply()
        bump(ctx)
    }

    /** Removes all uploaded photos and puts the family drawings back. */
    @Synchronized
    fun resetToDrawings(ctx: Context) {
        delete(ctx, "wallpapers")
        for (name in DRAWINGS) {
            ctx.assets.open("home/$name").use { write(ctx, "wallpapers/$name", it.readBytes()) }
        }
        Repo.store().edit().putInt("home_photos", 0).apply()
        bump(ctx)
    }
}
