package com.victorious.comms

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantAccessTest {
    private var now = Fx.BASE
    private val a: Archive = newArchive(clock = { now }).first
    private val ops = ArchiveOps(a)
    private val me = "ai.droidcommand.app"

    private fun conv(name: String, key: String = name): Long = a.createConversation("textnow", "5550100100", "textnow|$key", name, false)
    private fun say(c: Long, body: String, out: Boolean = false, ts: Long = now) =
        a.recordLive(c, if (out) Direction.OUT else Direction.IN, if (out) "self:me" else "p:sam", if (out) "Me" else "Sam", body, ts)
    private fun uid(c: Long) = a.conversation(c)!!.uid
    private fun share(c: Long, on: Boolean = true) = assertTrue(a.setAssistantAccess(c, on))

    private fun obj(vararg p: Pair<String, Any>) = buildJsonObject {
        p.forEach { (k, v) -> if (v is Number) put(k, v) else put(k, v.toString()) }
    }
    private fun ok(op: String, vararg p: Pair<String, Any>): JsonObject {
        val r = ops.call(me, op, obj(*p))
        check(r is ArchiveReply.Ok) { "expected Ok for $op but got $r" }
        return r.result
    }
    private fun err(op: String, vararg p: Pair<String, Any>): ArchiveReply.Error {
        val r = ops.call(me, op, obj(*p))
        check(r is ArchiveReply.Error) { "expected an error for $op but got $r" }
        return r
    }
    private fun JsonObject.msgs() = this["messages"]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    @Test fun nothingIsSharedByDefaultSoEveryOpSeesAnEmptyArchive() {
        val c = conv("Sam"); say(c, "secret dinner plans")
        assertEquals(0, ok(ArchiveWire.LIST)["count"]!!.jsonPrimitive.content.toInt())
        assertEquals(0, ok(ArchiveWire.CAPABILITIES)["shared_conversations"]!!.jsonPrimitive.content.toInt())
        assertEquals("not_found", err(ArchiveWire.READ, "conversation" to uid(c)).code)
        assertEquals(0, ok(ArchiveWire.SEARCH, "query" to "secret dinner").msgs().size)
        assertEquals("not_found", err(ArchiveWire.CONTEXT, "conversation" to uid(c), "query" to "dinner").code)
    }

    @Test fun aConversationThatIsMissingAndOneThatIsNotSharedAnswerIdentically() {
        val c = conv("Sam"); say(c, "hello")
        val unshared = err(ArchiveWire.READ, "conversation" to uid(c))
        val missing = err(ArchiveWire.READ, "conversation" to "0123456789abcdef0123456789abcdef")
        assertEquals("existence must not be probeable", unshared, missing)
    }

    @Test fun onlySharedConversationsAreListedAndSharingCanBeTakenBackAtOnce() {
        val shared = conv("Sam"); val hidden = conv("Alex")
        say(shared, "hi"); say(hidden, "private")
        share(shared)
        val listed = ok(ArchiveWire.LIST)["conversations"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(uid(shared)), listed.map { it.s("id") })
        assertEquals("Sam", listed[0].s("title"))
        assertFalse("no phone number or account is exposed", listed[0].keys.any { it == "account" || it == "phone" })
        share(shared, false)
        assertEquals(0, ok(ArchiveWire.LIST)["count"]!!.jsonPrimitive.content.toInt())
        assertEquals("not_found", err(ArchiveWire.READ, "conversation" to uid(shared)).code)
    }

    @Test fun readReturnsOldestFirstWithTheStoredFieldsAndFiltersByDirection() {
        val c = conv("Sam"); share(c)
        say(c, "one", ts = now); say(c, "two", out = true, ts = now + 1000); say(c, "three", ts = now + 2000)
        val all = ok(ArchiveWire.READ, "conversation" to uid(c))
        assertEquals(listOf("one", "two", "three"), all.msgs().map { it.s("body") })
        assertEquals(listOf("in", "out", "in"), all.msgs().map { it.s("direction") })
        assertEquals("live", all.msgs()[0].s("origin"))
        assertEquals(listOf("two"), ok(ArchiveWire.READ, "conversation" to uid(c), "direction" to "out").msgs().map { it.s("body") })
        assertEquals(listOf("one", "three"), ok(ArchiveWire.READ, "conversation" to uid(c), "direction" to "in").msgs().map { it.s("body") })
        assertFalse(all["has_more"]!!.jsonPrimitive.boolean)
        assertEquals(JsonNull, all["next_before_ts"])
    }

    @Test fun pagingNeverSkipsOrRepeatsEvenWhenManyMessagesShareOneTimestamp() {
        val c = conv("Sam"); share(c)
        val all = (0 until 40).map { i -> say(c, "m$i", out = i % 2 == 0, ts = now + (i / 10) * 1000L) }
        for (limit in listOf(7, 10, 15, 40)) {
            val seen = ArrayList<Long>()
            var before: Long? = null
            var guard = 0
            do {
                val page = if (before == null) ok(ArchiveWire.READ, "conversation" to uid(c), "limit" to limit)
                else ok(ArchiveWire.READ, "conversation" to uid(c), "limit" to limit, "before_ts" to before)
                seen.addAll(page.msgs().map { it["id"]!!.jsonPrimitive.content.toLong() })
                val more = page["has_more"]!!.jsonPrimitive.boolean
                before = if (more) page["next_before_ts"]!!.jsonPrimitive.content.toLong() else null
                assertTrue("paging must terminate", ++guard < 50)
            } while (before != null)
            assertEquals("limit=$limit: no gaps", all.toSet(), seen.toSet())
            assertEquals("limit=$limit: no repeats", seen.size, seen.toSet().size)
        }
    }

    @Test fun longBodiesAreClippedAndFlaggedAndTheAnswerHasAHardSizeCap() {
        val small = ArchiveOps(a, ArchiveLimits(maxBodyChars = 50, maxResultChars = 300))
        val c = conv("Sam"); share(c)
        say(c, "x".repeat(500), ts = now)
        (1..30).forEach { say(c, "y".repeat(40), ts = now + it * 1000L) }
        val r = small.call(me, ArchiveWire.READ, obj("conversation" to uid(c), "limit" to 200)) as ArchiveReply.Ok
        val msgs = r.result.msgs()
        assertTrue("bounded by the character cap, not just the row limit", msgs.size < 30)
        assertTrue(r.result["has_more"]!!.jsonPrimitive.boolean)
        assertTrue("the cap counts the serialized messages, not just their text", r.result["messages"].toString().length <= 300)
        val clipped = ok(ArchiveWire.READ, "conversation" to uid(c))
        assertEquals(31, clipped.msgs().size)
        val one = ArchiveOps(a, ArchiveLimits(maxBodyChars = 50)).call(me, ArchiveWire.READ, obj("conversation" to uid(c), "limit" to 200, "before_ts" to now + 1000)) as ArchiveReply.Ok
        assertEquals(50, one.result.msgs().single().s("body").length)
        assertEquals("true", one.result.msgs().single().s("truncated"))
    }

    @Test fun withTheDefaultLimitsEveryAnswerStaysUnderTheAssistantBrokersCeiling() {
        val c = conv("Sam"); share(c)
        repeat(100) { say(c, "needle " + "word ".repeat(600), out = it % 2 == 0, ts = now + it * 1000L) }
        val read = ok(ArchiveWire.READ, "conversation" to uid(c), "limit" to 200)
        val search = ok(ArchiveWire.SEARCH, "query" to "needle", "limit" to 200)
        for ((name, r) in listOf("read" to read, "search" to search)) {
            assertTrue("$name answer is ${r.toString().length} chars", r.toString().length < 50_000)
            assertTrue("$name still returns something useful", r.msgs().size >= 10)
        }
        assertTrue(read["has_more"]!!.jsonPrimitive.boolean)
        assertTrue(search["truncated"]!!.jsonPrimitive.boolean)
    }

    @Test fun searchOnlyReachesSharedConversationsAndSaysWhichMatchModeAnswered() {
        val sharedConv = conv("Sam"); val hidden = conv("Alex")
        say(sharedConv, "are you coming to dinner on Friday"); say(hidden, "dinner on Friday is a surprise party for Sam")
        share(sharedConv)
        val r = ok(ArchiveWire.SEARCH, "query" to "dinner Friday")
        assertEquals(1, r.msgs().size)
        assertEquals(uid(sharedConv), r.msgs()[0].s("conversation"))
        assertEquals("all", r.s("match"))
        assertFalse(r.toString().contains("surprise"))
        val fuzzy = ok(ArchiveWire.SEARCH, "query" to "what about dinner tomorrow brunch")
        assertEquals("any", fuzzy.s("match"))
        assertEquals(1, fuzzy.msgs().size)
        assertEquals("not_found", err(ArchiveWire.SEARCH, "query" to "dinner", "conversation" to uid(hidden)).code)
        assertEquals(1, ok(ArchiveWire.SEARCH, "query" to "dinner", "conversation" to uid(sharedConv)).msgs().size)
    }

    @Test fun contextHonoursTheSharingSwitchTheBudgetAndCarriesOnlyApprovedFacts() {
        val c = conv("Sam"); share(c)
        say(c, "are we still on for the museum tomorrow?", ts = now)
        say(c, "yes, 10am", out = true, ts = now + 1000)
        a.addFact(c, "Sam is vegetarian", approved = true)
        a.addFact(c, "unapproved guess", approved = false)
        a.setSummary(c, now, "Planning a museum visit.")
        val r = ok(ArchiveWire.CONTEXT, "conversation" to uid(c), "query" to "museum")
        assertEquals(listOf("Sam is vegetarian"), r["facts"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("Planning a museum visit.", r.s("summary"))
        assertEquals(listOf(false, true), r.msgs().map { it["from_owner"]!!.jsonPrimitive.boolean })
        val tiny = ok(ArchiveWire.CONTEXT, "conversation" to uid(c), "query" to "museum", "max_chars" to 200)
        assertTrue(tiny["chars"]!!.jsonPrimitive.content.toInt() <= 200)
        share(c, false)
        assertEquals("not_found", err(ArchiveWire.CONTEXT, "conversation" to uid(c), "query" to "museum").code)
    }

    @Test fun malformedOrUnknownParametersAreRefusedNeverIgnored() {
        val c = conv("Sam"); share(c)
        val u = uid(c)
        listOf(
            ArchiveWire.READ to arrayOf("conversation" to u, "limit" to 0),
            ArchiveWire.READ to arrayOf("conversation" to u, "limit" to 201),
            ArchiveWire.READ to arrayOf("conversation" to u, "limit" to "many"),
            ArchiveWire.READ to arrayOf("conversation" to u, "direction" to "sideways"),
            ArchiveWire.READ to arrayOf("conversation" to u, "before_ts" to -5),
            ArchiveWire.READ to arrayOf("conversation" to u, "delete" to "yes"),
            ArchiveWire.LIST to arrayOf("limit" to 5000),
            ArchiveWire.SEARCH to arrayOf("query" to "   "),
            ArchiveWire.SEARCH to arrayOf("query" to "x".repeat(201)),
            ArchiveWire.CONTEXT to arrayOf("conversation" to u, "query" to "ok", "max_chars" to 5),
            ArchiveWire.CAPABILITIES to arrayOf("anything" to "at all"),
        ).forEach { (op, args) -> assertEquals("$op ${args.toList()}", "invalid_parameters", err(op, *args).code) }
        assertEquals("unsupported_operation", err("comms.send_everything").code)
    }

    @Test fun theAccessLogRecordsThatAnAppAskedButNeverWhatItGot() {
        val c = conv("Sam"); share(c)
        say(c, "my password is hunter2")
        ok(ArchiveWire.READ, "conversation" to uid(c))
        ok(ArchiveWire.SEARCH, "query" to "password")
        err(ArchiveWire.READ, "conversation" to "ffffffffffffffffffffffffffffffff")
        ok(ArchiveWire.CAPABILITIES)
        val log = a.recentAccess()
        assertEquals("capabilities is not logged", 3, log.size)
        assertEquals(listOf("not_found", "ok", "ok"), log.map { it["outcome"] })
        assertTrue(log.all { it["caller"] == me })
        assertEquals("1", log.last()["returned"])
        assertFalse("never the content", log.joinToString { it.values.joinToString() }.contains("hunter2"))
    }

    @Test fun theAccessLogKeepsOnlyTheNewestRowsAndGoesWhenTheConversationGoes() {
        val c = conv("Sam"); share(c); say(c, "hi")
        repeat(520) { ok(ArchiveWire.READ, "conversation" to uid(c)) }
        assertEquals(500, a.recentAccess(1000).size)
        a.deleteConversation(c)
        assertEquals("no trace of a deleted conversation's uid remains", 0, a.recentAccess(1000).count { it["conversation"].orEmpty().isNotEmpty() })
    }

    @Test fun anExistingVersionOneArchiveUpgradesWithNothingShared() {
        val db = JdbcSqlDb()
        Schema.v1().forEach(db::exec)
        db.exec("PRAGMA user_version = 1")
        db.insert(
            "INSERT INTO conversations(uid,title,provider,account,match_key,is_group,created,updated) VALUES(?,?,?,?,?,?,?,?)",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Old friend", "textnow", "", "k", 0L, 1L, 1L,
        )
        val upgraded = Archive(db)
        assertEquals("2", db.rows("PRAGMA user_version")[0]["user_version"])
        assertFalse("an old conversation must not become visible by upgrading", upgraded.conversations().single().assistantAccess)
        assertEquals("not_found", (ArchiveOps(upgraded).call(me, ArchiveWire.READ, obj("conversation" to "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")) as ArchiveReply.Error).code)
        Archive(db) // reopening is a no-op
        assertEquals("2", db.rows("PRAGMA user_version")[0]["user_version"])
    }

    @Test fun theOwnersSharingChoicesSurviveABackupButAnOlderBackupRestoresWithNothingShared() {
        val c = conv("Sam"); say(c, "hello"); share(c)
        val blob = a.exportEncrypted("pw".toCharArray())
        val fresh = newArchive().first
        fresh.restoreEncrypted(blob, "pw".toCharArray())
        assertTrue(fresh.conversations().single().assistantAccess)

        val v1 = buildJsonObject {
            put("schema", 1)
            put(
                "conversations",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("id", "7"); put("uid", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"); put("title", "Older"); put("provider", "sms")
                            put("account", ""); put("match_key", "k2"); put("is_group", "0"); put("created", "1"); put("updated", "1")
                        },
                    ),
                ),
            )
        }
        fresh.restoreFrom(v1)
        assertFalse(fresh.conversations().single().assistantAccess)
        val tooNew = buildJsonObject { put("schema", 3) }
        try { fresh.restoreFrom(tooNew); error("a newer backup must be refused") } catch (_: IllegalArgumentException) { }
    }

    @Test fun togglingAccessDoesNotReorderTheOwnersConversationList() {
        val first = conv("A"); val second = conv("B")
        now += 1000; say(first, "x")
        now += 1000; say(second, "y")
        val before = a.conversations().map { it.id }
        now += 5000 // a stray write of `updated` would now put `first` on top
        share(first); share(first, false)
        assertEquals(before, a.conversations().map { it.id })
        assertNotEquals(0, before.size)
    }
}
