package com.iptv.player.ui

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.iptv.player.MainViewModel
import com.iptv.player.Screen
import com.iptv.player.data.Channel
import com.iptv.player.data.EpgLogic
import com.iptv.player.data.Programme
import com.iptv.player.player.PlayerController
import com.iptv.player.player.SegBox
import com.iptv.player.player.SegState
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Видео на весь экран. Фокус не берёт: пульт обрабатывает PlayerOverlay. */
@OptIn(UnstableApi::class)
@Composable
fun VideoSurface(exo: Player) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                setKeepContentOnPlayerReset(true)           // последний кадр остаётся, пока грузится новый канал
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                isFocusable = false
                isFocusableInTouchMode = false
                keepScreenOn = true
                player = exo
            }
        }
    )
}

/**
 * Пульт на экране плеера:
 * Вверх — предыдущий канал, Вниз/Вправо — следующий, Влево — гид, ОК — информационная панель.
 * («Назад» обрабатывается в MainActivity: меню каналов, двойное нажатие — выход.)
 */
@Composable
fun PlayerOverlay(vm: MainViewModel) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    val osd by vm.osd.collectAsStateWithLifecycle()
    val token by vm.osdToken.collectAsStateWithLifecycle()
    val ch by vm.current.collectAsStateWithLifecycle()
    val epg by vm.epg.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }

    LaunchedEffect(screen) {
        if (screen == Screen.PLAYER) { delay(50); runCatching { focus.requestFocus() } }
    }
    LaunchedEffect(osd, token) {
        if (osd) { delay(4500); vm.hideOsd() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .then(if (screen == Screen.PLAYER) Modifier.focusRequester(focus).focusable() else Modifier)
            .onPreviewKeyEvent { e ->
                if (screen != Screen.PLAYER || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionUp, Key.ChannelUp -> vm.prev()
                    Key.DirectionDown, Key.ChannelDown, Key.DirectionRight -> vm.next()
                    Key.DirectionLeft -> vm.openGuide()
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> vm.toggleOsd()
                    else -> return@onPreviewKeyEvent false
                }
                true
            }
    ) {
        if (ui.clockOverlay && screen == Screen.PLAYER) {
            PermanentClockOverlay(now = now, position = ui.clockPosition)
        }

        AnimatedVisibility(
            visible = osd && screen == Screen.PLAYER,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut()
        ) {
            OsdPanel(ch, epg, now)
        }
    }
}

@Composable
private fun PermanentClockOverlay(now: Long, position: String) {
    val hm = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val alignment = if (position == "bottom_right") Alignment.BottomEnd else Alignment.TopEnd
    Box(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 28.dp),
        contentAlignment = alignment
    ) {
        Text(
            text = hm.format(Date(now)),
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .glass(10.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun OsdPanel(ch: Channel?, epg: Map<String, List<Programme>>, now: Long) {
    val list = epg[ch?.id]
    val cur = EpgLogic.current(list, now)
    val nxt = list?.firstOrNull { it.start >= (cur?.stop ?: now) }
    val hm = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val date = remember { SimpleDateFormat("EEE, d MMMM", Locale("ru")) }
    val frac = if (cur != null && cur.stop > cur.start)
        ((now - cur.start).toFloat() / (cur.stop - cur.start)).coerceIn(0f, 1f) else 0f

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 80.dp, vertical = 56.dp)
            .glass()
            .padding(horizontal = 44.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Box(
                Modifier.size(104.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF1D2B3D)),
                contentAlignment = Alignment.Center
            ) {
                Text(ch?.name?.take(2)?.uppercase() ?: "TV", color = Accent, fontSize = 38.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Text("${ch?.number ?: ""} · ${ch?.group ?: ""}", color = Muted, fontSize = 22.sp)
                Text(ch?.name ?: "", color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(cur?.title ?: "Нет данных о программе", color = Color.White, fontSize = 28.sp, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(hm.format(Date(now)), color = Color.White, fontSize = 52.sp, fontWeight = FontWeight.SemiBold)
                Text(date.format(Date(now)), color = Muted, fontSize = 20.sp)
            }
        }
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.22f))) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Accent))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val left = if (cur != null) {
                val min = ((cur.stop - now) / 60_000L).coerceAtLeast(0)
                "${hm.format(Date(cur.start))} – ${hm.format(Date(cur.stop))} · осталось $min мин"
            } else ""
            Text(left, color = Muted, fontSize = 22.sp)
            Text(if (nxt != null) "Далее · ${hm.format(Date(nxt.start))} ${nxt.title}" else "", color = Muted, fontSize = 22.sp, maxLines = 1)
        }
    }
}

/** Отладка: прямоугольники сегментов (качается / скачан / играет / ошибка) и короткая статистика буфера. */
@Composable
fun DebugOverlay(c: PlayerController) {
    LaunchedEffect(Unit) { while (true) { c.refreshDebug(); delay(500) } }
    val d = c.debug
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        Column(
            Modifier
                .padding(bottom = 0.dp, end = 24.dp)
                .glass(8.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                d.segs.toList().forEach { s ->
                    Box(
                        Modifier
                            .size(24.dp, 14.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(segColor(s, d.playingKey))
                    )
                }
            }
            Text(d.stat, color = Color.White, fontSize = 14.sp)
        }
    }
}

private fun segColor(s: SegBox, playingKey: Long): Color = when {
    s.key == playingKey -> Color(0xFF2563EB)           // играет
    s.state == SegState.LOADING -> Color(0xFFFFC107)   // качается
    s.state == SegState.LOADED -> Color(0xFF2DD4BF)    // скачан
    else -> Color(0xFFF87171)                          // ошибка
}

@Composable
fun ToastBar(vm: MainViewModel) {
    val t by vm.toast.collectAsStateWithLifecycle()
    t?.let { msg ->
        Box(Modifier.fillMaxSize().padding(top = 48.dp), contentAlignment = Alignment.TopCenter) {
            Text(msg, color = Color.White, fontSize = 22.sp, modifier = Modifier.glass().padding(horizontal = 24.dp, vertical = 12.dp))
        }
    }
}
