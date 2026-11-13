package com.iptv.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Accent = Color(0xFF00E5FF)          // бирюзовый акцент фокуса пульта
val GlassBg = Color(0xCC121212)         // тёмное стекло, 80% непрозрачности
val Muted = Color(0xFFB9C3CF)

/** Тёмная полупрозрачная панель со скруглением. (Размытие фона позади панели в Compose недоступно.) */
fun Modifier.glass(radius: Dp = 16.dp): Modifier = this.clip(RoundedCornerShape(radius)).background(GlassBg)

@Composable
fun IptvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(primary = Accent, background = Color.Black, surface = Color(0xFF121212)),
        content = content
    )
}

/** Строка списка с чётким фокусом пульта: бирюзовая рамка и подсветка. */
@Composable
fun FocusRow(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onFocus: () -> Unit = {},
    onClick: () -> Unit = {},
    content: @Composable RowScope.() -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                when {
                    focused -> Accent.copy(alpha = 0.18f)
                    selected -> Color.White.copy(alpha = 0.12f)
                    else -> Color.Transparent
                }
            )
            .then(if (focused) Modifier.border(3.dp, Accent, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
fun TvButton(text: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Text(
        text = text, color = Color.White, fontSize = 24.sp,
        modifier = Modifier
            .clip(shape)
            .background(Color.White.copy(alpha = 0.14f))
            .then(if (focused) Modifier.border(3.dp, Accent, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 16.dp)
    )
}

@Composable
fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (focused) Modifier.border(3.dp, Accent, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 26.sp)
            Text(subtitle, color = Muted, fontSize = 18.sp)
        }
        Switch(
            checked = checked, onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = Color.White)
        )
    }
}
