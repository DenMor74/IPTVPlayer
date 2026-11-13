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

    var debugOverlay: Boolean
        get() = p.getBoolean("debug", false)
        set(v) = p.edit { putBoolean("debug", v) }

    var lastChannelId: String?
        get() = p.getString("last", null)
        set(v) = p.edit { putString("last", v) }

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
}
