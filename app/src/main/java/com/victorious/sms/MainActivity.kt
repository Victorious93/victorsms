package com.victorious.sms

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.victorious.sms.ui.screens.ConnectedAppsScreen
import com.victorious.sms.ui.screens.HelpScreen
import com.victorious.sms.ui.screens.SettingsScreen
import com.victorious.sms.ui.screens.BlockingScreen
import com.victorious.sms.ui.screens.SpamFolderScreen
import com.victorious.sms.ui.screens.PairedPhonesScreen
import com.victorious.sms.ui.screens.SmsThreadScreen
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
                val composeTo = intent?.getStringExtra(EXTRA_COMPOSE_TO).orEmpty()
                val composeBody = intent?.getStringExtra(EXTRA_COMPOSE_BODY).orEmpty()
                NavHost(nav, startDestination = "splash") {
                    composable("splash") {
                        SplashScreen { ok ->
                            nav.navigate(if (ok) "rooms" else "login") { popUpTo("splash") { inclusive = true } }
                            if (composeTo.isNotEmpty() || composeBody.isNotEmpty()) nav.navigate("sms_new")
                        }
                    }
                    composable("login") {
                        LoginScreen(
                            onLoggedIn = { nav.navigate("rooms") { popUpTo(0) } },
                            onHelp = { nav.navigate("help") },
                            onSkip = { nav.navigate("rooms") { popUpTo(0) } },
                        )
                    }
                    composable("add_account") {
                        LoginScreen(
                            onLoggedIn = { nav.popBackStack() },
                            onHelp = { nav.navigate("help") },
                            onCancel = { nav.popBackStack() },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            onConnectedApps = { nav.navigate("connected_apps") },
                            onPairedPhones = { nav.navigate("paired_phones") },
                            extra = listOf(
                                "Blocking & spam" to { nav.navigate("blocking") },
                                "Spam folder" to { nav.navigate("spam") },
                                "Caller lookup" to { nav.navigate("lookup") },
                                "Twilio alias number" to { nav.navigate("twilio") },
                                "Phone (keypad + recents)" to {
                                    startActivity(android.content.Intent(this@MainActivity, com.victorious.sms.phone.DialerActivity::class.java))
                                },
                            ),
                        ) { nav.popBackStack() }
                    }
                    composable("blocking") { BlockingScreen { nav.popBackStack() } }
                    composable("twilio") { com.victorious.sms.ui.screens.TwilioScreen { nav.popBackStack() } }
                    composable("lookup") { com.victorious.sms.ui.screens.LookupSettingsScreen { nav.popBackStack() } }
                    composable("spam") { SpamFolderScreen { nav.popBackStack() } }
                    composable("paired_phones") { PairedPhonesScreen { nav.popBackStack() } }
                    composable("connected_apps") { ConnectedAppsScreen { nav.popBackStack() } }
                    composable("rooms") {
                        RoomListScreen(
                            onOpen = { acc, id -> nav.navigate("room/" + Uri.encode(acc) + "/" + Uri.encode(id)) },
                            onOpenSms = { tid, addr -> nav.navigate("sms/$tid/" + Uri.encode(addr)) },
                            onHelp = { nav.navigate("help") },
                            onSettings = { nav.navigate("settings") },
                            onAddAccount = { nav.navigate("add_account") },
                            onNoAccounts = { nav.navigate("login") { popUpTo(0) } },
                        )
                    }
                    composable("room/{acc}/{id}") { entry ->
                        RoomScreen(
                            Uri.decode(entry.arguments?.getString("acc").orEmpty()),
                            Uri.decode(entry.arguments?.getString("id").orEmpty()),
                        ) { nav.popBackStack() }
                    }
                    composable("sms/{tid}/{addr}") { e ->
                        SmsThreadScreen(
                            e.arguments?.getString("tid")?.toLongOrNull() ?: -1L,
                            Uri.decode(e.arguments?.getString("addr").orEmpty()),
                        ) { nav.popBackStack() }
                    }
                    composable("sms_new") {
                        SmsThreadScreen(-1L, composeTo, composeBody) { nav.popBackStack() }
                    }
                    composable("help") { HelpScreen { nav.popBackStack() } }
                }
            }
        }
    }

    companion object {
        const val EXTRA_COMPOSE_TO = "compose_to"
        const val EXTRA_COMPOSE_BODY = "compose_body"
    }
}
