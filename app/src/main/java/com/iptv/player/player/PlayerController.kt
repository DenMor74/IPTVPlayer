package com.iptv.player.player

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import com.iptv.player.data.Channel
import com.iptv.player.data.Endpoints
import java.io.IOException

enum class SegState { LOADING, LOADED, ERROR }

data class SegBox(
    val key: Long, val startMs: Long, val endMs: Long,
    val state: SegState, val bytes: Long = 0, val loadMs: Long = 0
)

/** Состояние отладочной панели: сегменты (прямоугольники) и строка статистики. */
class DebugState {
    val segs = mutableStateListOf<SegBox>()
    var playingKey by mutableStateOf(-1L)
    var stat by mutableStateOf("")
}

/**
 * Единственный ExoPlayer на всё приложение.
 * Канал переключается заменой MediaItem (без пересоздания плеера).
 */
@OptIn(UnstableApi::class)
class PlayerController(
    private val ctx: Context,
    private val pcmOnly: Boolean,
    nightMode: Boolean = false,
    nightThresholdDb: Int = -6,
    nightReleaseMs: Int = 200
) {
    val debug = DebugState()
    private val main = Handler(Looper.getMainLooper())
    private var retries = 0
    // Ночной режим (лимитер громких звуков). Работает только на PCM-пути,
    // поэтому осмыслен вместе с pcmOnly; параметры меняются на лету.
    // ВАЖНО: DefaultRenderersFactory строит несколько аудио-рендереров, каждый со своим AudioSink,
    // поэтому NightModeProcessor создаётся отдельно под каждый sink, а общими остаются параметры.
    private val nightState = NightModeState().apply {
        enabled = nightMode
        thresholdDb = nightThresholdDb
        releaseMs = nightReleaseMs
    }

    /** Обновить параметры лимитера на лету. */
    fun setNightSettings(on: Boolean, thresholdDb: Int, releaseMs: Int) {
        nightState.enabled = on
        nightState.thresholdDb = thresholdDb
        nightState.releaseMs = releaseMs
    }
    // minBufferMs = 40s, maxBufferMs = 45s: быстрый старт (150мс), отсутствие нижнего порога
    // и постоянная равномерная дозагрузка сегментов каждые ~5 сек для удержания максимального буфера.
    private val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(40000, 45000, 150, 500)
        .setPrioritizeTimeOverSizeThresholds(true)
        .setBackBuffer(0, false)
        .build()
    var current: Channel? = null
        private set

    val error = MutableStateFlow<String?>(null)

    /** Аудиодорожка выключена из-за неподдерживаемого кодека (видео идёт без звука). */
    private var audioDisabled = false

    private val http = DefaultHttpDataSource.Factory()
        .setUserAgent(Endpoints.USER_AGENT)          // прокси узнаёт плеер по User-Agent
        .setConnectTimeoutMs(3000)
        .setReadTimeoutMs(6000)
        .setAllowCrossProtocolRedirects(true)

    /**
     * pcmOnly = true отключает «сквозную» передачу AC3/E-AC3/DTS на ТВ: звук декодируется в PCM,
     * и системная громкость работает на любом канале. При передаче как есть громкость не меняется.
     */
    private val renderers = object : DefaultRenderersFactory(ctx) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? {
            // Builder БЕЗ Context: только в этом случае setAudioCapabilities реально применяется.
            // С Context media3 1.11 берёт capabilities из динамического ресивера и флаг PCM игнорирует.
            val caps = if (pcmOnly) AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES
                       else AudioCapabilities.getCapabilities(context)
            return DefaultAudioSink.Builder()
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioCapabilities(caps)
                .setAudioProcessors(arrayOf(NightModeProcessor(nightState)))
                .build()
        }
    }.apply {
        setEnableDecoderFallback(true)
        // Подхватываем программные рендереры-расширения (сборка ffmpeg с AC3/E-AC3/DTS),
        // если они добавлены в приложение; без них реализация не меняется.
        setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
    }

    val player: ExoPlayer = ExoPlayer.Builder(ctx, renderers)
        .setLoadControl(loadControl)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(http).setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy())
        )
        .build()
        .apply { playWhenReady = true }

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) retries = 0
            }
            override fun onPlayerError(ex: PlaybackException) {
                // Ошибка рендерера на аудиодорожке (нет декодера/не читается формат) -
                // выключаем звук для этого канала и играем видео дальше, вместо цикла перезапусков.
                val audioError = ex is ExoPlaybackException &&
                    ex.type == ExoPlaybackException.TYPE_RENDERER &&
                    ex.rendererFormat?.sampleMimeType?.startsWith("audio/") == true
                if (audioError && !audioDisabled) {
                    audioDisabled = true
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                        .build()
                    error.value = "Формат звука не поддерживается устройством — видео без звука"
                    main.postDelayed({ player.stop(); player.prepare() }, 500)
                    return
                }

                // закрываем соединение и заново запрашиваем, показываем сообщение
                val msg = when {
                    ex.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
                        val code = generateSequence(ex as? Throwable) { it.cause }
                            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                            .firstOrNull()?.responseCode
                        "Канал недоступен (HTTP ${code ?: "?"}), перезапуск..."
                    }
                    ex.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                        ex.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                        "Ошибка сети, перезапуск..."
                    ex.message?.contains("timeout", ignoreCase = true) == true ->
                        "Таймаут подключения, перезапуск..."
                    else -> "Ошибка воспроизведения, перезапуск..."
                }
                error.value = msg
                retries++
                main.postDelayed({
                    player.stop()
                    player.prepare()
                }, 500)
            }
        })
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onLoadStarted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
                if (mediaLoadData.dataType != C.DATA_TYPE_MEDIA) return
                add(SegBox(loadEventInfo.loadTaskId, mediaLoadData.mediaStartTimeMs, mediaLoadData.mediaEndTimeMs, SegState.LOADING))
            }
            override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
                if (mediaLoadData.dataType != C.DATA_TYPE_MEDIA) return
                replace(SegBox(loadEventInfo.loadTaskId, mediaLoadData.mediaStartTimeMs, mediaLoadData.mediaEndTimeMs,
                    SegState.LOADED, loadEventInfo.bytesLoaded, loadEventInfo.loadDurationMs))
            }
            override fun onLoadError(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData, error: IOException, wasCanceled: Boolean) {
                if (mediaLoadData.dataType != C.DATA_TYPE_MEDIA) return
                replace(SegBox(loadEventInfo.loadTaskId, mediaLoadData.mediaStartTimeMs, mediaLoadData.mediaEndTimeMs, SegState.ERROR))
            }
            override fun onLoadCanceled(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
                debug.segs.removeAll { it.key == loadEventInfo.loadTaskId }
            }
        })
    }

    private fun add(b: SegBox) {
        debug.segs.add(b)
        while (debug.segs.size > 30) debug.segs.removeAt(0)
    }

    private fun replace(b: SegBox) {
        val i = debug.segs.indexOfFirst { it.key == b.key }
        if (i >= 0) debug.segs[i] = b else add(b)
    }

    fun play(ch: Channel) {
        current = ch
        retries = 0
        debug.segs.clear()

        // Новый канал - звук снова разрешён; если кодек опять не поддерживается,
        // onPlayerError выключит его для этого канала ещё раз.
        audioDisabled = false
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .build()

        val item = MediaItem.Builder()
            .setUri(ch.url)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    // большой целевой отступ: старт с начала короткого манифеста от прокси, а не «от края эфира»
                    .setTargetOffsetMs(30_000).setMinOffsetMs(0).setMaxOffsetMs(120_000)
                    .setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f)   // без ускорения/замедления
                    .build()
            )
            .build()
        player.setMediaItem(item)
        player.prepare()
        player.play()
    }

    fun pause() = player.pause()

    /** Полная остановка (ТВ выключен): гасим декодирование и сеть, а не только звук. */
    fun stopPlayback() = main.post {
        player.stop()
        player.clearMediaItems()
    }

    /** Вернуться в эфир после долгого простоя (приложение было в фоне). */
    fun resume(stale: Boolean) {
        val ch = current
        if (stale && ch != null) play(ch) else player.play()
    }

    /** Вызывается из отладочной панели раз в 500 мс (главный поток). */
    fun refreshDebug() {
        val p = player
        var periodPos = -1L
        val tl = p.currentTimeline
        if (!tl.isEmpty) {
            val w = tl.getWindow(p.currentMediaItemIndex, Timeline.Window())
            periodPos = p.currentPosition + w.positionInFirstPeriodMs
        }

        // Удаляем сыгранные сегменты (окончание которых раньше текущей позиции)
        if (periodPos > 0) {
            debug.segs.removeAll { it.endMs > 0 && it.endMs <= periodPos }
        }

        // Сортируем сегменты по времени начала
        val sorted = debug.segs.sortedBy { it.startMs }
        if (sorted != debug.segs.toList()) {
            debug.segs.clear()
            debug.segs.addAll(sorted)
        }

        // Синий текущий играющий сегмент — самый первый (слева)
        val playingSeg = debug.segs.firstOrNull {
            (it.startMs <= periodPos && periodPos < it.endMs) || it.startMs == -1L
        } ?: debug.segs.firstOrNull()

        debug.playingKey = playingSeg?.key ?: -1L

        // Запас буфера в секундах по скачанным сегментам в памяти
        val loadedSegs = debug.segs.filter { it.state == SegState.LOADED }
        val maxEndMs = loadedSegs.maxOfOrNull { it.endMs } ?: 0L
        val segBufferSec = if (periodPos > 0 && maxEndMs > periodPos) {
            (maxEndMs - periodPos) / 1000.0
        } else {
            (p.bufferedPosition - p.currentPosition).coerceAtLeast(0) / 1000.0
        }

        val live = p.currentLiveOffset
        val last = debug.segs.lastOrNull { it.state == SegState.LOADED }
        val mbit = DefaultBandwidthMeter.getSingletonInstance(ctx).bitrateEstimate / 1_000_000
        val vf = p.videoFormat

        debug.stat = buildString {
            append("буфер %.1f с".format(segBufferSec))
            if (live != C.TIME_UNSET) append(" · от эфира %.1f с".format(live / 1000.0))
            // Все аудиодорожки потока и их статус поддержки: диагностика «канал без звука»
            val audioTracks = p.currentTracks.groups
                .filter { it.type == C.TRACK_TYPE_AUDIO }
                .joinToString("/") { g ->
                    val why = when (g.getTrackSupport(0)) {
                        C.FORMAT_HANDLED -> "ок"
                        C.FORMAT_EXCEEDS_CAPABILITIES -> "нет декодера"
                        C.FORMAT_UNSUPPORTED_SUBTYPE -> "тип не заявлен"
                        C.FORMAT_UNSUPPORTED_TYPE -> "тип не поддержан"
                        C.FORMAT_UNSUPPORTED_DRM -> "drm"
                        else -> "?"
                    }
                    "${g.getTrackFormat(0).sampleMimeType?.substringAfter('/') ?: "?"}($why)" +
                        if (!g.isTrackSelected(0)) "!" else ""
                }
            if (audioTracks.isNotEmpty()) append(" · аудио:$audioTracks")
            if (vf != null && vf.width > 0 && vf.height > 0) {
                append(" · %dx%d".format(vf.width, vf.height))
                if (vf.frameRate > 0 && vf.frameRate <= 120) append("@%d".format(vf.frameRate.toInt()))
            }
            if (last != null && last.bytes > 0) append(" · %.1f МБ".format(last.bytes / 1048576.0))
            if (mbit > 0) append(" · %d Мбит/с".format(mbit))
            if (audioDisabled) append(" · звук выкл (декодер)")
            else p.audioFormat?.let { append(" · звук ${it.sampleMimeType?.substringAfter('/') ?: "?"} ${it.channelCount}к") }
        }
    }

    fun release() {
        main.removeCallbacksAndMessages(null)
        player.release()
    }
}
