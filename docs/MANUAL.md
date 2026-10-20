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

1. Download the debug APK from the CI artifacts of the latest build (or build it yourself with
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

### SMS conversations (planned)
1. Tap a thread to read it; tap the compose box to reply.
   `[screenshot: thread list]` `[screenshot: thread view]`

### Unified inbox with network badges (planned)
1. Bridged rooms show a network icon (WhatsApp, Signal, …).
   `[screenshot: unified inbox]`

### Phone: dialer, call screening, blocklist (planned)
1. Optionally set VictorSMS as the default phone app.
2. Add numbers to the blocklist; blocked calls are silenced or rejected.
3. Caller names are looked up on the device first, then on Don's PC over the mesh, then online
   only if you turned that on.
   `[screenshot: blocklist]`

### Bots: assistant, bridge, voice (planned)
1. Open the bot room and chat. Offload to the PC happens only with your consent.
   `[screenshot: bot room]`

### Backup & restore settings (planned)
1. From droidcommand-AI's unified backup, VictorSMS exports its settings. Export is local only
   (not allowed remotely).
2. The export **never** contains your code word, sender whitelist, Matrix tokens, phone numbers or
   message bodies. If any such field is found, the export is refused.
3. On import you can preview first (dry run) and choose what happens on conflicts: skip (default),
   overwrite, or merge by id.
4. VictorSMS asks you to confirm on the phone before applying. An import never turns on remote control.


## Permissions and why

| Permission / role | Why | Status |
|---|---|---|
| Default SMS role | Required by Android to send, receive and store SMS/MMS | planned |
| `SEND_SMS` | Send approved remote SMS (declared; runtime grant needed) | built |
| `POST_NOTIFICATIONS` | Remote SMS approval prompts | built |
| `RECEIVE_SMS`, `READ_SMS`, `RECEIVE_MMS` | Receive and read messages | planned |
| `READ_CONTACTS` | Show names instead of numbers; first step of caller lookup | planned |
| `POST_NOTIFICATIONS` | New-message notifications | planned |
| Dialer / call-screening role | Optional phone features and blocklist | planned |
| `INTERNET` | Matrix and mesh traffic | built |

You can deny any optional permission; the related feature simply stays off.

## Fleet integration

- **Remote control is OFF by default.** You must enable it in settings and can revoke it any time.
  Every remote action is written to an audit log. (planned)
- **SMS-triggered flow (planned):** an incoming SMS is treated as a fleet trigger only when
  **both** are true:
  1. the sender is on your **sender whitelist**, **and**
  2. the message contains your **secret code word**.
  If both match, VictorSMS **forwards** the request to droidcommand-AI. **VictorSMS never
  executes actions itself.** droidcommand-AI checks its own authorization, confirmation and
  replay rules. The trigger details (sender, matched keyword) are informational only and are not
  used for authorization.
- **Connected apps (built):** each fleet app is identified by its package name and signing
  certificate, checked by Android, not by what the app claims. Unknown apps get "not paired".
  Nothing is allowed until you approve the app and choose its permissions, and all of it sits
  behind the External control switch.
- **Status (planned):** droidcommand-AI can ask VictorSMS for its status (`node.status.get`). The
  answer says whether SMS is ready to deliver and whether the app is healthy. It never contains
  message bodies, phone numbers, your code word or your whitelist.

## Troubleshooting/FAQ

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

- Matrix sessions are stored per account, encrypted with a key in the Android Keystore. Tokens
  are never written to logs.
- Messages, contacts and the blocklist stay on the device. Nothing goes to third-party clouds by
  default.
- Offload to Don's PC goes only over the encrypted mesh, only for features you turned on.
- Online caller lookup is opt-in.
- **Voice-clone / persona consent gate:** voice cloning and persona features may only use
  **Don's own voice** or voices of people who gave explicit consent. The app will refuse to create
  a voice or persona from anyone else's recordings. (planned)
- Status reports to the fleet never include message bodies, phone numbers, the code word or the
  whitelist.
