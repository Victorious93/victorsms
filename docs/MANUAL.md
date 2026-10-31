# VictorSMS User Manual

> Current state: **v0.1.0 dev**. Matrix login, multiple accounts, room list, timeline + send,
> logout, Help, Settings and Connected apps are **built but not yet tested on a device**.
> Everything marked **(planned)** describes intended behavior.

## What VictorSMS is + fleet role

VictorSMS is an Android messenger (package `com.victorious.sms`). It will handle SMS/MMS on
the phone and show other networks (WhatsApp, Signal, Telegram, …) as Matrix rooms through
bridges (planned).

In the Victorious fleet, VictorSMS is an **EXTERNAL_NODE**. It is controlled by
**droidcommand-AI** (the controller). VictorSMS reports its status and delivers messages; it does
not decide or run fleet actions itself.

## Install & setup

1. Download the debug APK for your phone (`arm64-v8a` for almost all phones) from the CI artifacts of the latest build (or build it yourself with
   `./gradlew assembleDebug`).
2. Allow "Install unknown apps" for your file manager or browser, then open the APK.
3. Open VictorSMS. Today you will see the scaffold home screen.
4. (planned) Accept the prompt to make VictorSMS the default SMS app.
5. (planned) Grant the SMS, contacts and notification permissions when asked.
6. (planned) Sign in to your Matrix homeserver to see bridged networks.

## Features step-by-step

### Sign in to Matrix (built, not device-tested)
1. Open the app. On first start the sign-in screen appears.
2. Enter your homeserver (e.g. `matrix.org` or your own server), username and password.
3. Tap **Sign in**. Your session is stored encrypted on the phone; the password is not stored.
   Your homeserver must support sliding sync (recent Synapse).
   `[screenshot: sign-in]`

### Multiple accounts (built, not device-tested)
1. In the room list, tap the account name in the top bar.
2. Pick another account to switch, or **Add account** to sign in to one more.
3. **Remove / log out** signs out only the account you are viewing.
   `[screenshot: account switcher]`

### Rooms and messages (built, not device-tested)
1. The room list shows the joined rooms of the current account.
2. Tap a room to open its timeline. Tap **Load older** for history.
3. Type in the box and tap **Send**.
   `[screenshot: room list]` `[screenshot: room timeline]`

### Help (built)
1. Tap **Help** (sign-in screen or account menu) to read this manual in the app.

### Connected apps (built, not device-tested)
1. Account menu → **Settings**. Turn on **External control** only if you want fleet apps to connect.
2. Open **Connected apps**. Apps appear after they first try to connect.
3. Check the package name and cert fingerprint, then approve it and tick what it may do:
   **Read-only** (status and threads) or **Send**. Every send still asks you to confirm (planned dialog).
4. Turn the switch off to revoke, or tap **Forget**.
   `[screenshot: connected apps]`

### Approving a remote SMS (built, not device-tested)
1. When an approved fleet app with **Send** permission asks to send an SMS, you get a
   notification "… wants to send an SMS".
2. Tap it. You see the app, the recipient and the full message.
3. Tap **Approve** to send, or **Deny**. If you do nothing, the request expires (default 2 minutes).
   VictorSMS never sends one of these on its own.
   `[screenshot: approve SMS]`

### Text messaging (SMS) (built, not device-tested)
1. In the inbox, tap **Set up** on the banner. Android asks whether VictorSMS should be your
   default SMS app; accept, then allow the SMS (and notification) permissions.
2. Your SMS threads appear in the inbox with a cyan **SMS** badge. Tap one to read it.
3. Type in the box and tap **Send**. VictorSMS only sends SMS when you tap Send (or approve a
   remote request, see below).
4. Links like `sms:` / `smsto:` from other apps open a new message. "Reply with text" on incoming
   calls is supported.
5. MMS (pictures) are not downloaded yet (planned). You can use SMS without any Matrix account:
   tap **Skip: use SMS only** on the sign-in screen.
   `[screenshot: sms thread]`

### Unified inbox (built, not device-tested)
1. The inbox shows SMS threads (newest first) followed by Matrix rooms from **all** your accounts.
2. Each row has a badge: **SMS**, **Matrix**, or the bridged network (**WhatsApp**, **Telegram**,
   **Signal**, **Messenger**, **Instagram**, **Discord**, **Google Messages**).
3. Bridged networks need mautrix bridges on your homeserver; see `docs/BRIDGES.md`.
   `[screenshot: unified inbox]`

### Bots: assistant, bridge, voice (planned)
1. Open the bot room and chat. Offload to the PC happens only with your consent.
   `[screenshot: bot room]`

### Backup & restore settings (planned)
1. From droidcommand-AI's unified backup, VictorSMS exports its settings. Export is local only
   (not allowed remotely).
