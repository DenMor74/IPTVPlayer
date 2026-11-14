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
    val clockOverlay: Boolean, val clockPosition: String
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = AppSettings(app)
    private val repo = Repository(app)
    val controller = PlayerController(app, settings.pcmAudioOnly)

    val channels = MutableStateFlow<List<Channel>>(emptyList())
    val current = MutableStateFlow<Channel?>(null)
    val epg = MutableStateFlow<Map<String, List<Programme>>>(emptyMap())
    val epgInfo = MutableStateFlow("Программа: не загружена")
    val playlistInfo = MutableStateFlow("Плейлист: не загружен")
    val screen = MutableStateFlow(Screen.PLAYER)
    val osd = MutableStateFlow(false)
    val osdToken = MutableStateFlow(0)
    val now = MutableStateFlow(System.currentTimeMillis())
    val toast = MutableStateFlow<String?>(null)
    val ui = MutableStateFlow(readUi())

    private var epgJob: Job? = null
    private var lastBackAt = 0L
    private var lastZapAt = 0L
    private var stoppedAt = 0L
    private var lastHourKey = -1L

    private fun readUi() = UiSettings(
        settings.haAddress, settings.showClock, settings.debugOverlay,
        settings.terrKind, settings.terrId, settings.terrLabel, settings.pcmAudioOnly,
        settings.fontSize, settings.clockOverlay, settings.clockPosition
    )

    init {
        viewModelScope.launch { while (true) { now.value = System.currentTimeMillis(); delay(15_000) } }
        viewModelScope.launch { startup() }
        viewModelScope.launch { epgWatcher() }
    }

    private fun base() = Endpoints.normalizeBase(settings.haAddress)

    // ---------- запуск: сначала кэш (мгновенно), затем свежие данные ----------
    private suspend fun startup() {
        val cached = withContext(Dispatchers.IO) { repo.cachedPlaylist() }
        if (cached != null) {
            applyChannels(cached, "кэш")
            startEpg(fromCache = true)                 // программа грузится параллельно со свежим плейлистом
        }
        when (val res = withContext(Dispatchers.IO) { repo.fetchPlaylist(base()) }) {
            is LoadResult.Success -> {
                val fresh = res.data
                val changed = cached == null || fresh.map { it.id } != cached.map { it.id }
                applyChannels(fresh, "обновлён")
                if (changed) startEpg(fromCache = cached == null)
            }
            is LoadResult.Error -> {
                if (cached == null) {
                    showToast(res.message)
                } else {
                    showToast("Не удалось обновить плейлист: ${res.message}")
                }
            }
        }
        lastHourKey = hourKey()
    }

    private fun applyChannels(list: List<Channel>, source: String) {
        channels.value = list
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
            if (fromCache) loadEpgFromCache()
            refreshEpg()
        }
    }

    private suspend fun loadEpgFromCache() {
        val chs = channels.value
        val t = System.currentTimeMillis()
        for (name in listOf("epg_hourly.xml", "epg_today.xml", "epg.xml")) {
            val m = repo.cachedEpg(name, chs, t) ?: continue
            if (EpgLogic.isActual(m, t)) { publish(m, "кэш $name", overwrite = true); return }
        }
    }

    /** hourly -> today -> week; today качается параллельно с hourly, week только если оба неактуальны. */
    private suspend fun refreshEpg() = coroutineScope {
        val chs = channels.value
        if (chs.isEmpty()) return@coroutineScope
        val b = base()
        val t = System.currentTimeMillis()
        val todayJob = async { repo.fetchEpg(b, "epg_today.xml", chs, t) }
        var actual = false
        val hourlyRes = repo.fetchEpg(b, "epg_hourly.xml", chs, t)
        if (hourlyRes is LoadResult.Success) {
            val hourly = hourlyRes.data
            if (EpgLogic.isActual(hourly, t)) { publish(hourly, "hourly", overwrite = true); actual = true }
        } else if (hourlyRes is LoadResult.Error && chs.isNotEmpty() && epg.value.isEmpty()) {
            showToast(hourlyRes.message)
        }
        val todayRes = todayJob.await()
        if (todayRes is LoadResult.Success) {
            val today = todayRes.data
            if (EpgLogic.isActual(today, t)) {
                publish(today, if (actual) "hourly + today" else "today", overwrite = !actual)
                actual = true
            }
        }
        if (!actual) {
            val weekRes = repo.fetchEpg(b, "epg.xml", chs, t)
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
    fun setClockPosition(pos: String) { settings.clockPosition = pos; ui.value = readUi() }
    fun setPcm(v: Boolean) {
        settings.pcmAudioOnly = v; ui.value = readUi()
        showToast("Режим звука изменится после перезапуска приложения")
    }
    fun setDebug(v: Boolean) { settings.debugOverlay = v; ui.value = readUi() }
    fun setFontSize(v: String) { settings.fontSize = v; ui.value = readUi() }
    fun setTerrestrial(kind: String, id: String, label: String) {
        settings.terrKind = kind; settings.terrId = id; settings.terrLabel = label
        ui.value = readUi()
    }

    fun launchTerrestrial(): Boolean {
        val u = ui.value
        if (u.terrKind == "none") { showToast("Эфирное ТВ не выбрано в настройках"); return false }
        val ok = TerrestrialTv.launch(getApplication(), u.terrKind, u.terrId)
        if (!ok) showToast("Не удалось открыть эфирное ТВ")
        return ok
    }

    override fun onCleared() {
        controller.release()
        super.onCleared()
    }
}
