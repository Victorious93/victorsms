package com.victorious.comms

/**
 * Explicit, ordered schema migrations for the conversation archive (Victorious Framework).
 *
 * Design notes that the importer depends on:
 *  - `messages.fp` is a *content* fingerprint (direction + normalised text). It deliberately excludes the
 *    timestamp, because two exports of the same conversation disagree about precision and sometimes zone.
 *    Time proximity is a separate matching step, so identical texts ("ok") legitimately repeat.
 *  - `messages.origin` is `import` or `live`. Imported history can never be a trigger for automation.
 *  - `message_sources` records every import that contained a message, so rollback removes only messages whose
 *    sole provenance is the rolled-back import. A message missing from a newer export is never deleted.
 *  - `messages.author` separates the owner's own words from AI-generated ones, so style analysis can exclude
 *    unapproved bot text.
 */
object Schema {
    const val VERSION = 4

    fun migrate(db: SqlDb) {
        val current = db.one("PRAGMA user_version")?.get("user_version")?.toIntOrNull() ?: 0
        require(current <= VERSION) { "Archive schema $current is newer than this build supports ($VERSION)." }
        // Each step records its own version INSIDE its transaction: if the app dies between steps, the next start resumes at the
        // next step instead of re-running CREATE TABLE on a database that already has the tables (which would fail forever).
        if (current < 1) db.tx { v1().forEach(db::exec); db.exec("PRAGMA user_version = 1") }
        if (current < 2) db.tx { v2().forEach(db::exec); db.exec("PRAGMA user_version = 2") }
        if (current < 3) db.tx { v3().forEach(db::exec); db.exec("PRAGMA user_version = 3") }
        if (current < 4) db.tx {
            db.exec("ALTER TABLE scheduled ADD COLUMN sms_address TEXT")
            db.exec("ALTER TABLE scheduled ADD COLUMN sms_subscription_id INTEGER")
            db.exec("PRAGMA user_version = 4")
        }
    }

    /**
     * v2: the assistant connector. `assistant_access` is the owner's per-conversation switch and is OFF for every
     * existing and every new conversation. `access_log` records *that* a paired app read something (never what).
     */
    fun v2(): List<String> = listOf(
        "ALTER TABLE conversations ADD COLUMN assistant_access INTEGER NOT NULL DEFAULT 0",
        """CREATE TABLE access_log(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            ts INTEGER NOT NULL,
            caller TEXT NOT NULL,
            op TEXT NOT NULL,
            conversation TEXT NOT NULL DEFAULT '',
            returned INTEGER NOT NULL DEFAULT 0,
            outcome TEXT NOT NULL)""",
    )

    /** v3: owner-scheduled messages (see [Scheduler]). Separate from `outbox`, which is bot-reply machinery. */
    fun v3(): List<String> = listOf(
        """CREATE TABLE scheduled(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            uid TEXT NOT NULL UNIQUE,
            conversation INTEGER NOT NULL,
            body TEXT NOT NULL,
            send_at INTEGER NOT NULL,
            state TEXT NOT NULL,
            detail TEXT NOT NULL DEFAULT '',
            created INTEGER NOT NULL,
            updated INTEGER NOT NULL)""",
        "CREATE INDEX scheduled_due ON scheduled(state, send_at)",
        "CREATE INDEX scheduled_conv ON scheduled(conversation, send_at)",
    )

