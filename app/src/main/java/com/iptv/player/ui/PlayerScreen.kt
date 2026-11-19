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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
        },
        update = { it.player = exo }                         // пересадка на новый плеер при смене режима звука
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
    val pendingNum by vm.pendingNumber.collectAsStateWithLifecycle()
    val favs by vm.favorites.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    val favFocus = remember { FocusRequester() }
    val favChannels = remember(favs, channels) { favs.mapNotNull { id -> channels.firstOrNull { it.id == id } } }
    // Фокус на ярлыках избранных: таймер авто-скрытия панели при этом не тикает
    var favHasFocus by remember { mutableStateOf(false) }

    LaunchedEffect(screen) {
        if (screen == Screen.PLAYER) { delay(50); runCatching { focus.requestFocus() } }
    }
    LaunchedEffect(osd, token, favHasFocus) {
        if (osd && screen == Screen.PLAYER && !favHasFocus) { delay(4500); vm.hideOsd() }
        if (!osd) favHasFocus = false
    }

    Box(
        Modifier
            .fillMaxSize()
            .then(if (screen == Screen.PLAYER) Modifier.focusRequester(focus).focusable() else Modifier)
            .onPreviewKeyEvent { e ->
                if (screen != Screen.PLAYER) return@onPreviewKeyEvent false
                // Фокус на ярлыках избранных: влево/вправо и OK отдаём самим ярлыкам (false - не мешаем),
                // вверх/вниз возвращает управление плееру
                if (favHasFocus) {
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    return@onPreviewKeyEvent when (e.key) {
                        Key.DirectionUp, Key.DirectionDown, Key.ChannelUp, Key.ChannelDown -> {
                            runCatching { focus.requestFocus() }; true
                        }
                        else -> false
                    }
                }
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // Цифры (верхний ряд и NumPad) - поштучный ввод номера канала
                val code = e.nativeKeyEvent.keyCode
                if (code in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9) {
                    vm.pushDigit('0' + code - android.view.KeyEvent.KEYCODE_0); return@onPreviewKeyEvent true
                }
                if (code in android.view.KeyEvent.KEYCODE_NUMPAD_0..android.view.KeyEvent.KEYCODE_NUMPAD_9) {
                    vm.pushDigit('0' + code - android.view.KeyEvent.KEYCODE_NUMPAD_0); return@onPreviewKeyEvent true
                }
                when (e.key) {
                    // Вверх при открытой панели со избранными - фокус на ярлыки, иначе предыдущий канал
                    Key.DirectionUp, Key.ChannelUp ->
                        if (osd && favChannels.isNotEmpty()) { runCatching { favFocus.requestFocus() } }
                        else vm.prev()
                    Key.DirectionDown, Key.ChannelDown, Key.DirectionRight -> vm.next()
                    Key.DirectionLeft -> vm.openGuide()
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter ->
                        if (pendingNum != null) vm.commitNumberEntry()          // ввод номера завершён вручную
                        else vm.toggleOsd()
                    Key.Info -> { vm.setDebug(!ui.debug); true }
                    else -> return@onPreviewKeyEvent false
                }
                true
            }
    ) {
        if (ui.clockOverlay && screen == Screen.PLAYER) {
            PermanentClockOverlay(now = now, position = ui.clockPosition)
        }

        // Ввод номера канала с пульта: крупно вверху по центру
        AnimatedVisibility(
            visible = pendingNum != null && screen == Screen.PLAYER,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Text(
                pendingNum ?: "", color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(top = 40.dp)
                    .glass()
                    .padding(horizontal = 32.dp, vertical = 16.dp)
            )
        }

        AnimatedVisibility(
            visible = osd && screen == Screen.PLAYER,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut()
        ) {
            OsdPanel(vm, ch, epg, now, favChannels, favFocus, onFavFocusChange = { favHasFocus = it }, onPlay = { f ->
                vm.playChannel(f)
                vm.hideOsd()               // переключились по ярлыку - панель сразу скрываем
                runCatching { focus.requestFocus() }
            })
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
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun OsdPanel(
    vm: MainViewModel,
    ch: Channel?,
    epg: Map<String, List<Programme>>,
    now: Long,
    favChannels: List<Channel>,
    favFocus: FocusRequester,
    onFavFocusChange: (Boolean) -> Unit,
    onPlay: (Channel) -> Unit
) {
    val list = epg[ch?.id]
    val cur = EpgLogic.current(list, now)
    val nxt = list?.firstOrNull { it.start >= (cur?.stop ?: now) }
    val hm = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val date = remember { SimpleDateFormat("EEE, d MMMM", Locale("ru")) }
    val frac = if (cur != null && cur.stop > cur.start)
        ((now - cur.start).toFloat() / (cur.stop - cur.start)).coerceAtLeast(0f).coerceAtMost(1f) else 0f

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 80.dp, vertical = 40.dp)
            .glass()
            .padding(horizontal = 36.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---------- ярлыки избранных каналов (сетка: 5 в ряду, до 4 рядов) ----------
        if (favChannels.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .onFocusChanged { onFavFocusChange(it.hasFocus) },
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                favChannels.take(20).chunked(5).forEachIndexed { r, rowChips ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        rowChips.forEachIndexed { i, f ->
                            val fCur = EpgLogic.current(epg[f.id], now)
                            FocusRow(
                                // weight(1f) - ровно 1/5 ширины ряда (FocusRow внутри fillMaxWidth, ограничиваем весом);
                                // favFocus - на первом ярлыке списка, для прихода фокуса с плеера
                                modifier = (if (r == 0 && i == 0)
                                    Modifier.focusRequester(favFocus) else Modifier).weight(1f),
                                selected = f.id == ch?.id,
                                onFocus = {},
                                onClick = { onPlay(f) }
                            ) {
                                Column {
                                    Text(
                                        "★ ${f.name}", color = Accent, fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        fCur?.title ?: "—", color = Muted, fontSize = 11.sp, lineHeight = 13.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.12f)))
        }

        // ---------- основная информация (компактно) ----------
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            // Логотип из плейлиста, если включён и загрузился, иначе - инициалы
            val logos = vm.ui.value.logos
            ChannelLogo(
                if (logos) ch?.logo else null,
                Modifier.size(72.dp)
            ) {
                Box(
                    Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1D2B3D)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(ch?.name?.take(2)?.uppercase() ?: "TV", color = Accent, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
            }
            Column(Modifier.weight(1f)) {
                Text("${ch?.number ?: ""} · ${ch?.group ?: ""}", color = Muted, fontSize = 16.sp)
                Text(ch?.name ?: "", color = Muted, fontSize = 20.sp, maxLines = 1)
                Text(
                    cur?.title ?: "Нет данных о программе",
                    color = Accent, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(hm.format(Date(now)), color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.SemiBold)
                Text(date.format(Date(now)), color = Muted, fontSize = 15.sp)
            }
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.22f))) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Accent))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val left = if (cur != null) {
                val min = ((cur.stop - now) / 60_000L).coerceAtLeast(0)
                "${hm.format(Date(cur.start))} – ${hm.format(Date(cur.stop))} · осталось $min мин"
            } else ""
            Text(left, color = Muted, fontSize = 16.sp)
            Text(if (nxt != null) "Далее · ${hm.format(Date(nxt.start))} ${nxt.title}" else "", color = Muted, fontSize = 16.sp, maxLines = 1)
        }
    }
}

