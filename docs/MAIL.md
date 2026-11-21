# VictorSMS mail: design, decisions, status

## Stack decision (Thunderbird for Android / K-9)

The brief asked for an evaluation of Thunderbird for Android at a pinned revision. **That evaluation was not done**: in this
session `github.com/thunderbird/thunderbird-android` returned HTTP 403 through the egress proxy, so no revision could be
read, pinned or built. What is recorded instead:

* [Likely, not verified here] K-9's mail modules are part of the app's own Gradle build rather than published library
  artifacts, and pulling them in would also bring its account/UI model. Shortest remaining check: clone the repo from a
  machine with access, pin a SHA, and see whether `:mail:protocols:imap` / `:smtp` build standalone against this project's
  AGP/Kotlin versions.
* Chosen meanwhile: **Angus Mail 2.0.5** (Eclipse's Jakarta Mail implementation; EPL-2.0 / GPLv2+CE with Classpath
  Exception) plus **jsoup 1.24.1** (MIT) for HTML sanitising. Both are maintained and published on Maven Central.
* **Not verified**: Angus Mail on a real Android runtime. It compiles and passes 47 JVM tests (below). Jakarta Activation's
  mailcap lookup and `java.beans`/AWT-free paths must be exercised in an APK on a device or emulator; R8 rules may be needed.

## Architecture

`:mailcore` is a pure-JVM module (no Android types) so every rule below is tested without a phone:

| Piece | Behaviour |
|---|---|
| `ConversationKey(transport, account, sourceThread)` | Identity of a conversation. Display names are never part of it. |
| `UnifiedInbox` | Merge, filter (All/SMS/Email/Matrix/Alias, account, unread, search), reply route. A reply route is derived only from the row's key; a failed send is `Failed(route)` and the API offers no alternative route. |
| `ImapClient` | TLS only (implicit or required STARTTLS), TLS 1.2/1.3, server identity checked, UID/UIDVALIDITY incremental sync, read flag, folder list, Message-ID search. |
| `SmtpSender` | Authenticated submission only. Outcomes: `Accepted`, `NotSent` (nothing transmitted), `Rejected` (server refused), `Uncertain` (connection broke after hand-over). |
| `MimeBuilder` | To/Cc/Bcc, Reply-To, In-Reply-To/References, attachments, reply, reply-all (own address and duplicates removed), forward; header-injection guard. |
| `Outbox` | Message-ID assigned at enqueue; state persisted **before** the network call; only `QUEUED` is ever transmitted; a record left in `SENDING` after a restart becomes `UNCERTAIN`; `UNCERTAIN` is never retried by the app. Owner actions: check Sent folder, resend as a *new* message, discard. |
| `DraftStore` | Drafts survive restart. |
| `SafeHtml` | Scripts, handlers, frames, forms, styles, `javascript:`/`data:` links removed; remote images blocked by default (https only if the owner opts in). Rendered as text in the app, not a WebView. |
| `DraftHandoff` | Phone side of the VictorLinux draft flow (see below). |

App glue (`MailRuntime`, `MailScreens.kt`, inbox changes) keeps passwords in Keystore-backed `EncryptedSharedPreferences`,
the message cache/outbox/drafts under `noBackupFilesDir` (excluded from backup; protected by Android file-based encryption,
**not** separately encrypted). Email-only use needs no default-SMS role, Matrix login, VictorSuite or PC; SMS-only use needs no
email login (mail polling does nothing with zero accounts).

## OAuth

`Auth.OAuth` selects XOAUTH2 and needs an access token from an OAuth registration owned by this project. **No provider
registration exists**, so Gmail/Microsoft sign-in is not available; use an app password with manual server setup. Shortest
remaining check: register an app with the provider, add a `TokenProvider` that returns a fresh access token into `SecretStore`.

## Desktop draft hand-off

`victor-fleet message draft` -> coordinator op `message.draft` -> DroidCommand worker -> VictorSMS bridge op `message.draft`
(paired app, SEND group, on-device confirmation class) -> notification -> `DraftReviewActivity`. Only its Send button calls
`DraftHandoff.ownerSend`. Guarantees tested in `DraftHandoffTest`: receipt sends nothing; Send is valid once; decline sends
nothing; a re-delivered `draftId` is `AlreadySeen` and never re-shown as sendable; bodies are dropped from storage once
resolved; drafts expire after one hour. The DroidCommand worker side (calling the bridge op and relaying
`progress`/`complete`/`fail`) is **not implemented**.

## MMS

`MmsWapPushReceiver.onReceive` is still an empty placeholder and was left unchanged: the inbox exposes **no** MMS rows or
actions, so nothing claims MMS support. Real handling = parse the `m-notification-ind` PDU, call
`SmsManager.downloadMultimediaMessage`, insert into the `Telephony.Mms` provider. It needs a SIM and the carrier's MMSC to
verify; it is not started.

## Known gaps

* Unread counts exist for email only; SMS and Matrix rows show 0, so the Unread filter hides them.
* Sync runs while the inbox screen is open (60 s); there is no background sync worker or IMAP IDLE push.
* STARTTLS paths are implemented but only implicit-TLS was exercised (GreenMail has no STARTTLS).
* Upgrade from an existing inbox and loss of the default-SMS role were not exercised on a device.