    fun v1(): List<String> = listOf(
        """CREATE TABLE conversations(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            uid TEXT NOT NULL UNIQUE,
            title TEXT NOT NULL DEFAULT '',
            provider TEXT NOT NULL,
            account TEXT NOT NULL DEFAULT '',
            match_key TEXT NOT NULL,
            is_group INTEGER NOT NULL DEFAULT 0,
            created INTEGER NOT NULL,
            updated INTEGER NOT NULL)""",
        "CREATE INDEX conversations_match ON conversations(match_key)",
        """CREATE TABLE participants(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            conversation INTEGER NOT NULL,
            key TEXT NOT NULL,
            display TEXT NOT NULL DEFAULT '',
            phone TEXT,
            is_self INTEGER NOT NULL DEFAULT 0,
            contact_ref TEXT,
            UNIQUE(conversation, key))""",
        """CREATE TABLE imports(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            conversation INTEGER NOT NULL,
            kind TEXT NOT NULL,
            file_name TEXT NOT NULL DEFAULT '',
            file_sha256 TEXT NOT NULL,
            file_bytes INTEGER NOT NULL DEFAULT 0,
            status TEXT NOT NULL DEFAULT 'committed',
            parsed INTEGER NOT NULL DEFAULT 0,
            added INTEGER NOT NULL DEFAULT 0,
            matched INTEGER NOT NULL DEFAULT 0,
            near_dup INTEGER NOT NULL DEFAULT 0,
            conflicts INTEGER NOT NULL DEFAULT 0,
            errors INTEGER NOT NULL DEFAULT 0,
            first_ts INTEGER,
            last_ts INTEGER,
            total_after INTEGER NOT NULL DEFAULT 0,
            created_conversation INTEGER NOT NULL DEFAULT 0,
            notes TEXT NOT NULL DEFAULT '',
            created INTEGER NOT NULL)""",
        "CREATE INDEX imports_conv ON imports(conversation, id)",
        """CREATE TABLE messages(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            conversation INTEGER NOT NULL,
            sender INTEGER NOT NULL,
            direction TEXT NOT NULL CHECK(direction IN ('in','out')),
            body TEXT NOT NULL,
            ts INTEGER NOT NULL,
            ts_precision INTEGER NOT NULL DEFAULT 1000,
            provider_id TEXT,
            fp TEXT NOT NULL,
            origin TEXT NOT NULL CHECK(origin IN ('import','live')),
            author TEXT NOT NULL DEFAULT 'owner',
            attachments TEXT NOT NULL DEFAULT '[]',
            first_import INTEGER,
            created INTEGER NOT NULL)""",
        "CREATE INDEX messages_conv_ts ON messages(conversation, ts, id)",
        "CREATE INDEX messages_fp ON messages(conversation, fp)",
        "CREATE UNIQUE INDEX messages_provider ON messages(conversation, provider_id) WHERE provider_id IS NOT NULL",
        "CREATE VIRTUAL TABLE message_fts USING fts4(body, tokenize=unicode61)",
        """CREATE TABLE message_sources(
            message INTEGER NOT NULL,
            import_id INTEGER NOT NULL,
            PRIMARY KEY(message, import_id))""",
        """CREATE TABLE message_revisions(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            message INTEGER NOT NULL,
            body TEXT NOT NULL,
            import_id INTEGER,
            replaced_at INTEGER NOT NULL)""",
        """CREATE TABLE conflicts(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            conversation INTEGER NOT NULL,
            import_id INTEGER NOT NULL,
            message INTEGER NOT NULL,
            kind TEXT NOT NULL,
            incoming_body TEXT NOT NULL,
            incoming_ts INTEGER NOT NULL,
            status TEXT NOT NULL DEFAULT 'open',
            created INTEGER NOT NULL)""",
        """CREATE TABLE facts(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            conversation INTEGER NOT NULL,
            text TEXT NOT NULL,
            approved INTEGER NOT NULL DEFAULT 0,
            source TEXT NOT NULL DEFAULT 'user',
            created INTEGER NOT NULL)""",
        """CREATE TABLE summaries(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            conversation INTEGER NOT NULL,
            up_to_ts INTEGER NOT NULL,
            text TEXT NOT NULL,
            created INTEGER NOT NULL)""",
        """CREATE TABLE auto_rules(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            conversation INTEGER NOT NULL,
            bot_ref TEXT NOT NULL,
            mode TEXT NOT NULL,
            enabled INTEGER NOT NULL DEFAULT 0,
            hours_start INTEGER,
            hours_end INTEGER,
            max_per_hour INTEGER NOT NULL DEFAULT 4,
            min_gap_ms INTEGER NOT NULL DEFAULT 30000,
            max_consecutive INTEGER NOT NULL DEFAULT 3,
            disclose INTEGER NOT NULL DEFAULT 1,
            expires INTEGER,
            UNIQUE(conversation, bot_ref))""",
        // Only one bot may be active per conversation: competing bots cannot both answer.
        "CREATE UNIQUE INDEX auto_rules_one_active ON auto_rules(conversation) WHERE enabled = 1",
        """CREATE TABLE outbox(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            uid TEXT NOT NULL UNIQUE,
            idem_key TEXT NOT NULL UNIQUE,
            conversation INTEGER NOT NULL,
            bot_ref TEXT NOT NULL,
            trigger_message INTEGER,
            body TEXT NOT NULL DEFAULT '',
            provider TEXT NOT NULL DEFAULT '',
            state TEXT NOT NULL,
            detail TEXT NOT NULL DEFAULT '',
            created INTEGER NOT NULL,
            updated INTEGER NOT NULL)""",
        "CREATE INDEX outbox_conv ON outbox(conversation, created)",
        "CREATE TABLE settings(key TEXT PRIMARY KEY, value TEXT NOT NULL)",
    )
}
