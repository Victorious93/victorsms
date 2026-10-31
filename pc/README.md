# VictorSMS PC AI (Ubuntu, AMD ROCm)

One command sets up the PC side of **Draft reply** and **Voice note** for Don's PC
(Ryzen 5 5500, Radeon RX 6800 XT 16 GB = gfx1030 → **ROCm, not CUDA**, 16 GB RAM).

> Status: written and statically checked (shellcheck, dry-run, GPU-free shim tests).
> **Not yet run on the real PC.** Expect to fix small things on first run.

```bash
git clone https://github.com/Victorious93/victorsms && cd victorsms/pc
./setup.sh --dry-run      # see exactly what it will do
./setup.sh                # install/update (idempotent; re-run any time)
./setup.sh --uninstall    # remove shim service + venv (add --purge to delete token and voices)
```
Options: `--bind IP` (default: the `tailscale0` mesh IPv4, else `127.0.0.1`; never `0.0.0.0` unless you ask),
`--port 8765`, `--model qwen2.5:14b`, `--rotate-token`.

## What it installs
| Piece | Details |
|---|---|
| ROCm 6.2.4 | Skipped if `rocminfo` exists. Uses `amdgpu-install --usecase=rocm --no-dkms`; adds you to `render`,`video` (log out/in after). |
| Ollama | Official installer (adds ROCm runtime on AMD). Pinned to `127.0.0.1:11434` via a systemd drop-in, so only the shim is reachable from the mesh. |
| Model | `qwen2.5:14b` (Apache-2.0, ~9 GB Q4: fits 16 GB VRAM with room for TTS). Smaller: `--model qwen2.5:7b`. |
| Chatterbox TTS | `chatterbox-tts` (MIT) in `~/.local/share/victorsms-pc/venv` on PyTorch 2.6.0 **ROCm 6.2.4** wheels. The script aborts if a CUDA torch slips in. Chatterbox adds Resemble's Perth watermark to output. |
| Shim | `shim.py` (FastAPI) as systemd **user** service `victorsms-shim` (+ `loginctl enable-linger`). |

## API (matches `app/.../assist/PcEndpoints.kt`)
All except `/healthz` need `Authorization: Bearer <token>`.
| Method | Path | Body → Response |
|---|---|---|
| POST | `/api/chat` | Ollama format `{model, messages, stream:false}` → Ollama JSON (`message.content`). The shim always uses the configured model (the app's default `llama3.1` is replaced). |
| POST | `/tts` | `{"text","voice"}` → `audio/wav`. 403 unless the voice has a recorded consent file. Max 1000 chars. |
| GET | `/status` | `{ok, llm:{reachable,model,model_present}, tts:{loaded,device,error}, voices:[consented ids]}` for status checks / a voice-clone status proxy. |
| GET | `/healthz` | `{"ok":true}` only (no auth, no details). |

In the app (Settings → Assistant & persona): LLM URL `http://<mesh-ip>:8765`, Voice URL `http://<mesh-ip>:8765/tts`, PC token = the token printed once by setup.

## Token
Generated on the PC with `secrets.token_urlsafe(32)`, stored at `~/.config/victorsms-pc/token` (0600), printed **once**.
Never commit it. Lost it? `./setup.sh --rotate-token` and re-enter it in the app.

## Voices (consent gate)
Only your own voice, or someone who gave explicit consent:
```bash
~/.local/share/victorsms-pc/venv/bin/python ~/.local/share/victorsms-pc/app/voicectl.py add --id me --wav ~/me.wav --own
.../voicectl.py add --id mom --wav mom.wav --consent-from "Mom" --consent-note "Verbal, recorded 2026-10-30"
.../voicectl.py list | remove --id mom
```
This writes `voices/<id>/consent.json`; the shim refuses any voice without it. Then add the same id in the app.

## Troubleshooting
`systemctl --user status victorsms-shim`, `journalctl --user -u victorsms-shim` (no texts or tokens are logged),
`rocminfo | grep gfx1030`, `ollama ps` (shows GPU vs CPU).

## Tests (no GPU)
`pip install fastapi httpx pytest && python3 -m pytest pc/test_shim.py`
