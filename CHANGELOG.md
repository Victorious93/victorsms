# VictorSMS changelog — Victorious Framework

## Unreleased

### Added
- `comms-core` module: conversation archive schema v1 with FTS4 search, importers (CSV, JSON, SMS Backup XML, WhatsApp-style text, pasted text, ZIP),
  incremental import with duplicate/overlap/conflict handling and rollback, budgeted context retrieval, encrypted backup, selective deletion, and the
  automation gate and idempotent outbox. 58 JVM tests. **Not yet used by the app** (no screen, no Android adapter).
- CI runs `:comms-core:test`.

### Added (Android glue)
- `AndroidSqlDb`, `ArchiveRuntime`, `ImportSession`; Import Center screens (saved conversations, import wizard with preview, Update, Undo, conflicts, delete);
  share target for conversation files (shared text-only now opens a new SMS). Settings entry *Saved conversations (import)*.
- `Archive.search(matchAll = true)` for a search box. 17 new app tests (Robolectric). Never run on a device.

### Not done (see `docs/ARCHITECTURE.md` §24)
- Recording live SMS into the archive, the connector for droidcommand-AI, encrypted-backup screen,
  provider capability model, scheduled messages, voice messages, interactive AI calls. HTML and TextNow-specific import are unsupported.
