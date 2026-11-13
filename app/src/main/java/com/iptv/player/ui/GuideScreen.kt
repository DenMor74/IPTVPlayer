package com.iptv.player.ui

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
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
 * Меню каналов и программа передач. Слева стекло на ~68% ширины: категории, каналы, программа.
 * Видео справа продолжает играть. ОК на канале переключает канал, меню остаётся открытым.
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

    val groups = remember(channels) { channels.map { it.group }.distinct() }
    val shown = remember(channels, group) { if (group == ALL) channels else channels.filter { it.group == group } }
    val hm = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    val chList = rememberLazyListState()
    val currentFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        val i = shown.indexOfFirst { it.id == current?.id }
        if (i >= 0) chList.scrollToItem(i)
        delay(80)
        runCatching { currentFocus.requestFocus() }
    }

    Row(Modifier.fillMaxHeight().fillMaxWidth(0.68f).padding(40.dp).glass()) {

        // ---------- категории ----------
        LazyColumn(Modifier.weight(0.25f).fillMaxHeight().padding(16.dp)) {
            item { Header("Категории") }
            if (ui.terrKind != "none") item {
                FocusRow(onClick = { vm.launchTerrestrial() }) {
                    Text("Эфирное ТВ", color = Accent, fontSize = 26.sp, modifier = Modifier.weight(1f))
                    Text("▶", color = Accent, fontSize = 22.sp)
                }
            }
            item { CategoryRow(ALL, channels.size, group == ALL) { group = ALL } }
            items(groups) { g -> CategoryRow(g, channels.count { it.group == g }, group == g) { group = g } }
            item {
                Spacer(Modifier.height(16.dp))
                FocusRow(onClick = { vm.openSettings() }) { Text("Настройки", color = Color.White, fontSize = 26.sp) }
            }
        }

        // ---------- каналы ----------
        LazyColumn(Modifier.weight(0.36f).fillMaxHeight().padding(16.dp), state = chList) {
            item { Header("Каналы") }
            items(shown, key = { it.id }) { ch ->
                val title = EpgLogic.current(epg[ch.id], now)?.title
                FocusRow(
                    modifier = if (ch.id == current?.id) Modifier.focusRequester(currentFocus) else Modifier,
                    onFocus = { focusedCh = ch; focusedProg = null },
                    onClick = { vm.playChannel(ch) }
                ) {
                    Text("${ch.number}", color = Muted, fontSize = 22.sp, modifier = Modifier.width(56.dp))
                    Column(Modifier.weight(1f)) {
                        Text(ch.name, color = Color.White, fontSize = 26.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (title != null) Text(title, color = Muted, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (ch.id == current?.id) Text("▶", color = Accent, fontSize = 22.sp)
                }
            }
        }

        // ---------- программа и описание ----------
        Column(Modifier.weight(0.39f).fillMaxHeight().padding(16.dp)) {
            val list = epg[focusedCh?.id].orEmpty().filter { it.stop > now - 2 * 3_600_000L }
            val chosen = focusedProg ?: EpgLogic.current(epg[focusedCh?.id], now)
            Header("${focusedCh?.name ?: ""} · Сегодня")
            LazyColumn(Modifier.weight(1f)) {
                items(list, key = { it.start }) { p ->
                    val isNow = now >= p.start && now < p.stop
                    val past = p.stop <= now
                    FocusRow(selected = isNow, onFocus = { focusedProg = p }) {
                        Text(
                            hm.format(Date(p.start)),
                            color = if (isNow) Accent else Muted, fontSize = 22.sp, modifier = Modifier.width(84.dp)
                        )
                        Text(
                            p.title, color = if (past) Color(0xFF8C97A3) else Color.White,
                            fontSize = 24.sp, fontWeight = if (isNow) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.12f)))
            Column(Modifier.fillMaxWidth().height(230.dp).padding(top = 14.dp, start = 18.dp, end = 18.dp)) {
                Text(
                    chosen?.let { "Описание · ${it.title}" } ?: "Описание", color = Muted, fontSize = 18.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    chosen?.desc?.ifBlank { null } ?: "Описание передачи отсутствует",
                    color = Color(0xFFE6EDF3), fontSize = 22.sp, lineHeight = 30.sp,
                    maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp)
                )
                if (chosen != null) Text(
                    "${hm.format(Date(chosen.start))} – ${hm.format(Date(chosen.stop))} · ${(chosen.stop - chosen.start) / 60_000} мин",
                    color = Muted, fontSize = 18.sp, modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text.uppercase(), color = Muted, fontSize = 18.sp, letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 18.dp, bottom = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun CategoryRow(name: String, count: Int, selected: Boolean, onFocus: () -> Unit) {
    FocusRow(selected = selected, onFocus = onFocus) {
        Text(name, color = Color.White, fontSize = 26.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("$count", color = Muted, fontSize = 20.sp)
    }
}
