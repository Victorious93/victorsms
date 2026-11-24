package com.victorious.sms.archive

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import com.victorious.comms.Row
import com.victorious.comms.SqlDb

/**
 * Victorious Framework: [SqlDb] over Android's SQLite, so `comms-core` runs unchanged in the app.
 *
 * - Reads use `rawQuery`, which can only bind strings; SQLite's column affinity makes `id=?` and `LIMIT ?` work with
 *   numeric text, and `Boolean` is bound as 1/0. A `null` read argument is refused (rawQuery cannot bind NULL).
 * - Writes use compiled statements with typed binds (Long, Int, Boolean, Double, ByteArray, String, null).
 * - Transactions follow the *calling thread's* own state ([SQLiteDatabase.inTransaction]), so nesting is correct
 *   even when several coroutines use the archive; an exception rolls back.
 * - The database file's `user_version` belongs to `Schema.migrate`; do not open it through a `SQLiteOpenHelper`.
 */
class AndroidSqlDb(private val db: SQLiteDatabase) : SqlDb {
    override fun rows(sql: String, vararg args: Any?): List<Row> {
        val strings = Array(args.size) { i ->
            when (val v = args[i]) {
                null -> throw IllegalArgumentException("rows() cannot bind NULL (argument ${i + 1}); use a literal IS NULL.")
                is Boolean -> if (v) "1" else "0"
                else -> v.toString()
            }
        }
        db.rawQuery(sql, strings).use { c ->
            val names = c.columnNames
            val out = ArrayList<Row>()
            while (c.moveToNext()) {
                val m = LinkedHashMap<String, String>()
                for (i in names.indices) if (!c.isNull(i)) m[names[i]] = c.getString(i)
                out.add(m)
            }
            return out
        }
    }

    override fun insert(sql: String, vararg args: Any?): Long = db.compileStatement(sql).use { st ->
        bind(st, args)
        st.executeInsert()
    }

    override fun update(sql: String, vararg args: Any?): Int = db.compileStatement(sql).use { st ->
        bind(st, args)
        st.executeUpdateDelete()
    }

    override fun exec(sql: String) = db.execSQL(sql)

    override fun <T> tx(work: () -> T): T {
        if (db.inTransaction()) return work()
        db.beginTransactionNonExclusive()
        try {
            val r = work()
            db.setTransactionSuccessful()
            return r
        } finally {
            db.endTransaction()
        }
    }

    private fun bind(st: SQLiteStatement, args: Array<out Any?>) {
        args.forEachIndexed { i, v ->
            val n = i + 1
            when (v) {
                null -> st.bindNull(n)
                is Long -> st.bindLong(n, v)
                is Int -> st.bindLong(n, v.toLong())
                is Boolean -> st.bindLong(n, if (v) 1 else 0)
                is Double -> st.bindDouble(n, v)
                is ByteArray -> st.bindBlob(n, v)
                else -> st.bindString(n, v.toString())
            }
        }
    }
}
