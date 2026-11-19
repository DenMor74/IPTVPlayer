package com.iptv.player

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.player.data.Channel
import com.iptv.player.data.Endpoints
import com.iptv.player.data.EpgLogic
import com.iptv.player.data.LoadResult
import com.iptv.player.data.Programme
import com.iptv.player.data.Repository
import com.iptv.player.player.PlayerController
import com.iptv.player.player.TerrestrialTv
import com.iptv.player.remote.HaBridge
import com.iptv.player.remote.RemoteServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class Screen { PLAYER, GUIDE, SETTINGS }

data class UiSettings(
    val ha: String, val clock: Boolean, val debug: Boolean,
    val terrKind: String, val terrId: String, val terrLabel: String,
    val pcm: Boolean, val fontSize: String,
    val clockOverlay: Boolean, val clockPosition: String,
    val logos: Boolean
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = AppSettings(app)
    private val repo = Repository(app)
    private var controllerInstance = PlayerController(app, settings.pcmAudioOnly)
    val controller: PlayerController get() = controllerInstance
    val controllerFlow = MutableStateFlow(controllerInstance)   // для UI: перестроение при смене режима звука

    val channels = MutableStateFlow<List<Channel>>(emptyList())
    val current = MutableStateFlow<Channel?>(null)
    val epg = MutableStateFlow<Map<String, List<Programme>>>(emptyMap())
    val epgInfo = MutableStateFlow("Программа: не загружена")
    val playlistInfo = MutableStateFlow("Плейлист: не загружен")
    val guideGroups = MutableStateFlow<List<String>>(emptyList())
    val screen = MutableStateFlow(Screen.PLAYER)
    val favorites = MutableStateFlow<Set<String>>(emptySet())
    val osd = MutableStateFlow(false)
    val osdToken = MutableStateFlow(0)
    val now = MutableStateFlow(System.currentTimeMillis())
    val toast = MutableStateFlow<String?>(null)
    val ui = MutableStateFlow(readUi())

    /** Доступное обновление: версия с шары, если она новее установленной. */
    val updateAvailable = MutableStateFlow<String?>(null)

    private var epgJob: Job? = null
    private var lastBackAt = 0L
    private var lastZapAt = 0L
    private var stoppedAt = 0L
    private var lastHourKey = -1L

    private fun readUi() = UiSettings(
        settings.haAddress, settings.showClock, settings.debugOverlay,
        settings.terrKind, settings.terrId, settings.terrLabel, settings.pcmAudioOnly,
        settings.fontSize, settings.clockOverlay, settings.clockPosition,
        settings.showLogos
    )

    init {
        favorites.value = settings.favorites
        startRemoteServer()
        viewModelScope.launch { while (true) { now.value = System.currentTimeMillis(); delay(15_000) } }
        viewModelScope.launch { startup() }
        viewModelScope.launch { checkUpdate() }
        viewModelScope.launch { epgWatcher() }
    }

    // ---------- проверка обновления релиза ----------

    /** Установленная версия (versionName из манифеста). */
    fun currentVersion(): String = runCatching {
        val ctx = getApplication<Application>()
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    }.getOrDefault("")

    /** Сравнение «a.b.c» по числовым сегментам: 2.6.0 > 2.5.11. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('.').map { it.trim().toIntOrNull() ?: 0 }
        val b = current.split('.').map { it.trim().toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private suspend fun checkUpdate() {
        // Даю старту видео отработать без конкуренции за сеть
        delay(8000)
        val remote = withContext(Dispatchers.IO) { repo.fetchUpdateVersion(base()) } ?: return
        val current = currentVersion()
        if (isNewer(remote, current)) updateAvailable.value = remote
    }

    /** Скачивает APK и запускает системный установщик. */
    fun startUpdate() {
        val ctx = getApplication<Application>()
        showToast("Скачиваю обновление...")
        viewModelScope.launch {
            val apk = repo.downloadUpdate(base())
            if (apk == null) {
                withContext(Dispatchers.Main) { showToast("Не удалось скачать обновление") }
                return@launch
            }
            withContext(Dispatchers.Main) {
                runCatching {
                    val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", apk)
                    ctx.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW)
                            .setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.onFailure { showToast("Установка не запущена: ${it.message}") }
            }
        }
    }

    // ---------- виртуальный пульт (HTTP на ТВ): страница HA / телефонное приложение ----------

    private var remoteServer: RemoteServer? = null

    private fun startRemoteServer() {
        remoteServer = RemoteServer(RemoteServer.PORT) { path, params ->
            // вызов из потока сервера: только чтение StateFlow, действия - через viewModelScope (main)
            when (path) {
                "/play" -> {
                    val num = params["num"]?.toIntOrNull()
                    val name = params["name"]
                    val ch = when {
                        num != null -> findChannelByNumber(num)
                        !name.isNullOrBlank() -> findChannelByName(name)
                        else -> null
                    }
                    if (ch == null) "{\"ok\":false,\"error\":\"channel not found\"}"
                    else {
                        viewModelScope.launch { playChannel(ch) }
                        "{\"ok\":true,\"num\":${ch.number},\"name\":\"${jsonEsc(ch.name)}\"}"
                    }
                }
                "/next" -> { viewModelScope.launch { next() }; "{\"ok\":true}" }
                "/prev" -> { viewModelScope.launch { prev() }; "{\"ok\":true}" }
                "/state" -> current.value?.let { ch ->
                    "{\"ok\":true,\"num\":${ch.number},\"name\":\"${jsonEsc(ch.name)}\",\"group\":\"${jsonEsc(ch.group)}\"}"
                } ?: "{\"ok\":false,\"error\":\"not playing\"}"
                "/channels" -> channels.value.joinToString(
                    ",", prefix = "{\"ok\":true,\"channels\":[", postfix = "]}"
                ) { "{\"num\":${it.number},\"name\":\"${jsonEsc(it.name)}\",\"group\":\"${jsonEsc(it.group)}\"}" }
                else -> "{\"ok\":false,\"error\":\"unknown\"}"
            }
        }.also { it.start() }
    }

    private fun jsonEsc(s: String) =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ")

    /** Долгое нажатие OK в меню каналов: добавляет/убирает канал в избранном. */
    fun toggleFavorite(ch: Channel) {
        val cur = favorites.value.toMutableSet()
        val added = cur.add(ch.id)
        favorites.value = cur
        settings.favorites = cur
        showToast(if (added) "★ В избранном: ${ch.name}" else "Убрано из избранного: ${ch.name}")
    }

    private fun base() = Endpoints.normalizeBase(settings.haAddress)

    // ---------- запуск: мгновенный старт видео из кэша; плейлист и EPG - потом, в фоне ----------
    private suspend fun startup() {
        val cached = withContext(Dispatchers.IO) { repo.cachedPlaylist() }
        if (cached != null) applyChannels(cached, "кэш")   // воспроизведение стартует сразу

        // Свежий плейлист качаем параллельно - он не задерживает старт видео
        val playlistJob = viewModelScope.launch {
            when (val res = withContext(Dispatchers.IO) { repo.fetchPlaylist(base()) }) {
                is LoadResult.Success -> {
                    val fresh = res.data
                    val changed = cached == null || fresh.map { it.id } != cached.map { it.id }
                    applyChannels(fresh, "обновлён")
                    if (changed && cached != null) showToast("Плейлист обновлён")
                }
                is LoadResult.Error -> {
                    if (cached == null) showToast(res.message)
                    else showToast("Не удалось обновить плейлист: ${res.message}")
                }
            }
        }

        // EPG - строго после того, как видео уже поехало: сначала кэш, затем сеть, всё в фоне
        playlistJob.join()
        startEpg(fromCache = true)
        lastHourKey = hourKey()
    }

    private fun applyChannels(list: List<Channel>, source: String) {
        channels.value = list
        guideGroups.value = list.map { it.group }.distinct()
        playlistInfo.value = "Плейлист: ${list.size} каналов ($source)"
        val cur = current.value
        if (cur == null) {
            val ch = list.firstOrNull { it.id == settings.lastChannelId } ?: list.firstOrNull() ?: return
            playChannel(ch, showOsd = false)
        } else {
            current.value = list.firstOrNull { it.id == cur.id } ?: cur
        }
    }

    // ---------- программа передач ----------
    private fun startEpg(fromCache: Boolean) {
        epgJob?.cancel()
        epgJob = viewModelScope.launch {
            // Кэш и сеть — полностью параллельно, кто первый с актуальными данными — тот и победил
            val cacheJob = if (fromCache) {
                async { loadEpgFromCache() }
            } else null
            val netJob = async { refreshEpg() }
            // Ждём оба — первый кто вернёт актуальные данные, уже опубликован внутри
            cacheJob?.await()
            netJob.await()
        }
    }

    private suspend fun loadEpgFromCache() {
        val chs = channels.value
        val t = System.currentTimeMillis()
        // Параллельно читаем все 3 кэш-файла
        val hourlyDeferred = viewModelScope.async { repo.cachedEpg("epg_hourly.xml", chs, t) }
        val todayDeferred = viewModelScope.async { repo.cachedEpg("epg_today.xml", chs, t) }
        val weekDeferred = viewModelScope.async { repo.cachedEpg("epg.xml", chs, t) }

        // Проверяем по приоритету
        for ((name, deferred) in listOf(
            "epg_hourly.xml" to hourlyDeferred,
            "epg_today.xml" to todayDeferred,
            "epg.xml" to weekDeferred
        )) {
            val m = deferred.await()
            if (m != null && EpgLogic.isActual(m, t)) {
                publish(m, "кэш $name", overwrite = true)
                return
            }
        }
    }

    /** hourly и today — параллельно; week — если оба не актуальны. */
    private suspend fun refreshEpg() = coroutineScope {
        val chs = channels.value
        if (chs.isEmpty()) return@coroutineScope
        val t = System.currentTimeMillis()
        // Параллельная загрузка hourly + today
        val hourlyJob = async { repo.fetchEpg(base(), "epg_hourly.xml", chs, t) }
        val todayJob = async { repo.fetchEpg(base(), "epg_today.xml", chs, t) }

        var actual = false
        // Проверяем hourly
        val hourlyRes = hourlyJob.await()
        if (hourlyRes is LoadResult.Success) {
            val hourly = hourlyRes.data
            if (EpgLogic.isActual(hourly, t)) { publish(hourly, "hourly", overwrite = true); actual = true }
        } else if (hourlyRes is LoadResult.Error && chs.isNotEmpty() && epg.value.isEmpty()) {
            showToast(hourlyRes.message)
        }
        // Проверяем today
        val todayRes = todayJob.await()
        if (todayRes is LoadResult.Success) {
            val today = todayRes.data
            if (EpgLogic.isActual(today, t)) {
                publish(today, if (actual) "hourly + today" else "today", overwrite = !actual)
                actual = true
            }
        }
        // Week — только если оба не актуальны
        if (!actual) {
            val weekRes = repo.fetchEpg(base(), "epg.xml", chs, t)
            if (weekRes is LoadResult.Success) {
                val week = weekRes.data
                publish(week, "week", overwrite = true)
                actual = true
            } else if (weekRes is LoadResult.Error && epg.value.isEmpty()) {
                showToast(weekRes.message)
            }
        }
        if (!actual && epg.value.isEmpty()) epgInfo.value = "Программа: нет актуальных данных"
    }

    private fun publish(m: Map<String, List<Programme>>, label: String, overwrite: Boolean) {
        val t = System.currentTimeMillis()
        epg.update { EpgLogic.merge(it, m, overwrite, t - 3 * 3_600_000L) }
        epgInfo.value = "Программа: $label · ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))}"
    }

    private fun hourKey(): Long = System.currentTimeMillis() / 3_600_000L

    /** В фоне следит за актуальностью и каждый час (после HH:01) подгружает свежий epg_hourly. */
    private suspend fun epgWatcher() {
        while (true) {
            delay(60_000)
            if (channels.value.isEmpty()) continue
            val minute = Calendar.getInstance().get(Calendar.MINUTE)
            val key = hourKey()
            if (key != lastHourKey && minute >= 1) {
                lastHourKey = key
                refreshHourly()
            } else if (epg.value.isNotEmpty() && !EpgLogic.isActual(epg.value, System.currentTimeMillis())) {
                refreshEpg()
            }
        }
    }

    private suspend fun refreshHourly() {
        val chs = channels.value
        val t = System.currentTimeMillis()
        when (val res = repo.fetchEpg(base(), "epg_hourly.xml", chs, t)) {
            is LoadResult.Success -> {
                val h = res.data
                if (EpgLogic.isActual(h, t)) publish(h, "hourly", overwrite = true) else refreshEpg()
            }
            is LoadResult.Error -> {
                showToast(res.message)
                refreshEpg()
            }
        }
    }

    fun refreshAll() {
        viewModelScope.launch {
            when (val res = withContext(Dispatchers.IO) { repo.fetchPlaylist(base()) }) {
                is LoadResult.Success -> applyChannels(res.data, "обновлён")
                is LoadResult.Error -> showToast(res.message)
            }
            startEpg(fromCache = false)
        }
    }

    // ---------- управление плеером ----------
    fun playChannel(ch: Channel, showOsd: Boolean = true) {
        current.value = ch
        settings.lastChannelId = ch.id
        controller.play(ch)
        if (showOsd) showOsd()
    }

    private fun zap(delta: Int) {
        val t = SystemClock.elapsedRealtime()
        if (t - lastZapAt < 250) return                 // защита от залипания кнопки
        lastZapAt = t
        val list = channels.value
        if (list.isEmpty()) return
        val i = list.indexOfFirst { it.id == current.value?.id }
        playChannel(list[((if (i < 0) 0 else i) + delta + list.size) % list.size])
    }

    fun next() = zap(+1)
    fun prev() = zap(-1)

    fun showOsd() { osd.value = true; osdToken.update { it + 1 } }
    fun toggleOsd() { if (osd.value) osd.value = false else showOsd() }
    fun hideOsd() { osd.value = false }

    // ---------- экраны и кнопка «Назад» ----------
    fun openGuide() { osd.value = false; screen.value = Screen.GUIDE }
    fun openSettings() { screen.value = Screen.SETTINGS }
    fun closeOverlay() { screen.value = Screen.PLAYER }

    /** Назад: на плеере открывает меню каналов (передача идёт), второе нажатие за 2 с — выход. */
    fun onBack(finish: () -> Unit) {
        val t = SystemClock.elapsedRealtime()
        if (t - lastBackAt < 2000) { finish(); return }
        lastBackAt = t
        if (screen.value == Screen.PLAYER) { if (osd.value) osd.value = false; openGuide() } else closeOverlay()
        showToast("Нажмите «Назад» ещё раз для выхода")
    }

    fun onForeground() {
        val stale = stoppedAt != 0L && SystemClock.elapsedRealtime() - stoppedAt > 10_000
        if (stoppedAt != 0L) controller.resume(stale)
        stoppedAt = 0L
    }

    fun onBackground() {
        stoppedAt = SystemClock.elapsedRealtime()
        controller.pause()
    }

    /** ТВ выключен (экран ушёл в standby через CEC): выйти из плеера, не оставляя поток в фоне. */
    fun onTvOff() {
        stoppedAt = 0L                    // чтобы после включения ТВ не «воскресал» старый канал
        osd.value = false
        screen.value = Screen.PLAYER
        controller.stopPlayback()
    }

    fun showToast(msg: String) {
        toast.value = msg
        viewModelScope.launch { delay(2000); if (toast.value == msg) toast.value = null }
    }

    // ---------- настройки ----------
    fun setHa(v: String) {
        settings.haAddress = Endpoints.normalizeBase(v)     // обновление данных запускает вызывающий (refreshAll)
        ui.value = readUi()
    }
    fun setClock(v: Boolean) { settings.showClock = v; ui.value = readUi() }
    fun setClockOverlay(v: Boolean) { settings.clockOverlay = v; ui.value = readUi() }

    fun setLogos(v: Boolean) { settings.showLogos = v; ui.value = readUi() }
    fun setClockPosition(pos: String) { settings.clockPosition = pos; ui.value = readUi() }
    fun setPcm(v: Boolean) {
        settings.pcmAudioOnly = v
        // Режим звука шит в AudioSink при создании: пересоздаём контроллер, продолжая тот же канал
        val old = controllerInstance
        controllerInstance = PlayerController(getApplication(), v)
        controllerFlow.value = controllerInstance
        current.value?.let { playChannel(it, showOsd = false) }
        old.release()
        ui.value = readUi()
        showToast(if (v) "Звук: только PCM - громкость с пульта приставки" else "Звук: AC3/E-AC3 передаются на ТВ")
    }
    fun setDebug(v: Boolean) { settings.debugOverlay = v; ui.value = readUi() }
    fun setFontSize(v: String) { settings.fontSize = v; ui.value = readUi() }
    fun setTerrestrial(kind: String, id: String, label: String) {
        settings.terrKind = kind; settings.terrId = id; settings.terrLabel = label
        ui.value = readUi()
    }

    /** HA-мост настроен: токен и сущность ТВ заполнены (для переключения входов с приставки). */
    fun haConfigured() = settings.haToken.isNotBlank() && settings.haTvEntity.isNotBlank()
    fun haTokenValue() = settings.haToken
    fun haTvEntityValue() = settings.haTvEntity
    fun setHaToken(v: String) { settings.haToken = v.trim(); ui.value = readUi() }
    fun setHaTvEntity(v: String) { settings.haTvEntity = v.trim(); ui.value = readUi() }

    fun launchTerrestrial(): Boolean {
        val u = ui.value
        if (u.terrKind == "none") { showToast("Эфирное ТВ не выбрано в настройках"); return false }
        // Вход/команда на ТВ через HA: берём, когда плеер на приставке и локально входов ТВ не видно.
        // id хранит готовую adb-команду (например, "input keyevent 243" - HDMI 1).
        if (u.terrKind == "ha") {
            val token = settings.haToken
            val entity = settings.haTvEntity
            if (token.isBlank() || entity.isBlank()) {
                showToast("Не настроен доступ к HA: токен и сущность ТВ (см. настройки)")
                return false
            }
            showToast("Переключаю: ${u.terrLabel}")
            viewModelScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { HaBridge.adbCommand(base(), token, entity, u.terrId) }.getOrDefault(false)
                }
                if (!ok) showToast("Не удалось переключить вход через HA")
            }
            return true
        }
        val ok = TerrestrialTv.launch(getApplication(), u.terrKind, u.terrId)
        if (!ok) showToast("Не удалось открыть эфирное ТВ")
        return ok
    }

    // ---------- ввод номера канала цифрами с пульта ----------

    /** Незавершённый ввод номера (показывается на экране); null - ввода нет. */
    val pendingNumber = MutableStateFlow<String?>(null)
    private var numEntryJob: Job? = null

    fun pushDigit(d: Char) {
        pendingNumber.value = ((pendingNumber.value ?: "") + d).take(4)
        // Ввод подтверждается либо Enter, либо таймаутом 2 с
        numEntryJob?.cancel()
        numEntryJob = viewModelScope.launch { delay(2000); commitNumberEntry() }
    }

    fun commitNumberEntry() {
        numEntryJob?.cancel()
        val s = pendingNumber.value ?: return
        pendingNumber.value = null
        val n = s.toIntOrNull() ?: return
        val ch = findChannelByNumber(n)
        if (ch != null) playChannel(ch)
        else showToast("Канал $n не найден")
    }

    fun cancelNumberEntry() { numEntryJob?.cancel(); pendingNumber.value = null }

    // ---------- внешнее управление (Home Assistant / adb / deep link) ----------

    fun onExternalIntent(i: android.content.Intent) {
        when (i.action) {
            android.content.Intent.ACTION_VIEW ->
                i.data?.let { d -> viewModelScope.launch { handleExternalUri(d) } }

            // Глобальный поиск ТВ / ассистент: «включи канал Россия» (шаг 1 голосовой интеграции)
            android.content.Intent.ACTION_SEARCH,
            "com.google.android.gms.actions.SEARCH_ACTION",
            "android.media.action.MEDIA_PLAY_FROM_SEARCH" -> {
                val q = i.getStringExtra("query")
                    ?: i.getStringExtra(android.app.SearchManager.QUERY)
                if (!q.isNullOrBlank()) viewModelScope.launch {
                    waitForChannels()
                    val ch = findChannelByName(q)
                    if (ch != null) playChannel(ch) else showToast("Канал не найден: $q")
                }
            }
        }
    }

    /** Ждёт загрузку плейлиста (до ~15 с) - для внешних команд на холодном старте. */
    private suspend fun waitForChannels() {
        repeat(60) {
            if (channels.value.isNotEmpty()) return
            delay(250)
        }
    }

    private suspend fun handleExternalUri(uri: android.net.Uri) {
        waitForChannels()
        val ch = resolveExternalChannel(uri)
        if (ch != null) playChannel(ch) else showToast("Канал не найден: $uri")
    }

    private fun resolveExternalChannel(uri: android.net.Uri): Channel? = when (uri.scheme?.lowercase()) {
        // iptvplayer://play?num=11 | ?name=рен тв | ?url=<адрес потока>
        "iptvplayer" -> {
            val num = uri.getQueryParameter("num")?.toIntOrNull()
            val name = uri.getQueryParameter("name")
            val url = uri.getQueryParameter("url")
            when {
                num != null -> findChannelByNumber(num)
                !name.isNullOrBlank() -> findChannelByName(name)
                !url.isNullOrBlank() -> findChannelByUrl(url) ?: externalChannel(url, "Внешний поток")
                else -> null
            }
        }
        // Прямая ссылка на поток (am start -a VIEW -d "<http-url>" -t "video/*")
        "http", "https", "rtsp", "rtmp", "udp", "m3u8" -> findChannelByUrl(uri.toString()) ?: externalChannel(uri.toString(), uri.host ?: "Внешний поток")
        else -> null
    }

    private fun findChannelByNumber(n: Int): Channel? = channels.value.firstOrNull { it.number == n }

    private fun findChannelByName(q: String): Channel? {
        val target = EpgLogic.normalizeName(q)
        return channels.value.firstOrNull { EpgLogic.normalizeName(it.name) == target }
            ?: channels.value.firstOrNull { EpgLogic.normalizeName(it.name).contains(target) }
    }

    private fun findChannelByUrl(u: String): Channel? =
        channels.value.firstOrNull { it.url == u }
            ?: channels.value.firstOrNull { it.id == externalChannelId(u) }

    private fun externalChannel(url: String, name: String) = Channel(
        id = externalChannelId(url), number = 0, name = name,
        group = "Внешний", logo = null, url = url, tvgId = null
    )

    // id прямой ссылки совпадает с логикой M3uParser: имя файла без расширения и query
    private fun externalChannelId(url: String) =
        url.substringBefore('?').substringAfterLast('/').substringBefore('.').ifBlank { "ext" } + "_ext"

    override fun onCleared() {
        remoteServer?.stop()
        controllerInstance.release()
        super.onCleared()
    }
}
