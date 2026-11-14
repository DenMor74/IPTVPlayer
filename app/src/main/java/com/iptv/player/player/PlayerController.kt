package com.iptv.player.player

import android.content.Context
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.TrackGroupArray
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
class PlayerController(private val ctx: Context, pcmOnly: Boolean = false) {
    val debug = DebugState()
    private val main = Handler(Looper.getMainLooper())
    private var retries = 0
    private val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(15000, 45000, 150, 500)
        .setPrioritizeTimeOverSizeThresholds(true)
        .setBackBuffer(0, false)
        .build()
    var current: Channel? = null
        private set

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
            val b = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            if (pcmOnly) b.setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
            return b.build()
        }
    }.apply { setEnableDecoderFallback(true) }

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
            override fun onPlayerError(error: PlaybackException) {
                // быстрая повторная попытка (смена базы на прокси происходит за кадром)
                if (retries++ < 4) main.postDelayed({ player.prepare() }, 800)
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

        val item = MediaItem.Builder()
            .setUri(ch.url)
            .setMimeType(MimeTypes.APPLICATION_M3U8)       // без «обнюхивания» формата
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
            if (vf != null && vf.width > 0 && vf.height > 0) {
                append(" · %dx%d".format(vf.width, vf.height))
                if (vf.frameRate > 0 && vf.frameRate <= 120) append("@%d".format(vf.frameRate.toInt()))
            }
            if (last != null && last.bytes > 0) append(" · %.1f МБ".format(last.bytes / 1048576.0))
            if (mbit > 0) append(" · %d Мбит/с".format(mbit))
            p.audioFormat?.let { append(" · звук ${it.sampleMimeType?.substringAfter('/') ?: "?"} ${it.channelCount}к") }
        }
    }

    fun release() {
        main.removeCallbacksAndMessages(null)
        player.release()
    }
}
