package com.iptv.player.data

/** Чистая логика программы передач (без Android): время XMLTV, слияние, проверка актуальности. */
object EpgLogic {

    /** "20261111210500 +0300" -> мс UTC. Без пояса считаем UTC (как в стандарте XMLTV). */
    fun parseXmltvTime(s: String): Long? {
        if (s.length < 14) return null
        val y = s.substring(0, 4).toIntOrNull() ?: return null
        val mo = s.substring(4, 6).toIntOrNull() ?: return null
        val d = s.substring(6, 8).toIntOrNull() ?: return null
        val h = s.substring(8, 10).toIntOrNull() ?: return null
        val mi = s.substring(10, 12).toIntOrNull() ?: return null
        val sec = s.substring(12, 14).toIntOrNull() ?: return null
        var offset = 0
        val rest = s.substring(14).trim()
        if (rest.length >= 5 && (rest[0] == '+' || rest[0] == '-')) {
            val oh = rest.substring(1, 3).toIntOrNull() ?: 0
            val om = rest.substring(3, 5).toIntOrNull() ?: 0
            offset = (oh * 3600 + om * 60) * (if (rest[0] == '-') -1 else 1)
        }
        return (daysFromCivil(y, mo, d) * 86400L + h * 3600L + mi * 60L + sec - offset) * 1000L
    }

    // Алгоритм Говарда Хиннанта: дни с 1970-01-01 для григорианской даты
    private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (m + 9) % 12
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era.toLong() * 146097L + doe - 719468L
    }

    /** Название канала для сопоставления с XMLTV: только буквы и цифры, нижний регистр. */
    fun normalizeName(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Слияние программы. overwrite=true: новые данные заменяют старые с тем же временем начала
     * (так ежечасный файл уточняет суточный); false: старые остаются. Прошедшее старше cutoff удаляется.
     */
    fun merge(
        old: Map<String, List<Programme>>,
        new: Map<String, List<Programme>>,
        overwrite: Boolean,
        cutoff: Long
    ): Map<String, List<Programme>> {
        val out = HashMap<String, List<Programme>>(old)
        for ((ch, list) in new) {
            val m = HashMap<Long, Programme>()
            val prev = out[ch].orEmpty()
            if (overwrite) { prev.forEach { m[it.start] = it }; list.forEach { m[it.start] = it } }
            else { list.forEach { m[it.start] = it }; prev.forEach { m[it.start] = it } }
            out[ch] = m.values.filter { it.stop >= cutoff }.sortedBy { it.start }
        }
        return out
    }

    /** Файл актуален, если хотя бы у половины каналов с данными идёт передача прямо сейчас. */
    fun isActual(map: Map<String, List<Programme>>, now: Long): Boolean {
        val withData = map.values.count { it.isNotEmpty() }
        if (withData == 0) return false
        val live = map.values.count { l -> l.any { now >= it.start && now < it.stop } }
        return live * 2 >= withData
    }

    fun current(list: List<Programme>?, now: Long): Programme? =
        list?.firstOrNull { now >= it.start && now < it.stop }

    fun next(list: List<Programme>?, now: Long): Programme? =
        list?.firstOrNull { it.start >= now }
}
