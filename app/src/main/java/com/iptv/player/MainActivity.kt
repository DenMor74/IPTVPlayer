package com.iptv.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
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
import androidx.core.content.ContextCompat
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

    // ---- отслеживание выключения ТВ ----
    private fun exitOnTvOff() {
        vm.onTvOff()
        finishAndRemoveTask()          // приложение полностью уходит с экрана и из стека
    }

    // Экран/панель погасла: и на самом ТВ, и на приставке при CEC standby
    private val tvOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> exitOnTvOff()
                "android.intent.action.HDMI_PLUGGED" ->
                    // Приставка: ТВ ушёл в standby как «отсоединение» HDMI (state=false)
                    if (!intent.getBooleanExtra("state", true)) exitOnTvOff()
            }
        }
    }

    private val tvDisplayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            val d = (getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(displayId)
            if (d?.state == Display.STATE_OFF) exitOnTvOff()
        }
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Системный broadcast + состояние дисплея: событие выключения ловит хотя бы один из них
        val f = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction("android.intent.action.HDMI_PLUGGED") }
        ContextCompat.registerReceiver(this, tvOffReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        (getSystemService(DISPLAY_SERVICE) as DisplayManager)
            .registerDisplayListener(tvDisplayListener, null)
        vm.onExternalIntent(intent)                        // команда из Home Assistant / adb при холодном старте
        setContent {
            val ui by vm.ui.collectAsStateWithLifecycle()
            IptvTheme(fontSizeScale = ui.fontSize) { App(vm) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching {
            unregisterReceiver(tvOffReceiver)
            (getSystemService(DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(tvDisplayListener)
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
