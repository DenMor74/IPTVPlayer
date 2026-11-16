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

    var lastChannelId: String?
        get() = p.getString("last", null)
        set(v) = p.edit { putString("last", v) }

    /** Избранные каналы: id через запятую. */
    var favorites: Set<String>
        get() = p.getString("favs", "")?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        set(v) = p.edit { putString("favs", v.joinToString(",")) }

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

    /** Размер шрифта панелей: "small", "medium", "large". */
    var fontSize: String
        get() = p.getString("font_size", "medium") ?: "medium"
        set(v) = p.edit { putString("font_size", v) }
}
