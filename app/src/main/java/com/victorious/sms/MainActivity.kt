package com.victorious.sms

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.victorious.sms.ui.screens.HelpScreen
import com.victorious.sms.ui.screens.LoginScreen
import com.victorious.sms.ui.screens.RoomListScreen
import com.victorious.sms.ui.screens.RoomScreen
import com.victorious.sms.ui.screens.SplashScreen
import com.victorious.sms.ui.theme.VictorSMSTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VictorSMSTheme {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "splash") {
                    composable("splash") {
                        SplashScreen { ok ->
                            nav.navigate(if (ok) "rooms" else "login") { popUpTo("splash") { inclusive = true } }
                        }
                    }
                    composable("login") {
                        LoginScreen(
                            onLoggedIn = { nav.navigate("rooms") { popUpTo("login") { inclusive = true } } },
                            onHelp = { nav.navigate("help") },
                        )
                    }
                    composable("rooms") {
                        RoomListScreen(
                            onOpen = { nav.navigate("room/" + Uri.encode(it)) },
                            onHelp = { nav.navigate("help") },
                            onLoggedOut = { nav.navigate("login") { popUpTo(0) } },
                        )
                    }
                    composable("room/{id}") { entry ->
                        RoomScreen(Uri.decode(entry.arguments?.getString("id").orEmpty())) { nav.popBackStack() }
                    }
                    composable("help") { HelpScreen { nav.popBackStack() } }
                }
            }
        }
    }
}
