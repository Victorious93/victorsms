#!/usr/bin/env bash
# VictorSMS PC AI setup (Ubuntu 22.04/24.04, AMD RX 6800 XT gfx1030 -> ROCm, not CUDA).
# Installs/updates: ROCm check (or install), Ollama (ROCm) + model, Chatterbox TTS (PyTorch ROCm venv),
# and the FastAPI shim as a systemd *user* service. Idempotent: safe to re-run.
#
#   ./setup.sh                 install / update
#   ./setup.sh --dry-run       print what would run, change nothing
#   ./setup.sh --uninstall     remove shim service + venv (keeps voices/token; add --purge to delete them)
#   ./setup.sh --bind IP       bind address (default: tailscale0 IPv4 if present, else 127.0.0.1)
#   ./setup.sh --model NAME    Ollama model (default qwen2.5:14b, Apache-2.0, ~9 GB Q4)
#   ./setup.sh --port N        shim port (default 8765)
#   ./setup.sh --rotate-token  generate a new token (shown once)
set -euo pipefail

DRY=0; UNINSTALL=0; PURGE=0; ROTATE=0
BIND=""; PORT=8765; MODEL="qwen2.5:14b"
ROCM_VERSION="6.2.4"; TORCH_INDEX="https://download.pytorch.org/whl/rocm6.2.4"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONF="$HOME/.config/victorsms-pc"; DATA="$HOME/.local/share/victorsms-pc"; VENV="$DATA/venv"
UNIT_DIR="$HOME/.config/systemd/user"; UNIT="victorsms-shim.service"

while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY=1 ;;
    --uninstall) UNINSTALL=1 ;;
    --purge) PURGE=1 ;;
    --rotate-token) ROTATE=1 ;;
    --bind) BIND="${2:?}"; shift ;;
    --port) PORT="${2:?}"; shift ;;
    --model) MODEL="${2:?}"; shift ;;
    -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

log() { printf '\033[36m==>\033[0m %s\n' "$*"; }
run() { if [ "$DRY" = 1 ]; then printf '[dry-run] %s\n' "$*"; else "$@"; fi; }

if [ "$(id -u)" = 0 ]; then echo "Run as your normal user (sudo is used where needed)." >&2; exit 1; fi

uninstall() {
  log "Stopping and removing shim user service"
  run systemctl --user disable --now "$UNIT" || true
  run rm -f "$UNIT_DIR/$UNIT"
  run systemctl --user daemon-reload
  run rm -rf "$VENV" "$DATA/app"
  if [ "$PURGE" = 1 ]; then run rm -rf "$CONF" "$DATA"; log "Purged token and voices"; fi
  log "Ollama, the model and ROCm are left installed (remove with: sudo systemctl disable --now ollama; ollama rm $MODEL)"
}
if [ "$UNINSTALL" = 1 ]; then uninstall; exit 0; fi

# --- 1. ROCm -------------------------------------------------------------------
. /etc/os-release
case "${VERSION_CODENAME:-}" in jammy|noble) ;; *) echo "Ubuntu 22.04 (jammy) or 24.04 (noble) required" >&2; [ "$DRY" = 1 ] || exit 1; VERSION_CODENAME=noble ;; esac
if command -v rocminfo >/dev/null 2>&1 || [ -x /opt/rocm/bin/rocminfo ]; then
  log "ROCm present"
else
  log "Installing ROCm $ROCM_VERSION (amdgpu-install, rocm usecase, no DKMS)"
  deb="amdgpu-install_6.2.60204-1_all.deb"
  run wget -q -O "/tmp/$deb" "https://repo.radeon.com/amdgpu-install/$ROCM_VERSION/ubuntu/$VERSION_CODENAME/$deb"
  run sudo apt-get install -y "/tmp/$deb"
  run sudo amdgpu-install -y --usecase=rocm --no-dkms
  run sudo usermod -aG render,video "$USER"
  log "ROCm installed. Log out/in (or reboot) so render/video group membership applies, then re-run."
fi
ROCMINFO="$(command -v rocminfo || echo /opt/rocm/bin/rocminfo)"
if [ "$DRY" = 0 ] && [ -x "$ROCMINFO" ]; then
  if "$ROCMINFO" 2>/dev/null | grep -q gfx1030; then log "GPU gfx1030 visible to ROCm"
  else echo "WARNING: rocminfo does not show gfx1030 (group membership? reboot?). Continuing; GPU paths may fall back to CPU." >&2; fi
fi

# --- 2. Ollama -----------------------------------------------------------------
if command -v ollama >/dev/null 2>&1; then log "Ollama present: $(ollama --version 2>/dev/null | tail -1)"
else
  log "Installing Ollama (installer detects AMD and adds the ROCm runtime)"
  if [ "$DRY" = 1 ]; then echo "[dry-run] curl -fsSL https://ollama.com/install.sh | sh"
  else curl -fsSL https://ollama.com/install.sh | sh; fi