/** Отладка: компактные сегменты и статистика в строчку, прижата к низу. */
@Composable
fun DebugOverlay(c: PlayerController) {
    // Ключ - контроллер: при его пересоздании (смена режима звука) цикл перезапускается заново,
    // иначе текст продолжал бы собираться с выпущенным плеером и пропадал.
    LaunchedEffect(c) { while (true) { runCatching { c.refreshDebug() }; delay(500) } }
    val d = c.debug
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        Row(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 0.dp, end = 8.dp)
                .glass(6.dp)
                .padding(horizontal = 5.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                d.segs.toList().forEach { s ->
                    Box(
                        Modifier
                            .size(10.dp, 6.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(segColor(s, d.playingKey))
                    )
                }
            }
            Text(d.stat, color = Color.White, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

@Composable
fun ErrorBar(c: PlayerController) {
    val e by c.error.collectAsStateWithLifecycle()
    e?.let { msg ->
        // Сообщение висит 5 секунд (не стирается сразу после показа), затем убирается
        LaunchedEffect(msg) { delay(5000); c.error.value = null }
        Box(Modifier.fillMaxSize().padding(top = 48.dp), contentAlignment = Alignment.TopCenter) {
            Text(msg, color = Color(0xFFFFAB91), fontSize = 22.sp, modifier = Modifier.glass().padding(horizontal = 24.dp, vertical = 12.dp))
        }
    }
}