2. The export **never** contains your code word, sender whitelist, Matrix tokens, phone numbers or
   message bodies. If any such field is found, the export is refused.
3. On import you can preview first (dry run) and choose what happens on conflicts: skip (default),
   overwrite, or merge by id. Merge by id combines lists (for example bots) item by item using each
   item's id, keeping items that exist only on this phone.
4. VictorSMS asks you to confirm on the phone before applying. An import never turns on remote control.


### Phone-to-phone relay (built, not device-tested)
Use a second phone's SIM from this phone, over the Victorious Mesh.
1. On **both** phones: Settings → **Paired phones** → turn on **Phone relay**.
2. On the phone you want to pair with, tap **Wait for pairing** (open for 5 minutes).
3. On this phone, type the other phone's address (shown on its screen, e.g. `100.64.0.7:47320`)
   and tap **Pair**.
4. Both phones show a 6-digit code. Tap **Codes match** on each phone only if the codes are the
   same. Otherwise tap Cancel.
5. Everything is off after pairing. On the phone with the SIM you want to use, turn on what that
   peer may ask for: **send SMS**, **place calls**, and/or **mirror my incoming texts** to it.
6. To send: under the paired phone, enter a number and text and tap **Send via …**. The other
   phone shows an approval screen; it sends only after someone taps **Approve** there.
7. To call: tap **Call via**. The other phone asks for approval, then opens its dialer with the
   number. The call audio stays on that phone for now (audio bridging is planned).
8. **Revoke pairing** removes the phone completely.
9. After pairing, this phone only talks to the exact certificate it paired with. If a different
   device answers at that address, the request fails with `peer_cert_mismatch`. To pair a new or
   reset phone at the same address, revoke the old pairing first.
10. Pending approvals survive an app restart until they expire (2 minutes by default). They are
    stored encrypted on the phone.
   `[screenshot: paired phones]` `[screenshot: pairing code]`

### Blocking spam calls and texts (built, not device-tested)
1. Settings → **Blocking & spam** → **Turn on call screening** (Android asks you to confirm).
2. Add a number and choose **Block** (rejected, no ring), **Silence** (rings silently) or
   **Allow** (never filtered).
3. Add spam patterns: a number pattern such as `+1900*`, or tick **Text keyword** to filter texts
   containing a word into the Spam folder.
4. In any SMS thread, tap **Block** to block that sender.
5. Settings → **Spam folder** shows filtered texts. **Not spam** moves the text to your inbox and
   always allows that sender.
   `[screenshot: blocking]` `[screenshot: spam folder]`

### Phone app: keypad, calls, recents (built, not device-tested)
1. Settings → **Phone (keypad + recents)** → **Make VictorSMS your phone app**, then allow the
   Phone, Call log and Contacts permissions.
2. **Keypad:** type a number and tap **Call**. **Recents:** tap an entry to put it on the keypad.
3. During a call: **Mute**, **Speaker**, **Hold**, **Keypad** (for menus) and **End call**.
   Incoming calls show **Answer** / **Decline**, even on the lock screen.
4. The caller's name comes from your contacts first, then from your PC if you set a lookup URL in
   Settings → **Caller lookup**. Online lookup is off and no provider is set up.
   `[screenshot: keypad]` `[screenshot: in-call]`

### Second number: mask your real number (built; not device-tested; Twilio not tested live)
Use a real second number that **you own in your own Twilio account** so people see that number
instead of your SIM number. This is **not** caller-ID or SMS spoofing: the app refuses any
"from" number that is not in the list fetched from your Twilio account.
1. In the Twilio console, create an API key. In VictorSMS: Settings → **Twilio alias number**.
   Enter the Account SID, API key SID and secret, then tap **Save** (stored encrypted).
2. Tap **Load my numbers** and tap **Use as alias** on one or more numbers (several allowed).
3. **Default identity:** choose SIM or one of your aliases. New threads use it.
4. **In any SMS thread** the composer shows **Sending as: SIM** or **Sending as: Alias …1234**.
   Tap **Change** to set the identity for that thread only (or go back to the default).
5. **Alias texts / MMS:** with an alias selected, tap Send. A dialog shows the Twilio price first;
   nothing is sent until you tap **Confirm**. For MMS, paste a public `https://` image URL
   (Twilio fetches media from a URL; US/Canada only).
6. **Masked call:** enter your real phone under **Masked calls** and tap **Save phone**. In a thread
   using an alias, tap **Masked call** → confirm the per-minute price. Twilio rings **your**
   phone; answer and it connects the other person, who sees your alias number.
7. **Inbox:** texts to your alias numbers appear in the inbox with an **Alias …1234** badge
   (checked every 60 s). Use the **All / SIM / Alias** filter at the top.
