# VictorSMS changelog — Victorious Framework

## Unreleased

### Added (scheduled messages, provider capabilities — `comms-core` only)
- `Scheduler`: owner-written messages that go out later. Archive schema **v3** adds a `scheduled` table. A message that comes due while the phone could not send (more than 15 min late)
  becomes MISSED and is never sent late; a send in flight at a restart becomes UNKNOWN and is never resent; sending is refused if the conversation's provider cannot send. It stores and
  decides; it does not send.
- `Providers`: one table of what each provider label can do (`sms`, `matrix`, `email` can send; `whatsapp`, `textnow`, `paste`, `zip` are history only; unknown labels get nothing).
- 12 new `comms-core` tests (99 total, all pass). **Not wired into the app: no alarm, no sender, no screen. Never run on a device.** `:app` could not be compiled in the environment
  that wrote this (no Android SDK), so no Android code was touched.

### Fixed (voip pin, capabilities)
- voip edition: `LinphoneSipEngine` now really pins the server certificate (SHA-256 of the certificate or its public key, fails closed, self-signed accepted only when pinned) and compiles against Linphone 5.3.x; the earlier draft did not compile and its pin was a no-op.
- `capabilities.get` / `sms.capabilities.get` list the `comms.*` archive ops.

### Fixed (review, Nov 24)
- Mail outbox: claiming an item is one atomic step, so two flushes at once (inbox poll + Send, or a double Send on a PC draft) or a double tap on
  *Send again anyway* sends once and no longer throws `IllegalStateException` (which crashed the screen). Compose/Outbox buttons never crash on a
  missing account password.
- Archive migrations: each step records its `user_version` inside its own transaction; a crash between steps no longer leaves an archive that fails
  to open forever.
- Bridge: an exception from an op, or a request reaching a service whose worker was already shut down, is an error reply instead of an app crash.
- ZIP import: media entries are skipped by name without being buffered in memory, so a large export with media cannot exhaust the heap or trip the
  256 MB expansion limit before the chat text is read.
- CI: informational (non-blocking) job that assembles the voip edition, which CI never built before.

### Added
- `comms-core` module: conversation archive schema v1 with FTS4 search, importers (CSV, JSON, SMS Backup XML, WhatsApp-style text, pasted text, ZIP),
  incremental import with duplicate/overlap/conflict handling and rollback, budgeted context retrieval, encrypted backup, selective deletion, and the
  automation gate and idempotent outbox. 58 JVM tests. **Not yet used by the app** (no screen, no Android adapter).
- CI runs `:comms-core:test`.

### Added (Android glue)
- `AndroidSqlDb`, `ArchiveRuntime`, `ImportSession`; Import Center screens (saved conversations, import wizard with preview, Update, Undo, conflicts, delete);
  share target for conversation files (shared text-only now opens a new SMS). Settings entry *Saved conversations (import)*.
- `Archive.search(matchAll = true)` for a search box. 17 new app tests (Robolectric). Never run on a device.

### Added (assistant connector)
- Read-only archive access for droidcommand-AI over the paired bridge: new op group **Archive read**, five `comms.*` ops, and a per-conversation switch (*Let my assistant
  read this conversation*, off by default; archive schema v2 keeps every existing conversation off). A request log shows which app asked, never what it read.
- **Fix:** replies on the droidcommand-AI wire now include `node_id` and a structured error (`category`, `message`, `code`). droidcommand-AI's validator rejected the old shape.
- 15 `comms-core` tests and 17 app tests; reply frames pinned in both repositories. **Never run against the real droidcommand-AI or on a device.**

### Fixed (status reads)
- `sms.status`, `node.status.get` and `sms.capabilities.get` are now answered (they used to fall through to `not_implemented` or be refused), so droidcommand-AI's
  *Check paired apps* no longer reports a healthy VictorSMS as having a problem. The READ permission label no longer promises thread reading, which is not built.

### Added (live recording)
- Opt-in (off by default) recording of received and sent texts into the archive: Saved conversations → *Also save new texts here as they arrive and are sent*. Same
  conversation as an SMS Backup import, so a later import merges. Never delays delivery; skips the fleet code word and blocked spam; bot-drafted sends are recorded as
  bot-authored so they cannot teach the owner's style. The SMS Backup importer's provider guess is now `sms` (old `sms-backup` conversations are still found).
- 10 `comms-core` tests and 8 app tests. **The receiver and sender hooks have never run on a device.**

### Not done (see `docs/ARCHITECTURE.md` §24)
- Sending/drafting through the connector, encrypted-backup screen,
  provider capability model, scheduled messages, voice messages, interactive AI calls. HTML and TextNow-specific import are unsupported.
