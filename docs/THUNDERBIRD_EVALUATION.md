# Thunderbird for Android / K-9 Mail — evaluation for VictorSMS mail

Pinned source: https://github.com/thunderbird/thunderbird-android at commit
`b69e4b2d57c68377d320caebc5b8742ec6e0edb4` (main, committed 2026-11-24 13:54 ET). Latest beta tag at that time: `THUNDERBIRD_9_0b3`
(`2e7ec151f4b885e447889d7966dfdf7d9ad67e5a`). This is a reading of the source (shallow clone, 159 MB). Nothing from it was built or
imported into VictorSMS.

## What VictorSMS has today
`mail-core` is a small JVM module on Eclipse Angus Mail (Jakarta Mail): IMAP incremental INBOX sync (UIDVALIDITY/UID), authenticated
SMTP, an outbox, optional certificate pinning, STARTTLS that is required rather than opportunistic (`mail.<proto>.starttls.required=true`,
covered by `StartTlsTest`), and a jsoup allow-list sanitiser for HTML.

## What Thunderbird offers
| Area | Thunderbird / K-9 (pinned SHA) | Fit for VictorSMS |
|---|---|---|
| Licence | Apache-2.0 (LICENSE, NOTICE) | Compatible with VictorSMS (Apache-2.0, GPL-3.0 editions). We would have to keep NOTICE. |
| IMAP | `mail/protocols/imap`: its own IMAP stack with IDLE (`RealImapFolderIdler`, `IdleRefreshManager`), CONDSTORE/QRESYNC capability handling, compression (jzlib), modified UTF-7 folder names | Better than Angus for push (IDLE) and big mailboxes. It is a JVM library module (`ThunderbirdPlugins.Library.jvm`), so it is technically consumable. |
| SMTP | `mail/protocols/smtp`: XOAUTH2, CRAM-MD5, STARTTLS, a settings validator | Equivalent to what we have, plus OAuth. |
| POP3 | `mail/protocols/pop3` | Not needed. |
| Sync engine | `backend/imap` (`ImapSync`, `ImapBackend`, 22 files) on `backend/api` | Tied to K-9's `BackendStorage` and folder model. Adopting it means adopting K-9's storage. |
| JMAP | `backend/jmap` | Interesting for Fastmail later. Not now. |
| Internal coupling | `mail/protocols/imap` depends on `mail/common`, `core/common`, `feature/mail/folder/api` and `legacy/logging` | Not on Maven Central, so it would have to be vendored or included as a git submodule build. That is several internal modules, not one jar. |

## Recommendation
1. **Don't fork or merge the app.** Thunderbird is a full client with its own UI, storage and account model. VictorSMS's unified inbox and
   bridges don't fit it, and keeping a fork in step with upstream's weekly churn would cost more than it saves.
2. **Keep Angus Mail for now.** It covers sync, send, STARTTLS-required and pinning, and the tests pass on the JVM.
3. **If push mail becomes a must-have:** vendor only `mail/common` and `mail/protocols/imap` (plus the small `core/common` /
   `legacy/logging` pieces they need) at a pinned SHA under `third_party/thunderbird/`, keep NOTICE, and put them behind our `ImapClient`
   interface so that `MailSyncJob` and the UI don't change. Before doing that, check whether Angus's `IMAPFolder.idle()` in a foreground
   service is good enough, because it is much cheaper.
4. Re-evaluate at a newer SHA before any vendoring. This document is pinned to the commit above.

## Shortest checks still open
- Build `mail/protocols/imap` alone from the pinned SHA (`./gradlew :mail:protocols:imap:test`) to measure the real dependency closure. Not run.
- Try Angus IDLE against a real provider. Not run.
