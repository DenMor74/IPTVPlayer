package com.iptv.player

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iptv.player.ui.DebugOverlay
import com.iptv.player.ui.GuideScreen
import com.iptv.player.ui.IptvTheme
import com.iptv.player.ui.PlayerOverlay
import com.iptv.player.ui.SettingsScreen
import com.iptv.player.ui.ErrorBar
import com.iptv.player.ui.ToastBar
import com.iptv.player.ui.VideoSurface

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels {
        AndroidViewModelFactory.getInstance(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        vm.onExternalIntent(intent)                        // команда из Home Assistant / adb при холодном старте
        setContent {
            val ui by vm.ui.collectAsStateWithLifecycle()
            IptvTheme(fontSizeScale = ui.fontSize) { App(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.onExternalIntent(intent)                        // команда, пока приложение живо (launchMode=singleTask)
    }

    override fun onStart() { super.onStart(); vm.onForeground() }
    override fun onStop() { super.onStop(); vm.onBackground() }
}

@Composable
private fun App(vm: MainViewModel) {
    val activity = LocalContext.current as ComponentActivity
    val screen by vm.screen.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()

    BackHandler { vm.onBack { activity.finish() } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val controller by vm.controllerFlow.collectAsStateWithLifecycle()
        VideoSurface(controller.player)                    // видео всегда на фоне
        PlayerOverlay(vm)                                  // OSD и обработка пульта
        if (screen == Screen.GUIDE) GuideScreen(vm)
        if (screen == Screen.SETTINGS) SettingsScreen(vm)
        if (ui.debug) DebugOverlay(controller)
        ErrorBar(controller)
        ToastBar(vm)
    }
}
