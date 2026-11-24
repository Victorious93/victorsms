package com.victorious.sms.bridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Victorious Framework: the reply shapes droidcommand-AI validates. The same literal frames are pinned in that repo's
 * `VictorSmsFramesContractTest`; change one side and the other test fails, which is the point. These are copies, not a
 * shared file, so a change on one side must be copied by hand.
 */
class DcaFramesTest {
    private fun sameJson(expected: String, actual: JSONObject) =
        assertTrue("expected $expected\nbut was $actual", sameTree(expected, actual))

    private val listRequest =
        """{"protocol_major":1,"request_id":"dca-list-1","target_node_id":"victorsms","operation":"comms.conversations.list","timeout_ms":10000,"parameters":{}}"""

    @Test fun archiveOpsGetNoInjectedRequestIdButOtherOpsKeepTheirs() {
        val archive = DcaFrames.parseRequest(listRequest)
        assertFalse(archive.legacy.getJSONObject("params").has("requestId"))
        assertEquals("comms.conversations.list", archive.legacy.getString("op"))
        assertEquals("dca-list-1", archive.legacy.getString("id"))
        val send = DcaFrames.parseRequest("""{"protocol_major":1,"request_id":"dca-9","target_node_id":"victorsms","operation":"sms.send","parameters":{"to":"+15550100"}}""")
        assertEquals("dca-9", send.legacy.getJSONObject("params").getString("requestId"))
    }

    @Test fun aSuccessReplyCarriesTheEchoedNodeIdAndTheResult() {
        val req = DcaFrames.parseRequest(listRequest)
        val internal = JSONObject().put("id", "dca-list-1").put("ok", true).put("result", JSONObject("""{"conversations":[],"count":0,"truncated":false}"""))
        sameJson(
            """{"protocol_major":1,"request_id":"dca-list-1","node_id":"victorsms","ok":true,"result":{"conversations":[],"count":0,"truncated":false}}""",
            DcaFrames.response(req, internal),
        )
    }

    @Test fun anErrorReplyHasCategoryMessageAndCodeBecauseTheOtherSideRequiresTheFirstTwo() {
        val req = DcaFrames.parseRequest(listRequest.replace("dca-list-1", "dca-x"))
        val internal = JSONObject().put("id", "dca-x").put("ok", false).put("error", "permission_denied")
        sameJson(
            """{"protocol_major":1,"request_id":"dca-x","node_id":"victorsms","ok":false,"error":{"category":"permission_denied","message":"This app has not been granted that permission in VictorSMS.","code":"permission_denied"}}""",
            DcaFrames.response(req, internal),
        )
        val specific = DcaFrames.response(req, internal.put("error", "invalid_parameters").put("message", "Parameter 'limit' is not valid."))
        assertEquals("Parameter 'limit' is not valid.", specific.getJSONObject("error").getString("message"))
    }

    @Test fun aMissingOrMalformedTargetIsNotEchoedNeverInvented() {
        for (bad in listOf("", "has space", "-lead", "a".repeat(129), "semi;colon")) {
            val req = DcaFrames.parseRequest("""{"protocol_major":1,"request_id":"r","target_node_id":"$bad","operation":"comms.capabilities","parameters":{}}""")
            assertNull("'$bad'", req.targetNodeId)
            assertFalse(DcaFrames.response(req, JSONObject().put("id", "r").put("ok", true).put("result", JSONObject())).has("node_id"))
        }
        assertNull(DcaFrames.parseRequest("not json").targetNodeId)
    }

    @Test fun anUnreadableRequestStillProducesAFrameTheCallerCanCorrelateOrRefuse() {
        val req = DcaFrames.parseRequest(null)
        assertEquals(-1, req.legacy.getInt("protocol_major"))
        assertEquals("", req.legacy.getString("op"))
    }

    @Test fun everyCodeTheBridgeCanReturnHasAPlainMessage() {
        listOf(
            "external_control_disabled", "not_paired", "permission_denied", "unsupported_operation", "unsupported_protocol",
            "invalid_frame", "invalid_parameters", "not_found", "not_implemented", "internal_error",
        ).forEach { assertTrue(it, DcaFrames.describe(it).length > 10) }
    }
}
