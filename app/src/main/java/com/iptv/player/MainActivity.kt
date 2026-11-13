package com.iptv.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iptv.player.ui.DebugOverlay
import com.iptv.player.ui.GuideScreen
import com.iptv.player.ui.IptvTheme
import com.iptv.player.ui.PlayerOverlay
import com.iptv.player.ui.SettingsScreen
import com.iptv.player.ui.ToastBar
import com.iptv.player.ui.VideoSurface

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            IptvTheme {
                MainContent(viewModel = viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onForeground()
    }

    override fun onStop() {
        super.onStop()
        viewModel.onBackground()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        viewModel.onBack {
            super.onBackPressed()
        }
    }
}

@Composable
private fun MainContent(viewModel: MainViewModel) {
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        VideoSurface(exo = viewModel.controller.player)
        PlayerOverlay(vm = viewModel)

        if (ui.debug) {
            DebugOverlay(c = viewModel.controller)
        }

        when (screen) {
            Screen.PLAYER -> { }
            Screen.GUIDE -> GuideScreen(vm = viewModel)
            Screen.SETTINGS -> SettingsScreen(vm = viewModel)
        }

        ToastBar(vm = viewModel)
    }
}
