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

sealed class LoadResult<out T> {
    data class Success<T>(val data: T) : LoadResult<T>()
    data class Error(val message: String) : LoadResult<Nothing>()
}

/** Загрузка плейлиста и EPG по сети с дисковым кэшем (для мгновенного старта без сети). */
class Repository(ctx: Context) {
    private val dir = File(ctx.cacheDir, "iptv").apply { mkdirs() }

    // ---------- плейлист ----------
    fun cachedPlaylist(): List<Channel>? = runCatching {
        File(dir, "proxy.m3u").takeIf { it.exists() }?.readText()
            ?.let { M3uParser.parse(it) }?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun fetchPlaylist(base: String): LoadResult<List<Channel>> = try {
        val url = Endpoints.playlist(base)
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", Endpoints.USER_AGENT)
        val code = c.responseCode
        if (code != 200) {
            c.disconnect()
            LoadResult.Error("Прокси недоступен (HTTP $code)")
        } else {
            val text = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val list = M3uParser.parse(text)
            if (list.isEmpty()) {
                LoadResult.Error("Плейлист пустой или повреждён")
            } else {
                File(dir, "proxy.m3u").writeText(text)
                LoadResult.Success(list)
            }
        }
    } catch (_: java.net.UnknownHostException) {
        LoadResult.Error("Сеть недоступна (проверьте интернет)")
    } catch (_: java.net.ConnectException) {
        LoadResult.Error("Прокси недоступен (отказ подключения)")
    } catch (_: java.net.SocketTimeoutException) {
        LoadResult.Error("Превышено время ожидания (таймаут сети)")
    } catch (e: IOException) {
        LoadResult.Error("Ошибка сети: ${e.localizedMessage ?: "неизвестно"}")
    } catch (e: Exception) {
        LoadResult.Error("Ошибка: ${e.localizedMessage ?: "неизвестно"}")
    }

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
    suspend fun fetchEpg(base: String, name: String, channels: List<Channel>, now: Long): LoadResult<Map<String, List<Programme>>> =
        withContext(Dispatchers.IO) {
            try {
                val url = when (name) {
                    "epg_hourly.xml" -> Endpoints.epgHourly(base)
                    "epg_today.xml" -> Endpoints.epgToday(base)
                    else -> Endpoints.epgWeek(base)
                }
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 4000
                c.readTimeout = 15000
                c.setRequestProperty("User-Agent", Endpoints.USER_AGENT)
                val code = c.responseCode
                if (code != 200) {
                    c.disconnect()
                    return@withContext LoadResult.Error("Прокси EPG недоступен (HTTP $code)")
                }
                val tmp = File(dir, "$name.tmp")
                val result = TeeInputStream(BufferedInputStream(c.inputStream, 64 * 1024), FileOutputStream(tmp).buffered())
                    .use { parser(channels, now).parse(it) }
                tmp.renameTo(File(dir, name))
                LoadResult.Success(result)
            } catch (_: java.net.UnknownHostException) {
                LoadResult.Error("Сеть недоступна для EPG")
            } catch (_: java.net.ConnectException) {
                LoadResult.Error("Прокси EPG недоступен")
            } catch (_: java.net.SocketTimeoutException) {
                LoadResult.Error("Таймаут загрузки EPG")
            } catch (e: IOException) {
                LoadResult.Error("Ошибка сети EPG: ${e.localizedMessage ?: "неизвестно"}")
            } catch (e: Exception) {
                LoadResult.Error("Ошибка EPG: ${e.localizedMessage ?: "неизвестно"}")
            }
        }

    suspend fun cachedEpg(name: String, channels: List<Channel>, now: Long): Map<String, List<Programme>>? =
        withContext(Dispatchers.IO) {
            runCatching {
                File(dir, name).takeIf { it.exists() }?.inputStream()?.buffered()
                    ?.use { parser(channels, now).parse(it) }
            }.getOrNull()
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
