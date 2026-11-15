# IPTVPlayer

# Media3 поставляет consumer-правила через свои зависимости; ffmpeg-AAR содержит свои keep-правила
# (нативные методы и классы, находит JNI). Сверх них нужен конструктор FfmpegAudioRenderer:
# DefaultRenderersFactory создаёт его рефлексивно по имени класса, R8 без явного keep вырезает конструктор.

-keep class androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer {
    <init>(int, android.os.Handler, androidx.media3.exoplayer.audio.AudioRendererEventListener, androidx.media3.exoplayer.audio.AudioSink);
}

# На случай включения программного видео-рендерера (ExperimentalFfmpegVideoRenderer) — тоже рефлексия невозможна
# напрямую (используется только MediaDroid'ом), но класс не должен выпасть из AAR при shrink:
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-keep class com.hawkynt.mediadroid.ffmpeg.** { *; }

# Имена линий трассировки при падениях
-keepattributes SourceFile,LineNumberTable
