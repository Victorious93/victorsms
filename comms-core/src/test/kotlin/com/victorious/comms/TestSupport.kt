package com.victorious.comms

import java.io.ByteArrayOutputStream
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Real SQLite through JDBC, mirroring what the Android adapter does with SQLiteDatabase. */
class JdbcSqlDb(url: String = "jdbc:sqlite::memory:") : SqlDb {
    val c: Connection = DriverManager.getConnection(url).also { it.autoCommit = true }
    private var depth = 0

    private fun bind(ps: java.sql.PreparedStatement, a: Array<out Any?>) {
        a.forEachIndexed { i, v ->
            when (v) {
                null -> ps.setNull(i + 1, java.sql.Types.NULL)
                is Long -> ps.setLong(i + 1, v)
                is Int -> ps.setLong(i + 1, v.toLong())
                is Boolean -> ps.setLong(i + 1, if (v) 1 else 0)
                else -> ps.setString(i + 1, v.toString())
            }
        }
    }

    override fun rows(sql: String, vararg args: Any?): List<Row> = c.prepareStatement(sql).use { ps ->
        bind(ps, args)
        ps.executeQuery().use { rs ->
            val meta = rs.metaData
            val out = ArrayList<Row>()
            while (rs.next()) {
                val m = LinkedHashMap<String, String>()
                for (i in 1..meta.columnCount) rs.getString(i)?.let { m[meta.getColumnLabel(i)] = it }
                out.add(m)
            }
            out
        }
    }

    override fun insert(sql: String, vararg args: Any?): Long = c.prepareStatement(sql).use { ps ->
        bind(ps, args)
        ps.executeUpdate()
        c.createStatement().use { s -> s.executeQuery("SELECT last_insert_rowid()").use { it.next(); it.getLong(1) } }
    }

    override fun update(sql: String, vararg args: Any?): Int = c.prepareStatement(sql).use { ps -> bind(ps, args); ps.executeUpdate() }

    override fun exec(sql: String) { c.createStatement().use { it.execute(sql) } }

    override fun <T> tx(work: () -> T): T {
        if (depth > 0) return work()
        depth++
        c.autoCommit = false
        try {
            val r = work()
            c.commit()
            return r
        } catch (e: Throwable) {
            c.rollback()
            throw e
        } finally {
            depth--
            c.autoCommit = true
        }
    }
}

/** One synthetic message. All people and numbers here are invented. */
data class M(val out: Boolean, val text: String, val tsMs: Long)

object Fx {
    const val BASE: Long = 1_767_614_400_000L // 2026-01-05T12:00:00Z

    private val outPhrases = listOf(
        "yeah lol", "omw", "k sounds good 👍", "ok", "hahaha no way", "can't tonight, working late", "lmk", "ok", "thx!!", "u around?",
        "that's wild", "brb", "café at 5? ☕", "ok", "nah", "see u there",
    )
    private val inPhrases = listOf(
        "Hey, are you coming to dinner on Friday?", "Please let me know when you land.", "I sent the document over, take a look.",
        "Did you get my last message?", "Running ten minutes late, sorry!", "Can we move it to Thursday?", "Thanks so much for yesterday.",
        "Line one\nLine two\nLine three",
    )

    /** Deterministic realistic back-and-forth: n messages, one every [gapSec] seconds starting at [start]. */
    fun conversation(n: Int, start: Long = BASE, gapSec: Long = 97): List<M> = (0 until n).map { i ->
        val out = i % 3 != 0
        val text = if (out) outPhrases[(i * 7) % outPhrases.size] else inPhrases[(i * 5) % inPhrases.size]
        M(out, text, start + i * gapSec * 1000)
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("\n", "&#10;")

    fun smsXml(msgs: List<M>, address: String = "+1 (555) 010-0101"): ImportInput {
        val sb = StringBuilder("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n<smses count=\"${msgs.size}\">\n")
        for (m in msgs) sb.append("  <sms protocol=\"0\" address=\"${esc(address)}\" date=\"${m.tsMs}\" type=\"${if (m.out) 2 else 1}\" body=\"${esc(m.text)}\" read=\"1\" />\n")
        sb.append("</smses>\n")
        return ImportInput("sms-backup.xml", sb.toString().toByteArray())
    }

    fun csv(msgs: List<M>, name: String = "conversation.csv", zone: ZoneId = ZoneOffset.UTC, secondsPrecision: Boolean = true, withDirection: Boolean = true): ImportInput {
        val fmt = DateTimeFormatter.ofPattern(if (secondsPrecision) "yyyy-MM-dd HH:mm:ss" else "yyyy-MM-dd HH:mm").withZone(zone)
        val sb = StringBuilder(if (withDirection) "Date,Direction,Body,Phone\n" else "Date,Sender,Body\n")
        for (m in msgs) {
            val body = "\"" + m.text.replace("\"", "\"\"") + "\""
            val date = fmt.format(Instant.ofEpochMilli(m.tsMs))
            if (withDirection) sb.append("$date,${if (m.out) "sent" else "received"},$body,+1 555 010 0101\n")
            else sb.append("$date,${if (m.out) "Don" else "Sam"},$body\n")
        }
        return ImportInput(name, sb.toString().toByteArray())
    }

    fun whatsApp(msgs: List<M>, zone: ZoneId = ZoneOffset.UTC, self: String = "Don", other: String = "Sam", name: String = "WhatsApp Chat with Sam.txt"): ImportInput {
        val fmt = DateTimeFormatter.ofPattern("M/d/yy, h:mm a - ", Locale.US).withZone(zone)
        val sb = StringBuilder("1/5/26, 11:59 AM - Messages and calls are end-to-end encrypted. No one outside of this chat can read them.\n")
        for (m in msgs) sb.append(fmt.format(Instant.ofEpochMilli(m.tsMs))).append(if (m.out) self else other).append(": ").append(m.text).append("\n")
        return ImportInput(name, sb.toString().toByteArray())
    }

    fun zipOf(vararg entries: Pair<String, ByteArray>): ImportInput {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return ImportInput("export.zip", bos.toByteArray())
    }
}

fun newArchive(originals: OriginalStore = MemoryOriginalStore(), clock: () -> Long = { Fx.BASE + 86_400_000L }): Pair<Archive, JdbcSqlDb> {
    val db = JdbcSqlDb()
    return Archive(db, originals, clock) to db
}