8. **Get a new number:** search, tap **Buy…**. A dialog shows the monthly cost; nothing is bought
   unless you tap **Buy**. Nothing is ever bought automatically.
9. **SIP:** **Create SIP domain + credentials** prepares `<name>.sip.twilio.com`; SIP calling in
   the app is still not built (masked calls use the bridge instead).

**US texting rules (A2P 10DLC):** US carriers require texts sent from a Twilio 10-digit local
number to be registered (Brand + Campaign in the Twilio console, Messaging → Regulatory
Compliance). Unregistered traffic is filtered or blocked and may incur carrier fees. For personal
use, register a Sole Proprietor brand, or use a verified toll-free number instead. Honour STOP/HELP
(Twilio handles STOP opt-outs by default), text only people who expect to hear from you, and
follow local call-recording and consent laws. Costs (numbers, texts, MMS, call minutes on both
legs, registration fees) are billed by Twilio to your account.
   `[screenshot: twilio]`

### Draft replies in your style + voice notes (built, not device-tested)
Needs your PC on the mesh running an Ollama-compatible LLM (and optionally a voice server).
1. Settings → **Assistant & persona**. Enter the LLM URL (e.g. `http://100.64.0.2:11434`) and model.
2. **Import** your `persona.md`, chat exports, or a `.zip` of them.
3. In a thread, tap **Draft reply**. The draft appears in the message box. **Review and edit it;
   it is never sent automatically.**
4. **Voice notes:** enter the voice URL, then add a voice. You must declare it is **your own
   voice** or that the person gave **explicit consent** (who, how and when). No other voices can be used.
5. With a draft in the box, tap **Voice note**, then **Play**. **Share** it yourself if you want.
   `[screenshot: draft reply]` `[screenshot: voices]`

### Assistant bots (built, not device-tested)
Two bots sit at the top of the inbox.
1. **Summarizer / triage:** needs the PC LLM URL (Settings → Assistant & persona). Paste or
   type messages; it summarizes and lists action items.
2. **Fleet (droidcommand-AI):** turn on External control and approve **droidcommand-AI** in
   Settings → Connected apps. Type a command as `actionRef` and optional JSON args, e.g.
   `lock-tablet {}`. VictorSMS forwards it; **droidcommand-AI decides and may ask you to confirm**.
   If droidcommand-AI is not installed or its bridge is not live, you will see a clear error.
   `[screenshot: bots]`

### SMS remote-command trigger (built, not device-tested)
Let a text message from a trusted phone ask droidcommand-AI to do something. VictorSMS only
forwards; it never runs the command.
1. Turn on External control and approve **droidcommand-AI** in Connected apps.
2. Settings → **SMS remote-command trigger**. Turn it on (it is off by default).
3. Set a secret **code word** and add the allowed sender numbers to the whitelist.
4. An SMS must come from a whitelisted number **and** contain the code word. Then VictorSMS
   forwards it. The code word and whitelist are stored encrypted and are never exported or logged.
   `[screenshot: sms trigger]`

## Permissions and why

| Permission / role | Why | Status |
|---|---|---|
| `SEND_SMS` | Send texts you write, and remote SMS you approve | built |
| `POST_NOTIFICATIONS` | Remote SMS approval prompts | built |
| Default SMS role | Required by Android to receive and store SMS | built |
| `RECEIVE_SMS`, `READ_SMS` | Receive and show your texts | built |
| `RECEIVE_MMS`, `RECEIVE_WAP_PUSH` | Required for the default SMS role; MMS download planned | declared |
| `POST_NOTIFICATIONS` | New-message notifications | planned |
| Call-screening role | Filters calls using your blocklist | built |
| Dialer role, `CALL_PHONE` | Be your phone app and place calls | built |
| `READ_CALL_LOG` | Recents list | built |
| `READ_CONTACTS` | Caller names (first lookup step) | built |
| `INTERNET` | Matrix, mesh and phone-relay traffic | built |
| Foreground service (special use) | Keeps the phone-relay listener running while you have it on | built |

You can deny any optional permission; the related feature simply stays off.

## Fleet integration

- **Remote control is OFF by default.** You must enable it in settings and can revoke it any time.
  Every remote action is written to an audit log. (planned)
- **SMS-triggered flow (built, not device-tested):** Settings → SMS remote-command trigger
  (off by default). An incoming SMS is treated as a fleet trigger only when **both** are true:
  1. the sender is on your **sender whitelist**, **and**
  2. the message contains your **secret code word**.
  If both match, VictorSMS **forwards** `sms.remote_command.forward` to droidcommand-AI.
  **VictorSMS never executes actions itself.** The trigger details (sender, matched keyword) are
  informational only and are not used for authorization.
