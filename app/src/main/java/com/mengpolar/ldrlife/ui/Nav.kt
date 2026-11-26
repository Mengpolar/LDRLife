package com.mengpolar.ldrlife.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.collectAsState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mengpolar.ldrlife.session.Phase
import com.mengpolar.ldrlife.session.Session
import com.mengpolar.ldrlife.ui.components.FloatingHeartsLayer
import com.mengpolar.ldrlife.ui.components.UpdateDialog
import com.mengpolar.ldrlife.ui.components.rememberUpdater
import com.mengpolar.ldrlife.ui.screens.ChatScreen
import com.mengpolar.ldrlife.ui.screens.HapticScreen
import com.mengpolar.ldrlife.ui.screens.HomeScreen
import com.mengpolar.ldrlife.ui.screens.MapScreen
import com.mengpolar.ldrlife.ui.screens.PairScreen
import com.mengpolar.ldrlife.ui.screens.SettingsScreen
import com.mengpolar.ldrlife.ui.screens.SetupScreen

@Composable
fun AppRoot(session: Session) {
    val nav = rememberNavController()
    val phase = session.phase.collectAsState().value
    Box(Modifier.fillMaxSize()) {
        NavHost(
        navController = nav,
        // 有打包配置的构建：永远自动连接，注册失败也在首页提示；仅"裸构建"才出现手动配置页
        startDestination = if (phase == Phase.UNCONFIGURED && session.embeddedConfig == null) "setup" else "home",
    ) {
        composable("setup") {
            SetupScreen(session, onDone = {
                nav.navigate("home") { popUpTo("setup") { inclusive = true } }
            })
        }
        composable("home") {
            HomeScreen(
                session,
                onPair = { nav.navigate("pair") },
                onChat = { nav.navigate("chat") },
                onMap = { nav.navigate("map") },
                onHaptic = { nav.navigate("haptic") },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable("pair") { PairScreen(session, onBack = { nav.popBackStack() }) }
        composable("chat") { ChatScreen(session, onBack = { nav.popBackStack() }) }
        composable("map") { MapScreen(session, onBack = { nav.popBackStack() }) }
        composable("haptic") { HapticScreen(session, onBack = { nav.popBackStack() }) }
        composable("settings") { SettingsScreen(session, onBack = { nav.popBackStack() }) }
        }

        // 触摸爱心特效：全屏浮层，事件不消费，不影响任何下层交互
        FloatingHeartsLayer(Modifier.fillMaxSize())
    }

    // 应用内更新对话框（全局唯一实例；首页横幅 / 设置页入口共用）
    UpdateDialog(rememberUpdater())
}
