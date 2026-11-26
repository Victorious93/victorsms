package com.victorious.comms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression (review Nov 24): a crash between migration steps must not leave the archive unopenable. */
class SchemaMigrationTest {
    private fun version(db: SqlDb) = db.one("PRAGMA user_version")?.get("user_version")?.toIntOrNull() ?: -1

    /** Fails on the first statement of the v2 step, like a process killed mid-migration. */
    private class CrashInV2(val inner: JdbcSqlDb) : SqlDb by inner {
        var armed = true
        override fun exec(sql: String) {
            if (armed && sql.startsWith("ALTER TABLE conversations ADD COLUMN assistant_access")) throw IllegalStateException("killed")
            inner.exec(sql)
        }
    }

    @Test fun crashDuringV2KeepsV1RecordedAndTheNextStartFinishes() {
        val db = CrashInV2(JdbcSqlDb())
        val r = runCatching { Schema.migrate(db) }
        assertTrue(r.isFailure)
        assertEquals("v1 committed with its own version", 1, version(db))
        db.armed = false
        Schema.migrate(db)                      // used to fail: v1 re-ran CREATE TABLE conversations on user_version 0
        assertEquals(Schema.VERSION, version(db))
        assertEquals(0L, db.count("SELECT COUNT(*) AS n FROM conversations WHERE assistant_access=1"))
    }

    @Test fun migratingTwiceIsANoOp() {
        val db = JdbcSqlDb()
        Schema.migrate(db); Schema.migrate(db)
        assertEquals(Schema.VERSION, version(db))
    }

    @Test fun v3ScheduledTextsSurviveTheSmsRouteMigration() {
        val db = JdbcSqlDb()
        (Schema.v1() + Schema.v2() + Schema.v3()).forEach(db::exec)
        db.exec("PRAGMA user_version = 3")
        db.exec("""INSERT INTO scheduled(uid,conversation,body,send_at,state,created,updated)
            VALUES('queued',1,'See you soon',2000,'PENDING',1000,1000)""")
        val scheduled = Scheduler(Archive(db)).get("queued")!!
        assertEquals(Schema.VERSION, version(db))
        assertEquals(SchedState.PENDING, scheduled.state)
        assertEquals("See you soon", scheduled.body)
        assertNull(scheduled.smsAddress)
        assertNull(scheduled.smsSubscriptionId)
    }
}
