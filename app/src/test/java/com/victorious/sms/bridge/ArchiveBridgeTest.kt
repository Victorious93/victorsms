package com.victorious.sms.bridge

import androidx.test.core.app.ApplicationProvider
import com.victorious.comms.ImportInput
import com.victorious.comms.ImportRequest
import com.victorious.comms.ParseOptions
import com.victorious.sms.archive.ArchiveRuntime
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset

/**
 * Victorious Framework: a droidcommand-AI request frame in, a droidcommand-AI reply frame out, through the same
 * translation and archive code the bound service uses, on Android's SQLite under Robolectric. The service itself,
 * authorisation and the Messenger transport are NOT exercised here (authorisation is `PairingPolicyTest`).
 * Not a device or emulator run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArchiveBridgeTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val caller = "ai.droidcommand.app"

    @Before fun fresh() {
        ctx.getDatabasePath(ArchiveRuntime.DB_NAME).delete()
        ArchiveRuntime.resetForTests()
    }

    private fun wire(op: String, id: String, params: String = "{}", target: String = "victorsms") =
        """{"protocol_major":1,"request_id":"$id","target_node_id":"$target","operation":"$op","timeout_ms":10000,"parameters":$params}"""

    private fun ask(request: String): JSONObject {
        val req = DcaFrames.parseRequest(request)
        val internal = ArchiveBridge.reply(
            ArchiveRuntime.get(ctx).archive, req.legacy.getString("id"), caller, req.legacy.getString("op"), req.legacy.getJSONObject("params"),
        )
        return DcaFrames.response(req, internal)
    }

    private fun same(expected: String, actual: JSONObject) = assertTrue("expected $expected\nbut was $actual", sameTree(expected, actual))

    private fun seed(): Pair<Long, String> {
        val csv = "Date,Sender,Body\n2026-01-05 12:00:00,Sam,are you coming to dinner Friday\n2026-01-05 12:01:00,Don,yes 7pm\n"
        val h = ArchiveRuntime.get(ctx)
        val conv = h.engine.commit(ImportRequest(ImportInput("c.csv", csv.toByteArray()), providerLabel = "TextNow", selfSenders = setOf("Don"), options = ParseOptions(zone = ZoneOffset.UTC))).groups[0].conversation
        return conv to h.archive.conversation(conv)!!.uid
    }

    @Test fun capabilitiesMatchTheFramePinnedOnTheOtherSide() = same(
        """{"protocol_major":1,"request_id":"dca-cap-1","node_id":"victorsms","ok":true,"result":{"version":1,"ops":["comms.capabilities","comms.conversations.list","comms.conversation.read","comms.messages.search","comms.context.retrieve"],"access":"per_conversation","shared_conversations":0,"max_limit":200,"max_body_chars":4000}}""",
        ask(wire("comms.capabilities", "dca-cap-1")),
    )

    @Test fun anEmptyListMatchesTheFramePinnedOnTheOtherSide() = same(
        """{"protocol_major":1,"request_id":"dca-list-1","node_id":"victorsms","ok":true,"result":{"conversations":[],"count":0,"truncated":false}}""",
        ask(wire("comms.conversations.list", "dca-list-1")),
    )

    @Test fun anUnsharedAndAMissingConversationGiveTheSameFrameWhichMatchesThePinnedOne() {
        val (_, uid) = seed()
        val golden = """{"protocol_major":1,"request_id":"dca-read-1","node_id":"victorsms","ok":false,"error":{"category":"not_found","message":"No such conversation is shared with the assistant.","code":"not_found"}}"""
        same(golden, ask(wire("comms.conversation.read", "dca-read-1", """{"conversation":"$uid"}""")))
        same(golden, ask(wire("comms.conversation.read", "dca-read-1", """{"conversation":"00000000000000000000000000000000"}""")))
    }

    @Test fun onceSharedTheFourReadsAnswerWithRealDataAndTheOwnersTextStaysData() {
        val (conv, uid) = seed()
        ArchiveRuntime.get(ctx).archive.setAssistantAccess(conv, true)
        val list = ask(wire("comms.conversations.list", "r1")).getJSONObject("result")
        assertEquals(uid, list.getJSONArray("conversations").getJSONObject(0).getString("id"))
        val read = ask(wire("comms.conversation.read", "r2", """{"conversation":"$uid","limit":10}""")).getJSONObject("result")
        assertEquals(2, read.getJSONArray("messages").length())
        assertEquals("yes 7pm", read.getJSONArray("messages").getJSONObject(1).getString("body"))
        assertEquals("out", read.getJSONArray("messages").getJSONObject(1).getString("direction"))
        val search = ask(wire("comms.messages.search", "r3", """{"query":"dinner"}""")).getJSONObject("result")
        assertEquals(1, search.getJSONArray("messages").length())
        val ctxResult = ask(wire("comms.context.retrieve", "r4", """{"conversation":"$uid","query":"dinner"}""")).getJSONObject("result")
        assertEquals(2, ctxResult.getJSONArray("messages").length())
        assertTrue(ctxResult.has("owner_examples"))
    }

    @Test fun unknownOrForeignParametersAreRefusedWithAReadableFrame() {
        val reply = ask(wire("comms.conversations.list", "dca-bad", """{"limit":5,"delete":"all"}"""))
        assertFalse(reply.getBoolean("ok"))
        assertEquals("invalid_parameters", reply.getJSONObject("error").getString("category"))
        assertTrue(reply.getJSONObject("error").getString("message").contains("delete"))
        // The id the other side normally injects for other ops is not accepted on archive ops either.
        assertEquals("invalid_parameters", ask(wire("comms.conversations.list", "dca-bad2", """{"requestId":"x"}""")).getJSONObject("error").getString("category"))
    }

    @Test fun aRequestWithoutATargetGetsAReplyWithoutANodeIdSoTheOtherSideRefusesIt() {
        val req = """{"protocol_major":1,"request_id":"dca-n","operation":"comms.capabilities","parameters":{}}"""
        assertFalse(ask(req).has("node_id"))
    }
}
