package com.iptv.player.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.iptv.player.data.Logos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Иконка канала с кэшем. Пока картинка не загрузилась (или логотипа нет вообще) -
 * рисуется fallback, макет строки не прыгает по ширине.
 */
@Composable
fun ChannelLogo(url: String?, modifier: Modifier = Modifier, fallback: @Composable () -> Unit) {
    val ctx = LocalContext.current
    if (url.isNullOrEmpty()) { fallback(); return }
    var bmp by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) { bmp = withContext(Dispatchers.IO) { Logos.load(ctx, url) } }
    val b = bmp
    if (b != null) Image(b, null, modifier = modifier, contentScale = ContentScale.Fit)
    else fallback()
}
