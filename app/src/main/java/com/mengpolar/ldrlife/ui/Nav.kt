package com.mengpolar.ldrlife.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mengpolar.ldrlife.session.Phase
import com.mengpolar.ldrlife.session.Session
import com.mengpolar.ldrlife.ui.screens.ChatScreen
import com.mengpolar.ldrlife.ui.screens.HomeScreen
import com.mengpolar.ldrlife.ui.screens.PairScreen
import com.mengpolar.ldrlife.ui.screens.SettingsScreen
import com.mengpolar.ldrlife.ui.screens.SetupScreen

@Composable
fun AppRoot(session: Session) {
    val nav = rememberNavController()
    val phase = session.phase.collectAsState().value
    NavHost(
        navController = nav,
        startDestination = if (phase == Phase.UNCONFIGURED) "setup" else "home",
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
                onSettings = { nav.navigate("settings") },
            )
        }
        composable("pair") { PairScreen(session, onBack = { nav.popBackStack() }) }
        composable("chat") { ChatScreen(session, onBack = { nav.popBackStack() }) }
        composable("settings") { SettingsScreen(session, onBack = { nav.popBackStack() }) }
    }
}
