package com.iptv.player.remote

import java.net.HttpURLConnection
import java.net.URL

/**
 * Мост в Home Assistant REST API - для команд, которые нельзя исполнить с приставки.
 * Главный случай: переключение HDMI-входа ТВ. Когда плеер стоит на приставке,
 * TvInputManager видит только входы приставки; входами ТВ владеет сам телевизор,
 * и рулить ими можно через adb на ТВ, который вызывается службой androidtv в HA.
 *
 * Требуется долгоживущий токен (HA -> профиль -> Безопасность) и сущность ТВ
 * (media_player.tv_zal) - задаются в настройках приложения.
 */
object HaBridge {

    /** adb-команда на ТВ: POST {base}/api/services/androidtv/adb_command. */
    fun adbCommand(base: String, token: String, entity: String, command: String): Boolean = runCatching {
        val c = URL("$base/api/services/androidtv/adb_command").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 4000
        c.readTimeout = 8000
        c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Content-Type", "application/json")
        val body = "{\"entity_id\":${json(entity)},\"command\":${json(command)}}"
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val ok = c.responseCode in 200..299
        c.inputStream.use { it.readBytes() }   // прочитаем и закроем, чтобы соединение вернулось в пул
        ok
    }.getOrDefault(false)

    private fun json(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