fi
log "Pinning Ollama to localhost only (the shim is the only network entry point)"
run sudo mkdir -p /etc/systemd/system/ollama.service.d
if [ "$DRY" = 1 ]; then echo "[dry-run] write /etc/systemd/system/ollama.service.d/victorsms.conf (OLLAMA_HOST=127.0.0.1:11434)"
else printf '[Service]\nEnvironment="OLLAMA_HOST=127.0.0.1:11434"\n' | sudo tee /etc/systemd/system/ollama.service.d/victorsms.conf >/dev/null; fi
run sudo systemctl daemon-reload
run sudo systemctl enable --now ollama
run sudo systemctl restart ollama
log "Pulling model $MODEL (skipped if already present)"
if [ "$DRY" = 1 ]; then echo "[dry-run] ollama pull $MODEL"
else
  for _ in $(seq 1 30); do curl -fs http://127.0.0.1:11434/api/tags >/dev/null && break; sleep 1; done
  ollama list 2>/dev/null | awk 'NR>1{print $1}' | grep -qx "$MODEL" || ollama pull "$MODEL"
fi

# --- 3. Python venv: PyTorch ROCm + Chatterbox + shim deps ----------------------
log "Python venv at $VENV"
run sudo apt-get install -y python3-venv python3-dev ffmpeg
[ -d "$VENV" ] || run python3 -m venv "$VENV"
run "$VENV/bin/pip" install -q --upgrade pip
# torch first from the ROCm index so chatterbox's torch==2.6.0 pin is satisfied by the +rocm build.
run "$VENV/bin/pip" install -q "torch==2.6.0" "torchaudio==2.6.0" --index-url "$TORCH_INDEX"
run "$VENV/bin/pip" install -q chatterbox-tts -r "$HERE/requirements-shim.txt"
if [ "$DRY" = 0 ]; then
  "$VENV/bin/python" -c 'import torch,sys; print("torch", torch.__version__, "hip", torch.version.hip, "gpu", torch.cuda.is_available())'
  "$VENV/bin/python" -c 'import torch,sys; sys.exit(0 if "rocm" in torch.__version__ else 1)' \
    || { echo "torch is not a ROCm build (a CUDA wheel got pulled in). Aborting." >&2; exit 1; }
fi
run mkdir -p "$DATA/app" "$DATA/voices"
run install -m 0644 "$HERE/shim.py" "$DATA/app/shim.py"
run install -m 0755 "$HERE/voicectl.py" "$DATA/app/voicectl.py"

# --- 4. Token (generated locally, shown once, never committed) -------------------
run mkdir -p "$CONF"; run chmod 700 "$CONF"
if [ ! -s "$CONF/token" ] || [ "$ROTATE" = 1 ]; then
  if [ "$DRY" = 1 ]; then echo "[dry-run] generate token -> $CONF/token (0600)"
  else
    (umask 077; python3 -c 'import secrets; print(secrets.token_urlsafe(32))' > "$CONF/token")
    echo; echo "PC token (shown ONCE; paste into VictorSMS → Settings → Assistant & persona → PC token):"
    echo "  $(cat "$CONF/token")"; echo
  fi
else log "Token exists (not shown again; use --rotate-token for a new one)"; fi

# --- 5. Bind address: mesh or localhost, never 0.0.0.0 by default ----------------
if [ -z "$BIND" ]; then
  BIND="$(ip -4 -o addr show dev tailscale0 2>/dev/null | awk '{print $4}' | cut -d/ -f1 | head -1 || true)"
  [ -n "$BIND" ] || BIND=127.0.0.1
fi
if [ "$BIND" = 0.0.0.0 ]; then echo "WARNING: binding to 0.0.0.0 exposes the shim on every interface." >&2; fi
log "Shim will bind $BIND:$PORT"

# --- 6. systemd user service -----------------------------------------------------
run mkdir -p "$UNIT_DIR"
unit="[Unit]
Description=VictorSMS PC shim (Ollama proxy + Chatterbox TTS)
After=network-online.target

[Service]
Environment=VSMS_MODEL=$MODEL
Environment=VSMS_OLLAMA_URL=http://127.0.0.1:11434
WorkingDirectory=$DATA/app
ExecStart=$VENV/bin/uvicorn shim:app --host $BIND --port $PORT --no-access-log
Restart=on-failure
RestartSec=5
NoNewPrivileges=true

[Install]
WantedBy=default.target"
if [ "$DRY" = 1 ]; then echo "[dry-run] write $UNIT_DIR/$UNIT:"; echo "$unit"
else printf '%s\n' "$unit" > "$UNIT_DIR/$UNIT"; fi
run systemctl --user daemon-reload
run systemctl --user enable "$UNIT"
run systemctl --user restart "$UNIT"
run sudo loginctl enable-linger "$USER"

log "Done. In VictorSMS → Settings → Assistant & persona:"
echo "  LLM URL:   http://$BIND:$PORT      Model: $MODEL"
echo "  Voice URL: http://$BIND:$PORT/tts"
echo "Register a voice (consent gate): $VENV/bin/python $DATA/app/voicectl.py add --id me --wav ~/me.wav --own"
echo "Check: curl -H \"Authorization: Bearer \$(cat $CONF/token)\" http://$BIND:$PORT/status"
