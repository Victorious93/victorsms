package com.victorious.sms

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.victorious.sms.archive.PendingShare
import com.victorious.sms.bridge.PendingDraftsScreen
import com.victorious.sms.ui.screens.ConnectedAppsScreen
import com.victorious.sms.ui.screens.CarrierSmsActivityScreen
import com.victorious.sms.ui.screens.ImportWizardScreen
import com.victorious.sms.ui.screens.SavedConversationScreen
import com.victorious.sms.ui.screens.SavedConversationsScreen
import com.victorious.sms.ui.screens.ScheduledTextsScreen
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
        // A shared FILE (WhatsApp "Export chat", a CSV from a file manager) goes to the import wizard. Shared plain TEXT with
        // no file composes a new SMS, as in other messaging apps. Nothing is saved until the owner confirms the preview.
        val sharedFile = handleSharedFile(intent)
        setContent {
            VictorSMSTheme {
                val nav = rememberNavController()
                val composeTo = intent?.getStringExtra(EXTRA_COMPOSE_TO).orEmpty()
                val composeBody = (
                    intent?.getStringExtra(EXTRA_COMPOSE_BODY)
                        ?: if (intent?.action == Intent.ACTION_SEND && !sharedFile) intent?.getStringExtra(Intent.EXTRA_TEXT) else null
                    ).orEmpty()
                NavHost(nav, startDestination = "splash") {
                    composable("splash") {
                        SplashScreen { ok ->
                            nav.navigate(if (ok) "rooms" else "login") { popUpTo("splash") { inclusive = true } }
                            if (composeTo.isNotEmpty() || composeBody.isNotEmpty()) nav.navigate("sms_new")
                            if (sharedFile) nav.navigate("import/-1")
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
                                "SIP / VoIP (own server)" to { nav.navigate("sip") },
                                "Email accounts" to { nav.navigate("mail_accounts") },
                                "Email outbox" to { nav.navigate("mail_outbox") },
                                "Scheduled texts" to { nav.navigate("scheduled") },
                                "SMS activity" to { nav.navigate("sms_activity") },
                                "Drafts to review" to { nav.navigate("pending_drafts") },
                                "Saved conversations (import)" to { nav.navigate("saved") },
                                "Blocking & spam" to { nav.navigate("blocking") },
                                "Spam folder" to { nav.navigate("spam") },
                                "Caller lookup" to { nav.navigate("lookup") },
                                "Twilio alias number" to { nav.navigate("twilio") },
                                "Assistant & persona" to { nav.navigate("assistant") },
                                "SMS remote-command trigger" to { nav.navigate("sms_trigger") },
                                "Phone (keypad + recents)" to {
                                    startActivity(android.content.Intent(this@MainActivity, com.victorious.sms.phone.DialerActivity::class.java))
                                },
                            ),
                        ) { nav.popBackStack() }
                    }
                    composable("pending_drafts") { PendingDraftsScreen { nav.popBackStack() } }
                    composable("sms_activity") { CarrierSmsActivityScreen { nav.popBackStack() } }
                    composable("saved") {
                        SavedConversationsScreen(
                            onOpen = { nav.navigate("saved/$it") },
                            onImport = { nav.navigate("import/-1") },
                            onBack = { nav.popBackStack() },
                        )
                    }
                    composable("saved/{id}") { e ->
                        val cid = e.arguments?.getString("id")?.toLongOrNull() ?: -1L
                        SavedConversationScreen(
                            cid,
                            onUpdate = { nav.navigate("import/$cid") },
                            onBack = { nav.popBackStack() },
                            onSchedule = { nav.navigate("scheduled/$cid") },
                        )
                    }
                    composable("scheduled") {
                        ScheduledTextsScreen(null, onSaved = { nav.navigate("saved") }, onBack = { nav.popBackStack() })
                    }
                    composable("scheduled/{id}") { e ->
                        val cid = e.arguments?.getString("id")?.toLongOrNull()
                        ScheduledTextsScreen(cid, onSaved = { nav.navigate("saved") }, onBack = { nav.popBackStack() })
                    }
                    composable("import/{target}") { e ->
                        val target = e.arguments?.getString("target")?.toLongOrNull()?.takeIf { it >= 0 }
                        ImportWizardScreen(
                            targetConversation = target,
                            onOpenConversation = { nav.navigate("saved/$it") { popUpTo("saved") } },
                            onBack = { nav.popBackStack() },
                        )
                    }
                    composable("blocking") { BlockingScreen { nav.popBackStack() } }
                    composable("bot/{kind}") { e ->
                        when (e.arguments?.getString("kind")) {
                            "fleet" -> com.victorious.sms.ui.screens.FleetBotScreen { nav.popBackStack() }
                            else -> com.victorious.sms.ui.screens.SummarizerBotScreen { nav.popBackStack() }
                        }
                    }
                    composable("sms_trigger") { com.victorious.sms.ui.screens.SmsTriggerScreen { nav.popBackStack() } }
                    composable("assistant") { com.victorious.sms.ui.screens.AssistantSettingsScreen { nav.popBackStack() } }
                    composable("twilio") { com.victorious.sms.ui.screens.TwilioScreen { nav.popBackStack() } }
                    composable("lookup") { com.victorious.sms.ui.screens.LookupSettingsScreen { nav.popBackStack() } }
                    composable("spam") { SpamFolderScreen { nav.popBackStack() } }
                    composable("paired_phones") { PairedPhonesScreen { nav.popBackStack() } }
                    composable("connected_apps") { ConnectedAppsScreen { nav.popBackStack() } }
                    composable("rooms") {
                        RoomListScreen(
                            onOpen = { acc, id -> nav.navigate("room/" + Uri.encode(acc) + "/" + Uri.encode(id)) },
                            onOpenSms = { tid, addr -> nav.navigate("sms/$tid/" + Uri.encode(addr)) },
                            onNewSms = { nav.navigate("sms_compose") },
                            onOpenBot = { kind -> nav.navigate("bot/$kind") },
                            onOpenMail = { acc, key -> nav.navigate("mail_thread/" + Uri.encode(acc) + "/" + Uri.encode(key)) },
                            onCompose = { nav.navigate("mail_compose/-/new/-") },
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
                    composable("sms_compose") {
                        SmsThreadScreen(-1L, "") { nav.popBackStack() }
                    }
                    composable("sip") { com.victorious.sms.ui.screens.SipSettingsScreen { nav.popBackStack() } }
                    composable("mail_accounts") { com.victorious.sms.ui.screens.MailAccountsScreen { nav.popBackStack() } }
                    composable("mail_outbox") { com.victorious.sms.ui.screens.MailOutboxScreen { nav.popBackStack() } }
                    composable("mail_thread/{acc}/{key}") { e ->
                        val acc = Uri.decode(e.arguments?.getString("acc").orEmpty()); val key = Uri.decode(e.arguments?.getString("key").orEmpty())
                        com.victorious.sms.ui.screens.MailThreadScreen(acc, key, onReply = { mode, ref -> nav.navigate("mail_compose/" + Uri.encode(acc) + "/$mode/" + Uri.encode(ref)) }) { nav.popBackStack() }
                    }
                    composable("mail_compose/{acc}/{mode}/{ref}") { e ->
                        val acc = Uri.decode(e.arguments?.getString("acc").orEmpty()).takeIf { it != "-" }.orEmpty()
                        com.victorious.sms.ui.screens.MailComposeScreen(acc, e.arguments?.getString("mode").orEmpty(), Uri.decode(e.arguments?.getString("ref").orEmpty()).takeIf { it != "-" }.orEmpty()) { nav.popBackStack() }
                    }
                    composable("help") { HelpScreen { nav.popBackStack() } }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun handleSharedFile(i: Intent?): Boolean {
        if (i?.action != Intent.ACTION_SEND) return false
        val uri = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (uri == null) return false
        PendingShare.uri = uri
        return true
    }

    companion object {
        const val EXTRA_COMPOSE_TO = "compose_to"
        const val EXTRA_COMPOSE_BODY = "compose_body"
    }
}
