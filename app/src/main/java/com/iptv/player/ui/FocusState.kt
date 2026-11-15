package com.iptv.player.ui

import androidx.compose.runtime.compositionLocalOf

// CompositionLocal для передачи состояния фокуса от FocusRow к дочерним элементам
val LocalFocusRowFocused = compositionLocalOf { false }
