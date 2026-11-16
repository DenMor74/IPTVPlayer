package com.iptv.player

import android.app.SearchManager
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.BaseColumns
import com.iptv.player.data.Channel
import com.iptv.player.data.EpgLogic
import com.iptv.player.data.Programme
import com.iptv.player.data.Repository
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Подсказки для глобального поиска Android TV (шаг 2 голосовой интеграции).
 * Благодаря провайдеру (в связке с includeInGlobalSearch в searchable.xml) приложение
 * попадает в список «где искать» у Ассистента и поиска с пульта. Возвращает:
 *  - каналы, чьё имя содержит запрос;
 *  - передачи из КЭШИРОВАННОГО EPG (сеть из провайдера не дёргаем), идущие сейчас или позже.
 * Клик по подсказке открывает приложение через iptvplayer://play?name=... и сразу играет канал.
 */
class SearchSuggestionsProvider : ContentProvider() {

    override fun onCreate() = true

    // Разобранный EPG держим в памяти 3 минуты: поиск ходит сюда на каждый символ запроса,
    // а парс XML-файла на это время слишком тяжёл.
    @Volatile private var epgParsedAt = 0L
    @Volatile private var epgMap: Map<String, List<Programme>> = emptyMap()

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? {
        val ctx = context ?: return null
        val q = (selectionArgs?.firstOrNull() ?: uri.lastPathSegment ?: "").trim()
        val out = MatrixCursor(COLUMNS)
        if (q.length < 2) return out                       // 1 символ - слишком шумно

        val now = System.currentTimeMillis()
        val channels = Repository(ctx).cachedPlaylist().orEmpty()

        // 1. Каналы по имени
        var n = 0
        for (ch in channels) {
            if (n >= MAX_CHANNELS) break
            if (ch.name.contains(q, true)) {
                out.addRow(row(n++, ch.name, "Канал · №${ch.number} · ${ch.group}", ch.name))
            }
        }

        // 2. Передачи по названию (сейчас идёт или ещё не начались)
        val epg = cachedEpg(channels, now)
        if (epg.isNotEmpty()) {
            val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
            data class Hit(val channel: Channel, val prog: Programme)
            val hits = ArrayList<Hit>()
            for (ch in channels) {
                val list = epg[ch.id] ?: continue
                // первый подходящий слот: текущий либо ближайший будущий
                val prog = list.filter { it.stop > now - 30 * 60_000L && it.title.contains(q, true) }
                    .minByOrNull { it.start }
                if (prog != null) hits += Hit(ch, prog)
            }
            for (h in hits.sortedBy { it.prog.start }.take(MAX_EPG)) {
                val whenText = if (h.prog.start > now) hm.format(Date(h.prog.start)) else "сейчас"
                out.addRow(row(n++, h.prog.title, "${h.channel.name} · $whenText", h.channel.name))
                if (n >= MAX_CHANNELS) break
            }
        }
        return out
    }

    /** Разбор кэшированного EPG без сети: первый найденный файл из известных имён. */
    private fun cachedEpg(channels: List<Channel>, now: Long): Map<String, List<Programme>> {
        val ctx = context ?: return emptyMap()
        val fresh = System.currentTimeMillis() - epgParsedAt < EPG_TTL
        if (fresh && epgMap.isNotEmpty()) return epgMap
        val repo = Repository(ctx)
        val parsed = runBlocking {
            for (name in EPG_FILES) {
                val m = runCatching { repo.cachedEpg(name, channels, now) }.getOrNull()
                if (!m.isNullOrEmpty()) return@runBlocking m
            }
            emptyMap()
        }
        epgMap = parsed
        epgParsedAt = System.currentTimeMillis()
        return parsed
    }

    private fun row(id: Int, text1: String, text2: String, channelName: String) =
        arrayOf(id, text1, text2, android.content.Intent.ACTION_VIEW,
            "iptvplayer://play?name=${Uri.encode(channelName)}")

    override fun getType(uri: Uri): String = SearchManager.SUGGEST_MIME_TYPE

    override fun insert(uri: Uri, values: ContentValues?) = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "com.iptv.player.search"
        private val COLUMNS = arrayOf(
            BaseColumns._ID,
            SearchManager.SUGGEST_COLUMN_TEXT_1,
            SearchManager.SUGGEST_COLUMN_TEXT_2,
            SearchManager.SUGGEST_COLUMN_INTENT_ACTION,
            SearchManager.SUGGEST_COLUMN_INTENT_DATA,
        )
        private val EPG_FILES = arrayOf("epg_hourly.xml", "epg_today.xml", "epg_week.xml")
        private const val MAX_CHANNELS = 10
        private const val MAX_EPG = 6
        private const val EPG_TTL = 3 * 60_000L
    }
}
