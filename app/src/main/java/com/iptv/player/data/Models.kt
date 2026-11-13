package com.iptv.player.data

data class Channel(
    val id: String,          // стабильный идентификатор (из адреса потока)
    val number: Int,
    val name: String,
    val group: String,
    val logo: String?,
    val url: String,
    val tvgId: String?
)

data class Programme(
    val channelId: String,   // id канала из плейлиста
    val start: Long,         // мс UTC
    val stop: Long,
    val title: String,
    val desc: String
)

/**
 * Все адреса считаются от адреса Home Assistant (единственная сетевая настройка).
 * Если файлы лежат в другом месте — поправьте DIR и пути здесь, больше нигде.
 * /config/www/iptv/... в Home Assistant доступен как <адрес>/local/iptv/...
 */
object Endpoints {
    const val DEFAULT_HA = "http://192.168.2.115:8123"
    const val USER_AGENT = "IPTVPlayer/1.0"          // по нему прокси применяет правило «стартовых сегментов»
    private const val DIR = "/local/iptv"

    fun playlist(base: String) = "$base$DIR/working/proxy.m3u"
    fun epgHourly(base: String) = "$base$DIR/epg_hourly.xml"
    fun epgToday(base: String) = "$base$DIR/epg_today.xml"
    fun epgWeek(base: String) = "$base$DIR/epg.xml"

    fun normalizeBase(raw: String): String {
        var s = raw.trim().trimEnd('/')
        if (s.isEmpty()) return DEFAULT_HA
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        if (s.startsWith("http://") && !s.substringAfter("://").contains(':')) s += ":8123"
        return s
    }
}

/** Лёгкий парсер m3u: #EXTINF с атрибутами + строка с адресом. */
object M3uParser {
    private val attr = Regex("""([\w-]+)="([^"]*)"""")

    fun parse(text: String): List<Channel> {
        val out = ArrayList<Channel>()
        val usedIds = HashSet<String>()
        var info: String? = null
        var seq = 0
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#EXTINF")) { info = line; continue }
            if (line.isEmpty() || line.startsWith("#")) continue
            val i = info ?: continue
            info = null
            val attrs = attr.findAll(i).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
            val name = i.substringAfterLast(',').trim().ifEmpty { "Канал ${seq + 1}" }
            seq++
            val number = attrs["tvg-chno"]?.toIntOrNull() ?: seq
            var id = line.substringAfterLast('/').substringBefore('?').substringBefore('.').ifBlank { seq.toString() }
            while (!usedIds.add(id)) id += "_"
            out += Channel(
                id = id, number = number, name = name,
                group = attrs["group-title"]?.ifBlank { null } ?: "Прочее",
                logo = attrs["tvg-logo"]?.ifBlank { null },
                url = line,
                tvgId = attrs["tvg-id"]?.ifBlank { null }
            )
        }
        return out
    }
}
