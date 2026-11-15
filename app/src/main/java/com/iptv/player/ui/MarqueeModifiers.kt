package com.iptv.player.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.debugInspectorInfo
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Модификатор для автоматической прокрутки текста в горизонтальном направлении.
 * Прокрутка активируется только когда элемент в фокусе и текст не помещается в доступную ширину.
 * Для работы внутри FocusRow использует CompositionLocal состояние фокуса.
 *
 * @param speed Скорость прокрутки в пикселях в секунду (по умолчанию 100)
 * @param delayBeforeStart Задержка перед началом прокрутки в миллисекундах (по умолчанию 500)
 */
fun Modifier.autoScrollingText(
    speed: Float = 100f,
    delayBeforeStart: Long = 500
): Modifier = composed(inspectorInfo = debugInspectorInfo {
    name = "autoScrollingText"
    properties["speed"] = speed
    properties["delayBeforeStart"] = delayBeforeStart
}) {
    // Используем CompositionLocal для получения состояния фокуса от FocusRow
    val isFocused = LocalFocusRowFocused.current
    var textWidth by remember { mutableStateOf(0f) }
    var containerWidth by remember { mutableStateOf(0f) }

    val offsetAnim = remember { Animatable(0f) }

    // Фокус и размеры текста управляют прокруткой: один эффект вместо двух (меньше перезапусков корутин)
    LaunchedEffect(isFocused, textWidth, containerWidth) {
        if (!isFocused || textWidth <= containerWidth || containerWidth <= 0) {
            offsetAnim.snapTo(0f)
        } else {
            val totalScroll = textWidth - containerWidth

            // Задержка перед стартом
            delay(delayBeforeStart)

            // Бесконечная анимация прокрутки
            while (true) {
                // Прокрутка влево
                offsetAnim.animateTo(
                    targetValue = -totalScroll,
                    animationSpec = tween(
                        durationMillis = ((totalScroll / speed) * 1000).toInt(),
                        easing = LinearEasing
                    )
                )

                // Сброс в начальное положение (мгновенно) и пауза перед повторением
                offsetAnim.snapTo(0f)
                delay(delayBeforeStart)
            }
        }
    }

    Modifier
        .clipToBounds()
        .graphicsLayer { translationX = offsetAnim.value }
        .layout { measurable, constraints ->
            // Измеряем контент без ограничения по ширине, чтобы узнать реальную ширину текста
            val full = measurable.measure(Constraints(maxWidth = Constraints.Infinity))
            val newTw = full.width.toFloat()
            if (textWidth != newTw) textWidth = newTw      // запись только при изменении - без лишних рекомпозиций
            val newCw = constraints.maxWidth.toFloat()
            if (containerWidth != newCw) containerWidth = newCw
            val width = if (constraints.hasBoundedWidth) constraints.maxWidth else full.width
            layout(width, full.height) {
                full.place(0, 0)
            }
        }
}
