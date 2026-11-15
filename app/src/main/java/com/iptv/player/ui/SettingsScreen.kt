package com.iptv.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iptv.player.MainViewModel
import com.iptv.player.player.TerrTarget
import com.iptv.player.player.TerrestrialTv
import kotlinx.coroutines.delay

/** Настройки: адрес Home Assistant (всё остальное прописано в коде), часы, отладка, эфирное ТВ. */
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val epgInfo by vm.epgInfo.collectAsStateWithLifecycle()
    val playlistInfo by vm.playlistInfo.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    var ha by remember { mutableStateOf(ui.ha) }
    var picker by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(100); runCatching { firstFocus.requestFocus() } }   // пульт сразу попадает в меню

    // «Назад» закрывает выбор эфирного ТВ, а не весь экран
    BackHandler(enabled = picker) { picker = false }

    Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(1000.dp).fillMaxHeight(0.92f).glass()
                .verticalScroll(rememberScrollState())                           // нижние пункты прокручиваются
                .padding(48.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Настройки", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)

            Text("Адрес Home Assistant", color = Muted, fontSize = 20.sp)
            OutlinedTextField(
                value = ha, onValueChange = { ha = it }, singleLine = true,
                textStyle = TextStyle(fontSize = 26.sp, color = Color.White),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.setHa(ha); vm.refreshAll(); focusManager.clearFocus() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent, unfocusedBorderColor = Color(0x55FFFFFF), cursorColor = Accent
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvButton("Применить и обновить", Modifier.focusRequester(firstFocus)) { vm.setHa(ha); vm.refreshAll() }
            }
            Text(playlistInfo, color = Muted, fontSize = 18.sp)
            Text(epgInfo, color = Muted, fontSize = 18.sp)

            SwitchRow("Постоянные часы поверх видео", "Показывать текущее время во время просмотра", ui.clockOverlay) { vm.setClockOverlay(it) }
            if (ui.clockOverlay) {
                ClockPositionRow(ui.clockPosition) { vm.setClockPosition(it) }
            }

            SwitchRow("Звук только PCM", "Громкость регулируется пультом приставки на всех каналах (AC3/E-AC3 декодируются, без передачи на ТВ)", ui.pcm) { vm.setPcm(it) }
            SwitchRow("Отладка буфера", "Сегменты и статистика в правом нижнем углу при просмотре", ui.debug) { vm.setDebug(it) }

            FontSizeRow(ui.fontSize) { vm.setFontSize(it) }

            FocusRow(onClick = { picker = true }) {
                Column(Modifier.weight(1f)) {
                    Text("Эфирное ТВ / Источник", color = Color.White, fontSize = 26.sp)
                    Text(if (ui.terrKind == "none") "Не выбрано" else "📺 ${ui.terrLabel}", color = Muted, fontSize = 18.sp)
                }
                Text("Выбрать", color = Accent, fontSize = 22.sp)
            }
            Text("OK — выбрать · Назад — закрыть", color = Muted, fontSize = 18.sp, modifier = Modifier.padding(top = 8.dp))
        }

        if (picker) TerrestrialPicker(
            onPick = { t -> vm.setTerrestrial(t.kind, t.id, t.label); picker = false }
        )
    }
}

@Composable
private fun TerrestrialPicker(onPick: (TerrTarget) -> Unit) {
    val ctx = LocalContext.current
    val inputs = remember { TerrestrialTv.inputs(ctx).map { it.copy(label = "Вход: ${it.label}") } }
    val apps = remember { TerrestrialTv.apps(ctx).map { it.copy(label = "Приложение: ${it.label}") } }
    val firstItemFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(100); runCatching { firstItemFocus.requestFocus() } }

    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(840.dp).fillMaxHeight(0.85f).glass().padding(32.dp)) {
            Text("Выбор эфирного ТВ / источника", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    FocusRow(modifier = Modifier.focusRequester(firstItemFocus), onClick = { onPick(TerrTarget("none", "", "Не использовать")) }) {
                        Text("❌  Не использовать эфирное ТВ", color = Color.White, fontSize = 22.sp)
                    }
                }
                if (inputs.isNotEmpty()) {
                    item {
                        Text("ВХОДЫ ТЕЛЕВИЗОРА (HDMI / AV)", color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 8.dp))
                    }
                    items(inputs) { t ->
                        FocusRow(onClick = { onPick(t) }) {
                            Text("🔌  ${t.label}", color = Color.White, fontSize = 22.sp)
                        }
                    }
                }
                if (apps.isNotEmpty()) {
                    item {
                        Text("ПРИЛОЖЕНИЯ ТЕЛЕВИЗОРА", color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 8.dp))
                    }
                    items(apps) { t ->
                        FocusRow(onClick = { onPick(t) }) {
                            Text("📱  ${t.label}", color = Color.White, fontSize = 22.sp)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClockPositionRow(current: String, onSelect: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (focused) Accent.copy(alpha = 0.12f) else Color.Transparent)
            .then(if (focused) Modifier.border(3.dp, Accent, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column {
            Text("Расположение постоянных часов", color = Color.White, fontSize = 24.sp)
            Text("Угол экрана для отображения времени поверх видео", color = Muted, fontSize = 16.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            OptionButton("Верхний правый", selected = current == "top_right") { onSelect("top_right") }
            OptionButton("Нижний правый", selected = current == "bottom_right") { onSelect("bottom_right") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FontSizeRow(current: String, onSelect: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (focused) Accent.copy(alpha = 0.12f) else Color.Transparent)
            .then(if (focused) Modifier.border(3.dp, Accent, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column {
            Text("Размер шрифта", color = Color.White, fontSize = 24.sp)
            Text("Масштаб текста во всех меню и панелях (пересчитывается на лету)", color = Muted, fontSize = 16.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            OptionButton("Маленький", selected = current == "small") { onSelect("small") }
            OptionButton("Средний", selected = current == "medium") { onSelect("medium") }
            OptionButton("Большой", selected = current == "large") { onSelect("large") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OptionButton(text: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Text(
        text = text,
        color = if (selected) Accent else Color.White,
        fontSize = 20.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) Accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.10f))
            .then(if (focused) Modifier.border(3.dp, Accent, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp)
    )
}