- **Connected apps (built):** each fleet app is identified by its package name and signing
  certificate, checked by Android, not by what the app claims. Unknown apps get "not paired".
  Nothing is allowed until you approve the app and choose its permissions, and all of it sits
  behind the External control switch.
- **Status (planned):** droidcommand-AI can ask VictorSMS for its status (`node.status.get`). The
  answer says whether SMS is ready to deliver and whether the app is healthy. It never contains
  message bodies, phone numbers, your code word or your whitelist.
- **Fleet theme (built, not device-tested):** VictorSMS follows the colours you pick in
  VictorLaunch → Settings → **Fleet theme**. It reads them when the app starts and again whenever
  VictorLaunch changes them, so the app recolours live. VictorSMS only **reads** the theme; it never
  changes it. It only trusts the theme if it really comes from VictorLaunch (`com.victorious.launch`
  signed with its published key). If VictorLaunch is missing, not trusted, or says no, VictorSMS
  keeps its built-in neon theme. A colour VictorLaunch leaves out or sends broken keeps the neon
  default for that one colour.
  - **Note:** VictorLaunch only answers apps it has pinned. VictorSMS (`com.victorious.sms`) is not
    on its theme-consumer list yet, so until it is added there you will see the neon theme.

## Troubleshooting/FAQ

- **Pair says "Could not connect".** Phone relay must be on on the other phone, both phones must
  be on the mesh, and port 47320 must be reachable.
- **Pairing refused: not_pairing.** Tap **Wait for pairing** on the other phone first.
- **peer_cert_mismatch.** The device at that address is not the phone you paired with (or it was
  reset). Check the address; if the phone was reset, revoke the pairing and pair again.
- **Install says "app not compatible".** Use the APK for your phone's CPU: almost all modern phones
  use `arm64-v8a`; emulators use `x86_64`.

- **No SMS threads in the inbox.** Tap **Set up** and make VictorSMS the default SMS app, then
  allow the SMS permission.
- **A WhatsApp/Telegram room shows as "Matrix".** Detection relies on the bridges' default user
  name prefixes; see `docs/BRIDGES.md`.

- **Signed in but no rooms appear.** Your homeserver may not support sliding sync. Use a recent
  Synapse or a server with native sliding sync.
- **A fleet app says "not_paired".** Turn on External control, then approve the app under
  Settings → Connected apps.

- **The app only shows a blank/home screen.** Expected: this is the scaffold build.
- **APK won't install.** Uninstall any older build signed with a different key, then retry.
- **Building locally runs out of memory.** Use
  `./gradlew assembleDebug --no-daemon -Dorg.gradle.jvmargs=-Xmx1536m`.
- **Will a random SMS control my phone?** No. Remote control is off by default, and even when on,
  a message needs a whitelisted sender **and** the code word, and is only forwarded to
  droidcommand-AI.

## Privacy & data handling

- The SMS trigger code word and sender whitelist are stored encrypted, never logged, and refused
  by config export.

- Draft reply sends the persona text and the last 20 messages of that thread to **your PC URL
  only**, and only when you tap it. Voice notes send only the draft text and the voice id.

- Twilio credentials (if you add them) are stored encrypted on the phone and used only to talk to
  Twilio over HTTPS. Alias texts and masked calls go through Twilio's servers. The app never
  logs phone numbers, message bodies or tokens.

- Matrix sessions are stored per account, encrypted with a key in the Android Keystore. Tokens
  are never written to logs.
- Messages, contacts and the blocklist stay on the device. Nothing goes to third-party clouds by
  default.
- Offload to Don's PC goes only over the encrypted mesh, only for features you turned on.
- Online caller lookup is opt-in.
- Phone relay traffic is end-to-end encrypted (mutual TLS 1.3, pinned keys that never leave each
  phone's Keystore). Mirroring texts to another phone is off unless you turn it on for that phone.
- **Voice-clone / persona consent gate:** voice cloning and persona features may only use
  **Don's own voice** or voices of people who gave explicit consent. The app will refuse to create
  a voice or persona from anyone else's recordings. (planned)
- Status reports to the fleet never include message bodies, phone numbers, the code word or the
  whitelist.

## Config export/import over the bridge
- `config.export` returns only allowlisted, non-secret settings (assistant LLM/TTS URLs and model, PC lookup URL). The code word, whitelist, tokens, numbers and message bodies are never exported.
- `config.import` needs a nonce, timestampMs and ttlMs, and is a dry run unless `dryRun:false` is set. A real import is held as pending until you approve it on this phone. Poll it with `config.import.status`. An import can never enable external control or remote commands.
- Used nonces are now saved encrypted until they expire, so a restart doesn't reopen the replay window.
- Bridge badges come from the mautrix `m.bridge`/`uk.half-shot.bridge` state first, then from the bridge bot being in the room, and only then from the member-ID prefix.
