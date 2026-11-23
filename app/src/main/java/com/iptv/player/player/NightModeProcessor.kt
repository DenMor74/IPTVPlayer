package com.iptv.player.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.pow

/** Общие параметры ночного режима: один объект на все инстансы процессора (по одному на каждый AudioSink). */
@UnstableApi
class NightModeState {
    @Volatile
    var enabled: Boolean = false

    /** Порог сжатия в дБFS: всё громче прижимается к нему (-6 … -24). */
    @Volatile
    var thresholdDb: Int = -6

    /** Время спада, мс (100 … 800): скорость возврата обычной громкости после громкого звука. */
    @Volatile
    var releaseMs: Int = 200
}

/**
 * Ночной режим: программный лимитер поверх PCM-потока.
 * Сигнал ниже порога проходит без изменений, громкие всплески
 * плавно прижимаются к порогу (быстрая атака, медленный спад,
 * иначе слышно «дыхание» компрессора). Тихие фрагменты (речь)
 * не усиливаются.
 *
 * Вставляется в DefaultAudioSink (см. PlayerController.buildAudioSink);
 * работает только на PCM-пути, поэтому имеет смысл при «Звук только PCM».
 *
 * ВАЖНО: DefaultRenderersFactory строит несколько аудио-рендереров, каждый со своим
 * AudioSink, поэтому процессор НЕ может быть одиночным экземпляром на приложение —
 * внутренний direct-буфер шарился бы между двумя пайплайнами и вечные коллизии.
 * Поэтому PlayerController создаёт новый процессор для каждого AudioSink,
 * а общим остаётся только флаг NightModeState.
 * Порядок байт PCM-16 в цепочке AudioProcessor - native (little-endian на Android).
 */
@OptIn(UnstableApi::class)
class NightModeProcessor(private val state: NightModeState = NightModeState()) : BaseAudioProcessor() {

    /** Атака фиксированная (5 мс) - она отвечает за отсутствие щелчков на всплесках. */
    private val attackMs = 5f

    private var gain = 1f
    private var attackCoef = 0f
    private var releaseCoef = 0f

    // Порог и спад настраиваются из настроек на лету; коэффициенты пересчитываем лениво,
    // когда значение изменилось (здесь же кэш порога в линейном масштабе).
    private var threshold = 0.5f
    private var appliedThresholdDb = 0
    private var appliedReleaseMs = 0

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        val sr = inputAudioFormat.sampleRate
        attackCoef = exp(-1f / (attackMs / 1000f * sr))
        // Спад зависит и от частоты дискретизации, и от настройки - пересчёт по флагу ниже.
        appliedReleaseMs = 0
        gain = 1f
        return inputAudioFormat
    }

    override fun onFlush() {
        gain = 1f
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        // Пустой вход (пайплайн периодически прокатывает через цепочку EMPTY для сбора
        // отложенного вывода): ничего не делаем. ВАЖНО: replaceOutputBuffer(0) здесь нельзя -
        // при переиспользовании внутреннего буфера он делает clear() со старым пределом,
        // и выходным становится весь буфер с устаревшими байтами (многократно скармливаемый
        // AudioTrack -> «бурчание»). После последнего getOutput() выходной буфер уже EMPTY.
        if (bytes == 0) {
            return
        }
        // Ленивый пересчёт порога и спада при смене настроек (или после onConfigure).
        val db = state.thresholdDb
        val rel = state.releaseMs
        if (db != appliedThresholdDb || rel != appliedReleaseMs) {
            appliedThresholdDb = db
            appliedReleaseMs = rel
            threshold = 10f.pow(db / 20f)
            releaseCoef = exp(-1f / (rel / 1000f * inputAudioFormat.sampleRate))
        }
        if (!state.enabled) {
            replaceOutputBuffer(bytes).put(inputBuffer).flip()
            return
        }
        if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) processFloat(inputBuffer) else process16(inputBuffer)
    }

    private fun process16(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        val out = replaceOutputBuffer(bytes)
        out.order(ByteOrder.nativeOrder())
        out.put(inputBuffer).flip()
        val shorts = out.asShortBuffer()
        val channels = inputAudioFormat.channelCount
        val frames = shorts.remaining() / channels
        var g = gain
        for (f in 0 until frames) {
            val base = f * channels
            var peak = 0f
            for (c in 0 until channels) {
                val a = shorts.get(base + c) / 32768f
                if (a > peak) peak = a else if (-a > peak) peak = -a
            }
            g = smoothGain(g, peak)
            for (c in 0 until channels) {
                var scaled = shorts.get(base + c) * g
                if (scaled > 32767f) scaled = 32767f else if (scaled < -32768f) scaled = -32768f
                shorts.put(base + c, scaled.toInt().toShort())
            }
        }
        gain = g
    }

    private fun processFloat(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        val out = replaceOutputBuffer(bytes)
        out.order(ByteOrder.nativeOrder())
        out.put(inputBuffer).flip()
        val floats = out.asFloatBuffer()
        val channels = inputAudioFormat.channelCount
        val frames = floats.remaining() / channels
        var g = gain
        for (f in 0 until frames) {
            val base = f * channels
            var peak = 0f
            for (c in 0 until channels) {
                val a = floats.get(base + c)
                if (a > peak) peak = a else if (-a > peak) peak = -a
            }
            g = smoothGain(g, peak)
            for (c in 0 until channels) {
                val v = floats.get(base + c) * g
                floats.put(base + c, if (v > 1f) 1f else if (v < -1f) -1f else v)
            }
        }
        gain = g
    }

    /** Быстрая атака к меньшему gain, медленный возврат к 1. */
    private fun smoothGain(g: Float, peak: Float): Float {
        val desired = if (peak > threshold) threshold / peak else 1f
        val coef = if (desired < g) attackCoef else releaseCoef
        return desired + (g - desired) * coef
    }
}
