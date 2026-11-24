# VictorSMS changelog — Victorious Framework

## Unreleased

### Added
- `comms-core` module: conversation archive schema v1 with FTS4 search, importers (CSV, JSON, SMS Backup XML, WhatsApp-style text, pasted text, ZIP),
  incremental import with duplicate/overlap/conflict handling and rollback, budgeted context retrieval, encrypted backup, selective deletion, and the
  automation gate and idempotent outbox. 58 JVM tests. **Not yet used by the app** (no screen, no Android adapter).
- CI runs `:comms-core:test`.

### Not done (see `docs/ARCHITECTURE.md` §24)
- Import and saved-conversation screens, "Update Conversation" button, Android `SqlDb` adapter, the bound-service connector for droidcommand-AI,
  provider capability model, scheduled messages, voice messages, interactive AI calls. HTML and TextNow-specific import are unsupported.
