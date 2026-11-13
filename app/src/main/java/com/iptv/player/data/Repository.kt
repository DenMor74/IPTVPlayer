package com.iptv.player.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Загрузка плейлиста и EPG по сети с дисковым кэшем (для мгновенного старта без сети). */
class Repository(ctx: Context) {
    private val dir = File(ctx.cacheDir, "iptv").apply { mkdirs() }

    // ---------- плейлист ----------
    fun cachedPlaylist(): List<Channel>? = runCatching {
        File(dir, "proxy.m3u").takeIf { it.exists() }?.readText()
            ?.let { M3uParser.parse(it) }?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun fetchPlaylist(base: String): List<Channel>? = runCatching {
        val text = open(Endpoints.playlist(base)).use { it.readBytes().toString(Charsets.UTF_8) }
        val list = M3uParser.parse(text)
        if (list.isEmpty()) null else { File(dir, "proxy.m3u").writeText(text); list }
    }.getOrNull()

    // ---------- EPG ----------
    private fun parser(channels: List<Channel>, now: Long): EpgXmlParser {
        val byTvg = HashMap<String, String>()
        val byName = HashMap<String, String>()
        for (c in channels) {
            c.tvgId?.let { byTvg[it] = c.id }
            byName.putIfAbsent(EpgLogic.normalizeName(c.name), c.id)
        }
        return EpgXmlParser(byTvg, byName, now - 3 * HOUR, now + 40 * HOUR)
    }

    /** Качает и разбирает файл одновременно (поток), попутно сохраняя копию в кэш. */
    suspend fun fetchEpg(base: String, name: String, channels: List<Channel>, now: Long): Map<String, List<Programme>>? =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = when (name) {
                    "epg_hourly.xml" -> Endpoints.epgHourly(base)
                    "epg_today.xml" -> Endpoints.epgToday(base)
                    else -> Endpoints.epgWeek(base)
                }
                val tmp = File(dir, "$name.tmp")
                val result = TeeInputStream(BufferedInputStream(open(url), 64 * 1024), FileOutputStream(tmp).buffered())
                    .use { parser(channels, now).parse(it) }
                tmp.renameTo(File(dir, name))
                result
            }.getOrNull()
        }

    suspend fun cachedEpg(name: String, channels: List<Channel>, now: Long): Map<String, List<Programme>>? =
        withContext(Dispatchers.IO) {
            runCatching {
                File(dir, name).takeIf { it.exists() }?.inputStream()?.buffered()
                    ?.use { parser(channels, now).parse(it) }
            }.getOrNull()
        }

    private fun open(url: String): InputStream {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", Endpoints.USER_AGENT)
        if (c.responseCode != 200) { c.disconnect(); throw IOException("HTTP ${c.responseCode}") }
        return c.inputStream
    }

    private class TeeInputStream(private val src: InputStream, private val out: OutputStream) : InputStream() {
        override fun read(): Int { val b = src.read(); if (b >= 0) out.write(b); return b }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = src.read(b, off, len); if (n > 0) out.write(b, off, n); return n
        }
        override fun close() { try { src.close() } finally { out.close() } }
    }

    private companion object { const val HOUR = 3_600_000L }
}
