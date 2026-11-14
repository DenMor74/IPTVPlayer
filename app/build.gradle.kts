plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.iptv.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.iptv.player"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "2.2.7"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("IPTVPlayer-v${android.defaultConfig.versionName}-${variant.name}.apk")
        }
    }
}

tasks.register("copyApkToShare") {
    dependsOn("assembleDebug")
    doLast {
        val apkDir = file("build/outputs/apk/debug")
        val apkFile = apkDir.listFiles().filter { it.name.endsWith(".apk") }.maxByOrNull { it.lastModified() }
        if (apkFile != null) {
            val sharePath = "\\\\ha\\SHARE\\_TV\\"
            copy {
                from(apkFile)
                into(sharePath)
            }
            println("Copied ${apkFile.name} to $sharePath")
        } else {
            println("No APK found to copy")
        }
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation(libs.androidx.appcompat)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Media3: ExoPlayer + HLS + PlayerView
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
}
