package com.iptv.player.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Загрузка иконок каналов (tvg-logo из плейлиста) с двухуровневым кэшем:
 * память (LruCache) -> диск (cacheDir/logos) -> сеть.
 * data:-URI (inline base64) декодируются сразу, на диск не пишутся.
 * Вызывается из IO-диспетчера; потокобезопасность обеспечивает LruCache.
 */
object Logos {

    @Volatile private var mem: LruCache<String, ImageBitmap>? = null
    @Volatile private var dir: File? = null

    private fun init(ctx: Context) {
        if (dir == null) synchronized(this) {
            if (dir == null) dir = File(ctx.cacheDir, "logos").apply { mkdirs() }
        }
        if (mem == null) synchronized(this) {
            if (mem == null) mem = object : LruCache<String, ImageBitmap>(8 * 1024) {   // ~8 МБ
                override fun sizeOf(key: String, value: ImageBitmap) =
                    value.asAndroidBitmap().byteCount / 1024
            }
        }
    }

    fun load(ctx: Context, url: String): ImageBitmap? {
        if (url.isBlank()) return null
        init(ctx)
        mem?.get(url)?.let { return it }

        val bmp = if (url.startsWith("data:")) decodeDataUri(url)
        else decodeFile(fileFor(url)) ?: decodeNetwork(url, fileFor(url))
        return bmp?.asImageBitmap()?.also { mem?.put(url, it) }
    }

    private fun decodeDataUri(url: String): Bitmap? = runCatching {
        val raw = Base64.decode(url.substringAfter("base64,", ""), Base64.DEFAULT)
        BitmapFactory.decodeByteArray(raw, 0, raw.size)
    }.getOrNull()

    private fun decodeFile(f: File): Bitmap? = runCatching {
        if (!f.exists() || f.length() == 0L) return@runCatching null
        BitmapFactory.decodeFile(f.absolutePath)
    }.getOrNull()

    private fun decodeNetwork(url: String, f: File): Bitmap? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", Endpoints.USER_AGENT)
        if (c.responseCode != 200) { c.disconnect(); return@runCatching null }
        val bytes = c.inputStream.use { it.readBytes() }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
        // сохраняем в кэш атомарно: tmp -> rename, чтобы не expire половиной файла
        val tmp = File(f.absolutePath + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) tmp.delete()
        bmp
    }.getOrNull()

    private fun fileFor(url: String): File =
        File(dir!!, md5(url) + "." + url.substringBefore(';').substringAfterLast('.').take(4).filter { it.isLetterOrDigit() })

    private fun md5(s: String): String =
        java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
