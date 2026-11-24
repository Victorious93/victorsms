package com.victorious.comms

/** One result row; every column is a string and NULL columns are absent. */
typealias Row = Map<String, String>

/**
 * Minimal SQL surface (Victorious Framework). The Android app adapts `SQLiteDatabase` to this; JVM tests adapt
 * real SQLite through JDBC, so schema, migrations and every store here run unchanged in both places.
 * Arguments may be String, Long, Int, Boolean or null. Never concatenate user text into SQL.
 */
interface SqlDb {
    fun rows(sql: String, vararg args: Any?): List<Row>

    /** INSERT; returns the new row id. */
    fun insert(sql: String, vararg args: Any?): Long

    /** UPDATE/DELETE; returns the number of rows changed. */
    fun update(sql: String, vararg args: Any?): Int

    /** DDL or statements without arguments. */
    fun exec(sql: String)

    /** Runs [work] atomically; a nested call joins the outer transaction; an exception rolls back. */
    fun <T> tx(work: () -> T): T
}

fun SqlDb.one(sql: String, vararg args: Any?): Row? = rows(sql, *args).firstOrNull()

fun SqlDb.count(sql: String, vararg args: Any?): Long = one(sql, *args)?.get("n")?.toLongOrNull() ?: 0L

fun Row.long(key: String): Long = this[key]?.toLongOrNull() ?: 0L

fun Row.str(key: String): String = this[key].orEmpty()
