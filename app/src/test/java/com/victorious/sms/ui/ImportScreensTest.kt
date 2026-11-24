package com.victorious.sms.ui

import android.net.Uri
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ApplicationProvider
import com.victorious.comms.ArchiveOps
import com.victorious.comms.ArchiveReply
import com.victorious.comms.ArchiveWire
import com.victorious.comms.ImportInput
import com.victorious.comms.ImportRequest
import com.victorious.comms.ParseOptions
import com.victorious.sms.archive.ArchiveRuntime
import com.victorious.sms.archive.PendingShare
import com.victorious.sms.ui.screens.ImportWizardScreen
import com.victorious.sms.ui.screens.SavedConversationScreen
import com.victorious.sms.ui.screens.SavedConversationsScreen
import org.junit.After
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.ZoneOffset

/** Victorious Framework: the Import Center rendered by Robolectric on the JVM. NOT a device or emulator run. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ImportScreensTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val dir = File(System.getProperty("java.io.tmpdir"), "vsms-ui-${System.nanoTime()}").also { it.mkdirs() }

    @Before fun fresh() {
        ctx.getDatabasePath(ArchiveRuntime.DB_NAME).delete()
        ArchiveRuntime.resetForTests()
        PendingShare.take()
    }


    /** Waits for text to appear; on timeout fails WITH the visible UI tree so the reason is readable. */
    private fun awaitText(text: String, ms: Long = 15_000) {
        try {
            rule.waitUntil(ms) { runCatching { rule.onNodeWithText(text, substring = true).assertExists() }.isSuccess }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("'$text' never appeared. UI was:\n" + rule.onRoot().printToString(maxDepth = 12).lines().filter { it.contains("Text =") || it.contains("Role =") || it.contains("Disabled") || it.contains("ToggleableState") }.joinToString("\n"), e)
        }
    }

    private fun awaitEnabled(text: String, ms: Long = 15_000) {
        try {
            rule.waitUntil(ms) { runCatching { rule.onNodeWithText(text).assertIsEnabled() }.isSuccess }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("'$text' never became enabled. UI was:\n" + rule.onRoot().printToString(maxDepth = 12).lines().filter { it.contains("Text =") || it.contains("Disabled") }.joinToString("\n"), e)
        }
    }

    @After fun cleanUp() { dir.deleteRecursively() }

    private fun file(name: String, vararg rows: String): Uri {
        val f = File(dir, name)
        f.writeText("Date,Sender,Body\n" + rows.joinToString("\n") + "\n")
        return Uri.fromFile(f)
    }

    @Test fun anEmptyArchiveExplainsItselfAndOffersTheImport() {
        var imported = false
        rule.setContent { SavedConversationsScreen(onOpen = {}, onImport = { imported = true }, onBack = {}) }
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("Nothing saved yet", substring = true).assertExists() }.isSuccess }
        rule.onNodeWithText("Import a conversation").performClick()
        assertTrue(imported)
    }

    @Test fun theWizardRefusesToSaveUntilTheOwnerSaysWhichSenderIsThemThenSavesOnConfirm() {
        PendingShare.uri = file("chat.csv", "2026-01-05 12:00:00,Sam,hello there", "2026-01-05 12:01:00,Don,hi Sam")
        rule.setContent { ImportWizardScreen(targetConversation = null, onOpenConversation = {}, onBack = {}) }
        awaitText("Which of these is you?")
        rule.onNodeWithText("Save conversation").assertIsNotEnabled()
        assertEquals("a preview wrote nothing", 0, ArchiveRuntime.get(ctx).archive.conversations().size)
        rule.onNodeWithText("Don").performScrollTo().performClick()
        awaitEnabled("Save conversation")
        rule.onNodeWithText("Save conversation").performScrollTo().performClick()
        awaitText("Saved a new conversation")
        assertEquals(1, ArchiveRuntime.get(ctx).archive.conversations().size)
        assertEquals(2L, ArchiveRuntime.get(ctx).archive.conversations()[0].messageCount)
    }

    @Test fun updatingASavedConversationReportsWhatWasAddedAndDeletesNothing() {
        val h = ArchiveRuntime.get(ctx)
        val first = ImportInput("first.csv", "Date,Sender,Body\n2026-01-05 12:00:00,Sam,one\n2026-01-05 12:01:00,Don,two\n2026-01-05 12:02:00,Sam,three\n".toByteArray())
        val conv = h.engine.commit(ImportRequest(first, providerLabel = "TextNow", selfSenders = setOf("Don"), options = ParseOptions(zone = ZoneOffset.systemDefault()))).groups[0].conversation
        PendingShare.uri = file("newer.csv", "2026-01-05 12:00:00,Sam,one", "2026-01-05 12:01:00,Don,two", "2026-01-05 12:02:00,Sam,three", "2026-01-05 12:03:00,Don,four", "2026-01-05 12:04:00,Sam,five")
        rule.setContent { ImportWizardScreen(targetConversation = conv, onOpenConversation = {}, onBack = {}) }
        awaitEnabled("Add the new messages")
        rule.onNodeWithText("Add the new messages").performScrollTo().performClick()
        awaitText("2 added, 3 already saved")
        assertEquals(listOf("one", "two", "three", "four", "five"), h.archive.messages(conv, 20).map { it.body })
        assertEquals("still one conversation", 1, h.archive.conversations().size)
    }

    private fun seeded(): Long {
        val first = ImportInput("first.csv", "Date,Sender,Body\n2026-01-05 12:00:00,Sam,one\n2026-01-05 12:01:00,Don,two\n".toByteArray())
        return ArchiveRuntime.get(ctx).engine.commit(ImportRequest(first, providerLabel = "TextNow", selfSenders = setOf("Don"), options = ParseOptions(zone = ZoneOffset.systemDefault()))).groups[0].conversation
    }

    @Test fun theAssistantSwitchIsOffUntilTheOwnerTurnsItOnAndTheArchiveOpsFollowIt() {
        val h = ArchiveRuntime.get(ctx)
        val conv = seeded()
        val ops = ArchiveOps(h.archive)
        fun listed() = (ops.call("ai.droidcommand.app", ArchiveWire.LIST, buildJsonObject { }) as ArchiveReply.Ok).result["count"].toString()
        rule.setContent { SavedConversationScreen(id = conv, onUpdate = {}, onBack = {}) }
        awaitText("Off. No app can see")
        assertFalse(h.archive.conversation(conv)!!.assistantAccess)
        assertEquals("0", listed())

        rule.onNodeWithText("Let my assistant", substring = true).performScrollTo().performClick()
        awaitText("On. An app can read this")
        assertTrue(h.archive.conversation(conv)!!.assistantAccess)
        assertEquals("1", listed())

        rule.onNodeWithText("Let my assistant", substring = true).performScrollTo().performClick()
        awaitText("Off. No app can see")
        assertFalse(h.archive.conversation(conv)!!.assistantAccess)
        assertEquals("0", listed())
    }

    @Test fun theOwnerSeesWhichAppAskedAboutAConversationButNotWhatItRead() {
        val h = ArchiveRuntime.get(ctx)
        val conv = seeded()
        h.archive.setAssistantAccess(conv, true)
        val uid = h.archive.conversation(conv)!!.uid
        ArchiveOps(h.archive).call("ai.droidcommand.app", ArchiveWire.READ, buildJsonObject { put("conversation", uid) })
        rule.setContent { SavedConversationScreen(id = conv, onUpdate = {}, onBack = {}) }
        awaitText("ai.droidcommand.app")
        awaitText("2 items")
    }

    @Test fun theListMarksConversationsSharedWithTheAssistant() {
        val h = ArchiveRuntime.get(ctx)
        val conv = seeded()
        h.archive.setAssistantAccess(conv, true)
        rule.setContent { SavedConversationsScreen(onOpen = {}, onImport = {}, onBack = {}) }
        awaitText("shared with assistant")
    }
}
