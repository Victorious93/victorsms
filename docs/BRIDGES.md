# Self-hosting the mautrix bridges

VictorSMS shows WhatsApp, Telegram, Signal, Messenger, Instagram, Discord and Google Messages
chats as Matrix rooms. The bridging happens **on your homeserver**, not in the app.

## Licenses

| Bridge | Repo | License |
|---|---|---|
| mautrix-whatsapp | github.com/mautrix/whatsapp | AGPL-3.0 |
| mautrix-telegram | github.com/mautrix/telegram | AGPL-3.0 |
| mautrix-signal | github.com/mautrix/signal | AGPL-3.0 |
| mautrix-meta (Messenger + Instagram) | github.com/mautrix/meta | AGPL-3.0 |
| mautrix-discord | github.com/mautrix/discord | AGPL-3.0 |
| mautrix-gmessages | github.com/mautrix/gmessages | AGPL-3.0 |

The bridges are **AGPL-3.0**. They run **server-side only** as separate services and are **not
linked into or shipped with the app**. VictorSMS (Apache-2.0) talks to them only through the
standard Matrix client-server API. If you modify a bridge and let others use it over the network,
AGPL requires you to offer them your modified source.

## Requirements

- A Matrix homeserver you control (Synapse recommended) with **native sliding sync** support
  (needed by VictorSMS's Rust SDK client).
- PostgreSQL (recommended for bridges; SQLite works for testing).
- Docker or the bridges' prebuilt binaries.

## Steps (per bridge; WhatsApp shown)

1. Generate a config:
   ```sh
   mkdir -p ~/bridges/whatsapp && cd ~/bridges/whatsapp
   docker run --rm -v "$PWD:/data" dock.mau.dev/mautrix/whatsapp:latest
   ```
2. Edit `config.yaml`:
   - `homeserver.address`: your homeserver's internal URL; `homeserver.domain`: your server name.
   - `database`: your PostgreSQL URI.
   - `bridge.permissions`: give your own Matrix id `admin`, everyone else nothing.
   - Keep encryption enabled (`encryption.allow: true`, `default: true`) if you want end-to-bridge E2EE.
3. Run the container once more to create `registration.yaml`.
4. Register it with Synapse: add the file path to `app_service_config_files` in `homeserver.yaml`
   and restart Synapse.
5. Start the bridge: `docker run -d --restart unless-stopped -v "$PWD:/data" dock.mau.dev/mautrix/whatsapp:latest`.
6. In VictorSMS (or any Matrix client), start a chat with the bridge bot (e.g.
   `@whatsappbot:your.server`) and send `login`. Follow the QR-code / phone-number steps.
7. Your chats appear as rooms. VictorSMS badges them by source.

Repeat for each bridge (images: `dock.mau.dev/mautrix/telegram`, `/signal`, `/meta`, `/discord`,
`/gmessages`). mautrix-meta runs once per network: set `network.mode` to `messenger` or `instagram`.

## How VictorSMS detects bridged rooms

Currently a heuristic: if a room's heroes (main members) include a user whose localpart starts with
the bridge's default prefix (`whatsapp_`, `telegram_`, `signal_`, `instagram_`, `messenger_`,
`facebook_`, `discord_`, `gmessages_`) or is the bridge bot (`whatsappbot`, …), the room gets that
badge. If you change `username_template` in a bridge config, detection will show the room as plain
Matrix. Reading the `m.bridge` / `uk.half-shot.bridge` room state is planned.
