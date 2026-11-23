package com.iptv.player

import android.content.Context
import androidx.core.content.edit
import com.iptv.player.data.Endpoints

/** Настройки в SharedPreferences. Из сети настраивается только адрес Home Assistant. */
class AppSettings(ctx: Context) {
    private val p = ctx.getSharedPreferences("iptv", Context.MODE_PRIVATE)

    var haAddress: String
        get() = p.getString("ha", Endpoints.DEFAULT_HA) ?: Endpoints.DEFAULT_HA
        set(v) = p.edit { putString("ha", v) }

    var showClock: Boolean
        get() = p.getBoolean("clock", true)
        set(v) = p.edit { putBoolean("clock", v) }

    /** Постоянные часы поверх видео во время просмотра. */
    var clockOverlay: Boolean
        get() = p.getBoolean("clock_overlay", false)
        set(v) = p.edit { putBoolean("clock_overlay", v) }

    /** Расположение постоянных часов: "top_right", "bottom_right". */
    var clockPosition: String
        get() = p.getString("clock_position", "top_right") ?: "top_right"
        set(v) = p.edit { putString("clock_position", v) }

    var debugOverlay: Boolean
        get() = p.getBoolean("debug", false)
        set(v) = p.edit { putBoolean("debug", v) }

    /** true: звук всегда декодируется в PCM (громкость работает везде); false: AC3/E-AC3 передаются на ТВ как есть. */
    var pcmAudioOnly: Boolean
        get() = p.getBoolean("pcm", false)
        set(v) = p.edit { putBoolean("pcm", v) }

    /** Ночной режим (лимитер громких звуков): действует постоянно. Работает только в режиме «Звук только PCM». */
    var nightAlways: Boolean
        get() = p.getBoolean("night_always", false)
        set(v) = p.edit { putBoolean("night_always", v) }

    /** Ночной режим: автоматическое включение по времени (с nightStartMinutes до 08:00). */
    var nightAuto: Boolean
        get() = p.getBoolean("night_auto", false)
        set(v) = p.edit { putBoolean("night_auto", v) }

    /** Время начала ночного режима в минутах от полуночи (кратно 30); конец окна фиксирован - 08:00. */
    var nightStartMinutes: Int
        get() = p.getInt("night_start", 22 * 60)
        set(v) = p.edit { putInt("night_start", v) }

    /** Степень подавления ночного режима: 1 (мягко) … 6 (максимально). В дБ переводи MainViewModel. */
    var nightLevel: Int
        get() {
            // Миграция со старой шкалы в дБ (-6 … -24, шаг 3): -6 -> 1, -9 -> 2, и т.д.
            if (!p.contains("night_level") && p.contains("night_threshold_db")) {
                val lvl = (p.getInt("night_threshold_db", -6) + 6) / -3
                return lvl.coerceIn(1, 6)
            }
            return p.getInt("night_level", 1)
        }
        set(v) = p.edit { putInt("night_level", v) }

    /** Время спада ночного режима, мс (100 … 800): скорость возврата обычной громкости. */
    var nightReleaseMs: Int
        get() = p.getInt("night_release_ms", 200)
        set(v) = p.edit { putInt("night_release_ms", v) }

    /** Перезапрос к серверу, когда буфер исчерпан и сегменты не приходят ~3 с (до 3 раз подряд). */
    var autoRestart: Boolean
        get() = p.getBoolean("auto_restart", true)
        set(v) = p.edit { putBoolean("auto_restart", v) }

    var lastChannelId: String?
        get() = p.getString("last", null)
        set(v) = p.edit { putString("last", v) }

    /** Избранные каналы: id через запятую. */
    var favorites: Set<String>
        get() = p.getString("favs", "")?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        set(v) = p.edit { putString("favs", v.joinToString(",")) }

    /** Долгоживущий токен HA (профиль -> Безопасность) для команд пульту ТВ с приставки. */
    var haToken: String
        get() = p.getString("ha_token", "") ?: ""
        set(v) = p.edit { putString("ha_token", v) }

    /** Сущность ТВ в HA (media_player.tv_zal) - куда слать adb-команды с приставки. */
    var haTvEntity: String
        get() = p.getString("ha_tv_entity", "") ?: ""
        set(v) = p.edit { putString("ha_tv_entity", v) }

    /** Эфирное ТВ: kind = none | app | input, id = пакет приложения или id входа ТВ. */
    var terrKind: String
        get() = p.getString("terr_kind", "none") ?: "none"
        set(v) = p.edit { putString("terr_kind", v) }
    var terrId: String
        get() = p.getString("terr_id", "") ?: ""
        set(v) = p.edit { putString("terr_id", v) }
    var terrLabel: String
        get() = p.getString("terr_label", "") ?: ""
        set(v) = p.edit { putString("terr_label", v) }

    /** Иконки каналов из плейлиста (tvg-logo): грузятся с кэшем, на слабых устройствах можно отключить. */
    var showLogos: Boolean
        get() = p.getBoolean("logos", true)
        set(v) = p.edit { putBoolean("logos", v) }

    /** Размер шрифта панелей: "small", "medium", "large". */
    var fontSize: String
        get() = p.getString("font_size", "medium") ?: "medium"
        set(v) = p.edit { putString("font_size", v) }
}
