package com.iptv.player.remote

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * Встроенный HTTP-сервер «виртуального пульта»: Home Assistant (страница с кнопками каналов)
 * и будущий телефонный пульт дёргают напрямую ТВ по локальной сети:
 *
 *   GET http://<IP ТВ>:8197/play?num=11      - включить канал по номеру
 *   GET http://<IP ТВ>:8197/play?name=рен тв - включить канал по названию
 *   GET http://<IP ТВ>:8197/next | /prev     - следующий/предыдущий канал
 *   GET http://<IP ТВ>:8197/state            - JSON: текущий канал
 *   GET http://<IP ТВ>:8197/channels         - JSON: список каналов (номер + имя)
 *
 * Ответ всегда JSON; действия с UI уходят в наш callback (см. MainViewModel) -
 * оттуда на главный поток. Сервер ловит только пути из ALLOWED, всё остальное - ошибка.
 * Без аутентификации: рассчитан на доверенную домашнюю сеть.
 */
class RemoteServer(
    private val port: Int,
    private val onCommand: (path: String, params: Map<String, String>) -> String
) {
    @Volatile private var running = false
    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null

    fun start() {
        if (running) return
        running = true
        acceptThread = Thread {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(port))
                server = s
                while (running) {
                    val client = runCatching { s.accept() }.getOrNull() ?: break
                    Thread { handle(client) }.apply { isDaemon = true }.start()
                }
            } catch (_: Exception) {
                // сокет закрыт при stop() - нормальный выход
            }
        }.apply { isDaemon = true; name = "remote-server"; start() }
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
    }

    private fun handle(client: Socket) {
        try {
            client.use { c ->
                c.soTimeout = 3000
                val reader = BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8))
                val line = reader.readLine() ?: return
                val target = line.split(" ").getOrNull(1) ?: return
                val path = target.substringBefore('?').trim()
                val query = target.substringAfter('?', "")
                while (reader.readLine()?.isNotEmpty() == true) { /* дренаж заголовков */ }

                val json = if (path in ALLOWED) {
                    runCatching { onCommand(path, parseQuery(query)) }
                        .getOrElse { "{\"ok\":false,\"error\":\"handler\"}" }
                } else {
                    "{\"ok\":false,\"error\":\"unknown command\"}"
                }
                val bytes = json.toByteArray(Charsets.UTF_8)
                val resp = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: application/json; charset=utf-8\r\n")
                    append("Access-Control-Allow-Origin: *\r\n")   // чтобы веб-пульт с телефона открывался из браузера
                    append("Content-Length: ").append(bytes.size).append("\r\n")
                    append("Connection: close\r\n\r\n")
                }
                val out = c.getOutputStream()
                out.write(resp.toByteArray(Charsets.UTF_8))
                out.write(bytes)
                out.flush()
            }
        } catch (_: Exception) {
            // клиент отвалился - не важно
        }
    }

    private fun parseQuery(q: String): Map<String, String> =
        q.split('&').filter { it.contains('=') }.associate {
            val (k, v) = it.split('=', limit = 2)
            URLDecoder.decode(k, "UTF-8").trim() to URLDecoder.decode(v, "UTF-8").trim()
        }

    companion object {
        const val PORT = 8197
        private val ALLOWED = setOf("/play", "/next", "/prev", "/state", "/channels")

        /** Локальный IPv4 адрес ТВ - подсказывается в настройках. */
        fun ipAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filter { !it.isLoopbackAddress && it.address.size == 4 }
                .map { it.hostAddress }
                .firstOrNull()
        }.getOrNull()
    }
}
