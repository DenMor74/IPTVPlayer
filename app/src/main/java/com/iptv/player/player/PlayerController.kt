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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
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
 * Единственный ExoPlayer на всё приложение. Свой буфер минимален: прокси сам держит запас,
 * плеер берёт первые же байты. Канал переключается заменой MediaItem (без пересоздания плеера).
 */
@OptIn(UnstableApi::class)
class PlayerController(private val ctx: Context) {
    val debug = DebugState()
    private val main = Handler(Looper.getMainLooper())
    private var retries = 0
    var current: Channel? = null
        private set

    private val http = DefaultHttpDataSource.Factory()
        .setUserAgent(Endpoints.USER_AGENT)          // прокси узнаёт плеер по User-Agent
        .setConnectTimeoutMs(3000)
        .setReadTimeoutMs(6000)
        .setAllowCrossProtocolRedirects(true)

    val player: ExoPlayer = ExoPlayer.Builder(ctx)
        .setLoadControl(
            DefaultLoadControl.Builder()
                // min, max, старт воспроизведения, старт после остановки буфера (мс)
                .setBufferDurationsMs(1000, 3000, 100, 500)
                .setPrioritizeTimeOverSizeThresholds(true)
                .setBackBuffer(0, false)
                .build()
        )
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(http).setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(2))
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
        while (debug.segs.size > 14) debug.segs.removeAt(0)
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
        debug.playingKey = debug.segs.firstOrNull { it.startMs <= periodPos && periodPos < it.endMs }?.key ?: -1L
        val ahead = (p.bufferedPosition - p.currentPosition).coerceAtLeast(0) / 1000.0
        val live = p.currentLiveOffset
        val last = debug.segs.lastOrNull { it.state == SegState.LOADED }
        val mbit = DefaultBandwidthMeter.getSingletonInstance(ctx).bitrateEstimate / 1_000_000
        debug.stat = buildString {
            append("буфер %.1f с".format(ahead))
            if (live != C.TIME_UNSET) append(" · от эфира %.1f с".format(live / 1000.0))
            if (last != null) append(" · сегм. %.1f МБ за %.2f с".format(last.bytes / 1048576.0, last.loadMs / 1000.0))
            append(" · %d Мбит/с".format(mbit))
        }
    }

    fun release() {
        main.removeCallbacksAndMessages(null)
        player.release()
    }
}
