package com.iptv.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/** Настройки: адрес Home Assistant (всё остальное прописано в коде), часы, отладка, эфирное ТВ. */
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val epgInfo by vm.epgInfo.collectAsStateWithLifecycle()
    val playlistInfo by vm.playlistInfo.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    var ha by remember { mutableStateOf(ui.ha) }
    var picker by remember { mutableStateOf(false) }

    // «Назад» закрывает выбор эфирного ТВ, а не весь экран
    BackHandler(enabled = picker) { picker = false }

    Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(1000.dp).glass().padding(48.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                TvButton("Применить и обновить") { vm.setHa(ha); vm.refreshAll() }
            }
            Text(playlistInfo, color = Muted, fontSize = 18.sp)
            Text(epgInfo, color = Muted, fontSize = 18.sp)

            SwitchRow("Часы в панели информации", "Время в панели при нажатии ОК", ui.clock) { vm.setClock(it) }
            SwitchRow("Отладка буфера", "Сегменты и статистика в правом нижнем углу при просмотре", ui.debug) { vm.setDebug(it) }

            FocusRow(onClick = { picker = true }) {
                Column(Modifier.weight(1f)) {
                    Text("Эфирное ТВ", color = Color.White, fontSize = 26.sp)
                    Text(if (ui.terrKind == "none") "Не задано" else ui.terrLabel, color = Muted, fontSize = 18.sp)
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
    val targets = remember {
        buildList {
            add(TerrTarget("none", "", "Не использовать"))
            addAll(TerrestrialTv.inputs(ctx).map { it.copy(label = "Вход: ${it.label}") })
            addAll(TerrestrialTv.apps(ctx).map { it.copy(label = "Приложение: ${it.label}") })
        }
    }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(800.dp).fillMaxHeight(0.8f).glass().padding(32.dp)) {
            Text("Эфирное ТВ: приложение или вход", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(targets) { t ->
                    FocusRow(onClick = { onPick(t) }) { Text(t.label, color = Color.White, fontSize = 24.sp) }
                }
            }
        }
    }
}
