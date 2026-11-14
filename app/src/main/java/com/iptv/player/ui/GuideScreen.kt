package com.iptv.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iptv.player.MainViewModel
import com.iptv.player.data.Channel
import com.iptv.player.data.EpgLogic
import com.iptv.player.data.Programme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val ALL = "Все каналы"

/**
 * Меню каналов и программа передач. Слева категории (выдвижные), далее каналы и программа передач.
 * При фокусе на каналах/программах категории скрываются влево. Стрелка «Влево» на каналах снова выдвигает категории.
 */
@Composable
fun GuideScreen(vm: MainViewModel) {
    val channels by vm.channels.collectAsStateWithLifecycle()
    val current by vm.current.collectAsStateWithLifecycle()
    val epg by vm.epg.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()

    var group by remember { mutableStateOf(current?.group ?: ALL) }
    var focusedCh by remember { mutableStateOf(current) }
    var focusedProg by remember { mutableStateOf<Programme?>(null) }
    var categoriesExpanded by remember { mutableStateOf(false) }

    val groups by vm.guideGroups.collectAsStateWithLifecycle()
    val shown = remember(channels, group) { if (group == ALL) channels else channels.filter { it.group == group } }
    val chList = rememberLazyListState()
    val currentChannelFocus = remember { FocusRequester() }
    val categoryFocusRequester = remember { FocusRequester() }
    val hm = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    LaunchedEffect(Unit) {
        val i = shown.indexOfFirst { it.id == current?.id }
        if (i >= 0) chList.scrollToItem(i)
        delay(80)
        runCatching { currentChannelFocus.requestFocus() }
    }

    Row(Modifier.fillMaxSize().padding(16.dp).glass()) {

        // ---------- 1. категории (выдвижная панель влево) ----------
        AnimatedVisibility(
            visible = categoriesExpanded,
            enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
            exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut()
        ) {
            LazyColumn(
                Modifier
                    .width(260.dp)
                    .fillMaxHeight()
                    .padding(12.dp)
                    .onFocusChanged { if (it.hasFocus) categoriesExpanded = true }
            ) {
                item { Header("Категории") }
                if (ui.terrKind != "none") item {
                    FocusRow(onClick = { vm.launchTerrestrial() }) {
                        Text("📺", fontSize = 20.sp, modifier = Modifier.padding(end = 8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Эфирное ТВ", color = Accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            if (ui.terrLabel.isNotBlank()) {
                                Text(ui.terrLabel, color = Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Text("▶", color = Accent, fontSize = 18.sp)
                    }
                }
                item {
                    CategoryRow(
                        ALL, channels.size, group == ALL,
                        modifier = if (group == ALL) Modifier.focusRequester(categoryFocusRequester) else Modifier
                    ) { group = ALL }
                }
                items(groups) { g ->
                    CategoryRow(
                        g, channels.count { it.group == g }, group == g,
                        modifier = if (g == group) Modifier.focusRequester(categoryFocusRequester) else Modifier
                    ) { group = g }
                }
                item {
                    Spacer(Modifier.height(12.dp))
                    FocusRow(onClick = { vm.openSettings() }) { Text("Настройки", color = Color.White, fontSize = 20.sp) }
                }
            }
        }

        // ---------- 2. каналы ----------
        LazyColumn(
            modifier = Modifier
                .weight(0.40f)
                .fillMaxHeight()
                .padding(12.dp)
                .onFocusChanged { if (it.hasFocus) categoriesExpanded = false }
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) {
                        categoriesExpanded = true
                        runCatching { categoryFocusRequester.requestFocus() }
                        true
                    } else false
                },
            state = chList
        ) {
            item { Header("Каналы") }
            items(shown, key = { it.id }) { ch ->
                val title = EpgLogic.current(epg[ch.id], now)?.title
                FocusRow(
                    modifier = if (ch.id == current?.id) Modifier.focusRequester(currentChannelFocus) else Modifier,
                    selected = ch.id == current?.id,
                    onFocus = { focusedCh = ch; focusedProg = null },
                    onClick = { vm.playChannel(ch); vm.closeOverlay() }
                ) {
                    Text("${ch.number}", color = Muted, fontSize = 17.sp, modifier = Modifier.width(44.dp))
                    Column(Modifier.weight(1f)) {
                        Text(ch.name, color = Color.White, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (title != null) Text(title, color = Muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (ch.id == current?.id) Text("▶", color = Accent, fontSize = 18.sp)
                }
            }
        }

        // ---------- 3. программа и описание ----------
        Column(
            Modifier
                .weight(0.60f)
                .fillMaxHeight()
                .padding(12.dp)
                .onFocusChanged { if (it.hasFocus) categoriesExpanded = false }
        ) {
            val progList = remember(focusedCh?.id, now) {
                val all = epg[focusedCh?.id].orEmpty()
                val nowMs = now
                val current = all.firstOrNull { nowMs >= it.start && nowMs < it.stop }
                val next = all.filter { it.start >= nowMs }.take(2)
                listOfNotNull(current) + next
            }
            val chosen = focusedProg ?: progList.firstOrNull { now >= it.start && now < it.stop }
            Header("${focusedCh?.name ?: ""} · Сегодня")

            // Текущая + 2 следующие программы (без прошедших)
            Column(Modifier.weight(0.25f)) {
                progList.forEach { p ->
                    val isNow = now >= p.start && now < p.stop
                    FocusRow(
                        selected = isNow || focusedProg == p,
                        onFocus = { focusedProg = p }
                    ) {
                        Text(
                            hm.format(Date(p.start)),
                            color = if (isNow) Accent else Muted,
                            fontSize = 15.sp,
                            fontWeight = if (isNow) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.width(52.dp)
                        )
                        Text(
                            p.title,
                            color = if (isNow) Color.White else Color(0xFFB0BEC5),
                            fontSize = 15.sp,
                            lineHeight = 20.sp,
                            fontWeight = if (isNow) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (isNow) Text("▶", color = Accent, fontSize = 15.sp, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }

            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.12f)))

            // Блок описания — занимает основное пространство
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(0.75f)
                    .padding(top = 10.dp, start = 14.dp, end = 14.dp)
            ) {
                Text(
                    chosen?.let { "Описание · ${it.title}" } ?: "Описание",
                    color = Muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    chosen?.desc?.ifBlank { null } ?: "Описание передачи отсутствует",
                    color = Color(0xFFE6EDF3), fontSize = 17.sp, lineHeight = 24.sp,
                    maxLines = 20, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp)
                )
                if (chosen != null) Text(
                    "${hm.format(Date(chosen.start))} – ${hm.format(Date(chosen.stop))} · ${(chosen.stop - chosen.start) / 60_000} мин",
                    color = Accent, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp)
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
    onFocus: () -> Unit
) {
    FocusRow(modifier = modifier, selected = selected, onFocus = onFocus) {
        Text(name, color = Color.White, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("$count", color = Muted, fontSize = 16.sp)
    }
}
