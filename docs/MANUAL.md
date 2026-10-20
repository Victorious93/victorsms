# VictorSMS User Manual

> Current state: **scaffold (v0.1.0)**. Only the Compose shell, theme and icon exist today.
> Everything else below is marked **(planned)** and describes the intended behavior.

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

### Home screen (built)
1. Open the app. The scaffold home screen appears.
   `[screenshot: home screen]`

### Conversations (planned)
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

### Help / Manual screen (planned)
1. Open the menu → Help to read this manual inside the app.

## Permissions and why

| Permission / role | Why | Status |
|---|---|---|
| Default SMS role | Required by Android to send, receive and store SMS/MMS | planned |
| `SEND_SMS`, `RECEIVE_SMS`, `READ_SMS`, `RECEIVE_MMS` | Send and receive messages | planned |
| `READ_CONTACTS` | Show names instead of numbers; first step of caller lookup | planned |
| `POST_NOTIFICATIONS` | New-message notifications | planned |
| Dialer / call-screening role | Optional phone features and blocklist | planned |
| `INTERNET` | Matrix and mesh traffic | planned |

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
- **Status (planned):** droidcommand-AI can ask VictorSMS for its status (`node.status.get`). The
  answer says whether SMS is ready to deliver and whether the app is healthy. It never contains
  message bodies, phone numbers, your code word or your whitelist.

## Troubleshooting/FAQ

- **The app only shows a blank/home screen.** Expected: this is the scaffold build.
- **APK won't install.** Uninstall any older build signed with a different key, then retry.
- **Building locally runs out of memory.** Use
  `./gradlew assembleDebug --no-daemon -Dorg.gradle.jvmargs=-Xmx1536m`.
- **Will a random SMS control my phone?** No. Remote control is off by default, and even when on,
  a message needs a whitelisted sender **and** the code word, and is only forwarded to
  droidcommand-AI.

## Privacy & data handling

- Messages, contacts and the blocklist stay on the device. Nothing goes to third-party clouds by
  default.
- Offload to Don's PC goes only over the encrypted mesh, only for features you turned on.
- Online caller lookup is opt-in.
- **Voice-clone / persona consent gate:** voice cloning and persona features may only use
  **Don's own voice** or voices of people who gave explicit consent. The app will refuse to create
  a voice or persona from anyone else's recordings. (planned)
- Status reports to the fleet never include message bodies, phone numbers, the code word or the
  whitelist.
