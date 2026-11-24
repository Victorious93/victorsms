package com.victorious.sms.archive

import android.content.Context
import com.victorious.comms.Author
import com.victorious.comms.Direction
import com.victorious.comms.LiveIngest
import com.victorious.sms.fleet.SmsRemoteTriggerSettings
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Victorious Framework: the one place live texts enter the conversation archive. **Off by default**; the owner turns it on
 * under Saved conversations. When on, a received or sent text is also written to the archive.
 *
 * Rules that hold on every path:
 *  - Never delays or fails delivery. Work runs on a background thread, every failure is swallowed, and nothing here
 *    throws to the SMS receiver or sender.
 *  - Recording is not automation: nothing is queued, no rule is created, and a new conversation is not shared with any
 *    assistant app (that stays the owner's per-conversation switch).
 *  - A text that contains the owner's fleet code word is never recorded, and if the code word cannot be read the text is
 *    not recorded either (fail closed): the archive must never hold the secret an assistant app could later read.
 *  - Bot-drafted text is recorded with a bot [Author], so it can never teach the owner's writing style.
 *  - Spam the blocklist stops is never recorded (the receiver returns before this is called).
 */
object LiveArchive {
    private const val PREFS = "archive_settings"
    private const val KEY_RECORD_LIVE = "record_live_sms"

    /** Serial, so writes keep their order. Tests swap in a direct executor. */
    @Volatile internal var executor: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "archive-live").apply { isDaemon = true } }

    fun isEnabled(context: Context): Boolean =
        runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_RECORD_LIVE, false) }.getOrDefault(false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_RECORD_LIVE, on).apply()
    }

    /** [done] runs when the work is finished or skipped; the receiver uses it to release its `goAsync()` hold. */
    fun recordIncoming(
        context: Context, address: String, body: String, ts: Long = System.currentTimeMillis(),
        secrets: () -> Set<String> = { setOf(SmsRemoteTriggerSettings(context).codeWord) },
        done: (() -> Unit)? = null,
    ) = record(context, address, body, ts, Direction.IN, Author.OWNER, secrets, done)

    /** [author] is mandatory on purpose: a send path must say whose words these are. */
    fun recordOutgoing(
        context: Context, address: String, body: String, ts: Long, author: Author,
        secrets: () -> Set<String> = { setOf(SmsRemoteTriggerSettings(context).codeWord) },
    ) = record(context, address, body, ts, Direction.OUT, author, secrets, null)

    private fun record(
        context: Context, address: String, body: String, ts: Long, direction: Direction, author: Author,
        secrets: () -> Set<String>, done: (() -> Unit)?,
    ) {
        if (!isEnabled(context)) { done?.invoke(); return }
        val app = context.applicationContext
        try {
            executor.execute {
                try {
                    val words = secrets()
                    if (words.none { it.isNotEmpty() && body.contains(it) }) {
                        LiveIngest(ArchiveRuntime.get(app).archive).record(address, body, ts, direction, author)
                    }
                } catch (_: Throwable) {
                    // Delivery and sending must never depend on the archive.
                } finally {
                    done?.invoke()
                }
            }
        } catch (_: Throwable) {
            done?.invoke()
        }
    }
}
