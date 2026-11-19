package com.iptv.player.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iptv.player.ui.autoScrollingText
import com.iptv.player.MainViewModel
import com.iptv.player.data.Channel
import com.iptv.player.data.EpgLogic
import com.iptv.player.data.Programme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val ALL = "Все каналы"

/** Колонка меню, в которой сейчас фокус пульта. Когда фокус не в категориях, они сворачиваются влево. */
private enum class Pane { Categories, Channels, Programs }

/**
 * Меню каналов и программа передач (3 колонки: Категории | Каналы | Программа и описание).
 * Навигация пультом (влево/вправо/вверх/вниз) работает постоянно во все стороны.
 */
@Composable
fun GuideScreen(vm: MainViewModel) {
    val channels by vm.channels.collectAsStateWithLifecycle()
    val current by vm.current.collectAsStateWithLifecycle()
    val epg by vm.epg.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()

    var group by remember { mutableStateOf(current?.group ?: ALL) }
    var focusedCh by remember { mutableStateOf(current) }
    var focusedProg by remember { mutableStateOf<Programme?>(null) }
    var pane by remember { mutableStateOf(Pane.Channels) }
    val scope = rememberCoroutineScope()

    // Категории плавно сворачиваются влево, когда фокус переходит на каналы, и выкатываются обратно по нажатию влево
    val catWidth by animateDpAsState(if (pane == Pane.Categories) 200.dp else 0.dp, label = "catWidth")
    val catPadding by animateDpAsState(if (pane == Pane.Categories) 12.dp else 0.dp, label = "catPadding")
    val categoriesFocused = pane == Pane.Categories
    val catFocus = remember { FocusRequester() }

    val groups by vm.guideGroups.collectAsStateWithLifecycle()
    val shown = remember(channels, group) { if (group == ALL) channels else channels.filter { it.group == group } }
    val chList = rememberLazyListState()
    val currentChannelFocus = remember { FocusRequester() }
    val hm = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    LaunchedEffect(Unit) {
        val i = shown.indexOfFirst { it.id == current?.id }
        if (i >= 0) chList.scrollToItem(i)
        delay(80)
        runCatching { currentChannelFocus.requestFocus() }
    }

    Row(
        Modifier
            .fillMaxSize()
            .padding(8.dp)
            .glass()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) {
                    false
                } else when (e.key) {
                    // Влево с каналов - выдвинуть категории, затем перевести фокус на них.
                    // Колонка категорий при свёрнутом состоянии не композируется, поэтому фокус-таргет
                    // прикрепляется только когда выезжающая колонка начинает рисоваться - пробуем, пока не получится.
                    Key.DirectionLeft -> when (pane) {
                        Pane.Channels -> {
                            pane = Pane.Categories
                            scope.launch {
                                repeat(20) {
                                    delay(50)
                                    val ok = runCatching { catFocus.requestFocus() }.isSuccess
                                    if (ok) return@launch
                                }
                            }
                            true
                        }
                        else -> false // из категорий и из программы - обычная навигация
                    }
                    // Вправо из категорий - свернуть категории и перейти к текущему каналу
                    Key.DirectionRight -> when (pane) {
                        Pane.Categories -> {
                            pane = Pane.Channels
                            scope.launch {
                                repeat(20) {
                                    delay(50)
                                    val ok = runCatching { currentChannelFocus.requestFocus() }.isSuccess
                                    if (ok) return@launch
                                }
                            }
                            true
                        }
                        else -> false
                    }
                    else -> false
                }
            }
    ) {

        // ---------- 1. категории и настройки (сворачиваются, когда фокус уходит вправо; в нуле ширины не композируются) ----------
        if (pane == Pane.Categories || catWidth > 0.dp) LazyColumn(
            Modifier
                .width(catWidth)
                .fillMaxHeight()
                .padding(catPadding)
        ) {
            item { Header("Категории") }
            if (ui.terrKind != "none") item {
                FocusRow(onClick = { vm.launchTerrestrial() }, enabled = categoriesFocused) {
                    Text("📺", fontSize = 18.sp, modifier = Modifier.padding(end = 6.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Эфирное ТВ", color = Accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        if (ui.terrLabel.isNotBlank()) {
                            Text(ui.terrLabel, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Text("▶", color = Accent, fontSize = 16.sp)
                }
            }
            item {
                CategoryRow(
                    ALL, channels.size, group == ALL,
                    focusRequester = if (group == ALL) catFocus else null,
                    enabled = categoriesFocused,
                    onFocus = { group = ALL; pane = Pane.Categories }
                )
            }
            items(groups) { g ->
                CategoryRow(
                    g, channels.count { it.group == g }, group == g,
                    focusRequester = if (group == g) catFocus else null,
                    enabled = categoriesFocused,
                    onFocus = { group = g; pane = Pane.Categories }
                )
            }
            item {
                Spacer(Modifier.height(12.dp))
                FocusRow(onClick = { vm.openSettings() }, enabled = categoriesFocused) {
                    Text("⚙️", fontSize = 18.sp, modifier = Modifier.padding(end = 6.dp))
                    Text("Настройки", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // ---------- 2. каналы ----------
        LazyColumn(
            modifier = Modifier
                .weight(0.70f)
                .fillMaxHeight()
                .padding(2.dp),
            state = chList
        ) {
            item { Header("Каналы") }
            items(shown, key = { it.id }) { ch ->
                // Расчёт текущей передачи и прогресса - только для compose-ящихся (видимых) строк LazyColumn
                val prog = EpgLogic.current(epg[ch.id], now)
                val title = prog?.title
                val fraction = if (prog != null && prog.stop > prog.start) {
                    ((now - prog.start).toFloat() / (prog.stop - prog.start)).coerceIn(0f, 1f)
                } else null
                val isFav = ch.id in favorites
                // Долгое нажатие OK: маркер срабатывания по автоповтору кнопки (первый repeat съедается,
                // и отпускание глотается, чтобы короткий click не сыграл следом)
                var longToggled by remember { mutableStateOf(false) }
                val longPressKeys = Modifier.onPreviewKeyEvent { e ->
                    val center = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                    if (!center) return@onPreviewKeyEvent false
                    when {
                        e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 1 && !longToggled -> {
                            longToggled = true
                            vm.toggleFavorite(ch)
                            true
                        }
                        e.type == KeyEventType.KeyUp -> { val t = longToggled; longToggled = false; t }
                        else -> longToggled
                    }
                }
                FocusRow(
                    modifier = longPressKeys.then(
                        if (ch.id == current?.id) Modifier.focusRequester(currentChannelFocus) else Modifier
                    ),
                    selected = ch.id == current?.id,
                    onFocus = { focusedCh = ch; focusedProg = null; pane = Pane.Channels },
                    onClick = { vm.playChannel(ch); vm.closeOverlay() }
                ) {
                    // Логотип канала вместо цифры, максимум крупно. Нет логотипа - крупная цифра.
                    ChannelLogo(
                        if (ui.logos) ch.logo else null,
                        Modifier.size(64.dp, 40.dp)
                    ) {
                        Box(Modifier.size(64.dp, 40.dp), contentAlignment = Alignment.Center) {
                            Text("${ch.number}", color = Muted, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        // Номер канала переехал к названию: серым впереди имени
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = Muted)) { append("${ch.number}  ") }
                                withStyle(SpanStyle(color = Color.LightGray)) { append(ch.name) }
                            },
                            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, modifier = Modifier.autoScrollingText()
                        )
                        if (title != null) {
                            Text(
                                title,
                                color = Accent, fontSize = 24.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.autoScrollingText()
                            )
                            if (fraction != null) {
                                // Компактная полоска прогресса передачи прямо под названием (рисование в draw-фазе, без вложенных узлов)
                                Box(
                                    Modifier
                                        .padding(top = 1.dp)
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .drawBehind {
                                            drawRect(Color.White.copy(alpha = 0.15f))
                                            if (fraction > 0f) {
                                                drawRect(Accent, size = Size(size.width * fraction, size.height))
                                            }
                                        }
                                )
                            }
                        }
                    }
                    if (isFav) Text("★", color = Accent, fontSize = 15.sp)
                    if (ch.id == current?.id) Text("▶", color = Accent, fontSize = 18.sp)
                }
            }
        }

        // ---------- 3. программа и описание ----------
        Column(
            Modifier
                .weight(0.40f)
                .fillMaxHeight()
                .padding(2.dp)
        ) {
            val progList = remember(focusedCh?.id, now) {
                val all = epg[focusedCh?.id].orEmpty()
                val nowMs = now
                val currentProg = all.firstOrNull { nowMs >= it.start && nowMs < it.stop }
                val next = all.filter { it.start >= nowMs }.take(2)
                listOfNotNull(currentProg) + next
            }
            val chosen = focusedProg ?: progList.firstOrNull { now >= it.start && now < it.stop }
            Header("${focusedCh?.name ?: ""} · Сегодня")

            // Текущая + 2 следующие программы
            Column(Modifier.weight(0.35f)) {
                if (progList.isEmpty()) {
                    FocusRow(selected = false) {
                        Text("Нет данных о программе", color = Muted, fontSize = 15.sp)
                    }
                } else {
                    progList.forEach { p ->
                        val isNow = now >= p.start && now < p.stop
                        FocusRow(
                            selected = isNow || focusedProg == p,
                            onFocus = { focusedProg = p; pane = Pane.Programs }
                        ) {
                            Text(
                                hm.format(Date(p.start)),
                                color = if (isNow) Accent else Muted,
                                fontSize = 15.sp,
                                fontWeight = if (isNow) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.width(56.dp)
                            )
                            Text(
                                p.title,
                                color = if (isNow) Color.White else Color(0xFFB0BEC5),
                                fontSize = 15.sp,
                                lineHeight = 20.sp,
                                fontWeight = if (isNow) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).autoScrollingText()
                            )
                            if (isNow) Text("▶", color = Accent, fontSize = 15.sp, modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
            }

            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.12f)))

            // Блок описания
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(0.65f)
                    .padding(top = 10.dp, start = 14.dp, end = 14.dp)
            ) {
                Text(
                    chosen?.let { "Описание · ${it.title}" } ?: "Описание",
                    color = Muted, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold
                )
                Text(
                    chosen?.desc?.ifBlank { null } ?: "Описание передачи отсутствует",
                    color = Color(0xFFE6EDF3), fontSize = 15.sp, lineHeight = 22.sp,
                    maxLines = 14, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp)
                )
                if (chosen != null) Text(
                    "${hm.format(Date(chosen.start))} – ${hm.format(Date(chosen.stop))} · ${(chosen.stop - chosen.start) / 60_000} мин",
                    color = Accent, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text.uppercase(), color = Muted, fontSize = 15.sp, letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 14.dp, bottom = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun CategoryRow(
    name: String, count: Int, selected: Boolean,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    enabled: Boolean = true,
    onFocus: () -> Unit
) {
    FocusRow(
        modifier = modifier.then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
        selected = selected,
        enabled = enabled,
        onFocus = onFocus,
        onClick = onFocus
    ) {
        Text(name, color = Color.White, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("$count", color = Muted, fontSize = 15.sp)
    }
}

