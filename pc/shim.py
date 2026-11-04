"""VictorSMS PC shim: Ollama /api/chat proxy + Chatterbox TTS behind a bearer token.

Matches app/src/main/java/com/victorious/sms/assist/PcEndpoints.kt:
  POST /api/chat  {model, messages, stream:false} -> Ollama JSON (message.content)
  POST /tts       {"text","voice"}                -> audio/wav bytes
  GET  /status    auth'd status for the app / voice-clone status proxy
  GET  /healthz   unauthenticated liveness ({"ok":true} only)
Never logs request bodies, text, or the token.
"""
from __future__ import annotations

import hmac
import io
import json
import os
import re
import threading
from pathlib import Path

import httpx
from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse, Response

CONFIG_DIR = Path(os.environ.get("VSMS_CONFIG_DIR", Path.home() / ".config" / "victorsms-pc"))
DATA_DIR = Path(os.environ.get("VSMS_DATA_DIR", Path.home() / ".local" / "share" / "victorsms-pc"))
VOICES_DIR = DATA_DIR / "voices"
OLLAMA_URL = os.environ.get("VSMS_OLLAMA_URL", "http://127.0.0.1:11434").rstrip("/")
DEFAULT_MODEL = os.environ.get("VSMS_MODEL", "qwen2.5:14b")
# The app's default model field is "llama3.1"; we serve the configured model unless overrides are allowed.
ALLOW_MODEL_OVERRIDE = os.environ.get("VSMS_ALLOW_MODEL_OVERRIDE", "0") == "1"
MAX_TEXT = int(os.environ.get("VSMS_MAX_TTS_CHARS", "1000"))
VOICE_ID = re.compile(r"^[A-Za-z0-9_-]{1,64}$")


def load_token() -> str:
    t = os.environ.get("VSMS_TOKEN") or (CONFIG_DIR / "token").read_text().strip()
    if len(t) < 32:
        raise RuntimeError("token too short")
    return t


TOKEN = load_token()


def require_auth(request: Request) -> None:
    h = request.headers.get("authorization", "")
    ok = h.startswith("Bearer ") and hmac.compare_digest(h[7:].strip().encode(), TOKEN.encode())
    if not ok:
        raise HTTPException(status_code=401, detail="unauthorized", headers={"WWW-Authenticate": "Bearer"})


def consented_voice(voice_id: str) -> Path:
    """Consent gate: voices/<id>/consent.json must have consent:true plus who/how/when; reference.wav must exist."""
    if not VOICE_ID.match(voice_id or ""):
        raise HTTPException(status_code=400, detail="invalid voice id")
    d = VOICES_DIR / voice_id
    try:
        c = json.loads((d / "consent.json").read_text())
    except (OSError, ValueError):
        raise HTTPException(status_code=403, detail="voice has no recorded consent")
    own = c.get("ownership")
    valid = c.get("consent") is True and own in ("own", "consented") and c.get("recorded_at")
    if own == "consented":
        valid = valid and bool(c.get("consent_from")) and bool(c.get("consent_note"))
    if not valid:
        raise HTTPException(status_code=403, detail="voice has no recorded consent")
    ref = d / "reference.wav"
    if not ref.is_file():
        raise HTTPException(status_code=404, detail="voice reference audio missing")
    return ref


def list_consented() -> list[str]:
    out = []
    if VOICES_DIR.is_dir():
        for d in sorted(VOICES_DIR.iterdir()):
            try:
                consented_voice(d.name)
                out.append(d.name)
            except HTTPException:
                pass
    return out


class Tts:
    """Lazy Chatterbox loader (first request pays the load cost)."""
    def __init__(self) -> None:
        self.model = None
        self.device = None
        self.error: str | None = None
        self.lock = threading.Lock()

    def get(self):
        with self.lock:
            if self.model is None:
                import torch  # ROCm builds of PyTorch expose the GPU as "cuda"
                from chatterbox.tts import ChatterboxTTS
                self.device = "cuda" if torch.cuda.is_available() else "cpu"
                self.model = ChatterboxTTS.from_pretrained(device=self.device)
            return self.model

    def synth(self, text: str, ref: Path) -> bytes:
        import torchaudio
        m = self.get()
        with self.lock:
            wav = m.generate(text, audio_prompt_path=str(ref))
        buf = io.BytesIO()
        torchaudio.save(buf, wav.cpu(), m.sr, format="wav")
        return buf.getvalue()


tts = Tts()
app = FastAPI(title="VictorSMS PC shim", docs_url=None, redoc_url=None, openapi_url=None)


@app.get("/healthz")
def healthz():
    return {"ok": True}


@app.get("/status", dependencies=[Depends(require_auth)])
async def status():
    llm = {"reachable": False, "model": DEFAULT_MODEL, "model_present": False}
    try:
        async with httpx.AsyncClient(timeout=3) as c:
            r = await c.get(OLLAMA_URL + "/api/tags")
            names = [m.get("name", "") for m in r.json().get("models", [])]
            llm.update(reachable=r.status_code == 200, model_present=any(n.split(":latest")[0] == DEFAULT_MODEL or n == DEFAULT_MODEL for n in names))
    except Exception:
        pass
    return {"ok": llm["reachable"], "llm": llm,
            "tts": {"loaded": tts.model is not None, "device": tts.device, "error": tts.error},
            "voices": list_consented()}


@app.post("/api/chat", dependencies=[Depends(require_auth)])
async def chat(request: Request):
    try:
        body = await request.json()
    except ValueError:
        raise HTTPException(status_code=400, detail="invalid json")
    if not isinstance(body, dict) or not isinstance(body.get("messages"), list):
        raise HTTPException(status_code=400, detail="messages required")
    if not (ALLOW_MODEL_OVERRIDE and body.get("model")):
        body["model"] = DEFAULT_MODEL
    body["stream"] = False
    try:
        async with httpx.AsyncClient(timeout=180) as c:
            r = await c.post(OLLAMA_URL + "/api/chat", json=body)
    except httpx.HTTPError:
        raise HTTPException(status_code=502, detail="llm unreachable")
    return Response(content=r.content, status_code=r.status_code, media_type="application/json")


@app.post("/tts", dependencies=[Depends(require_auth)])
async def synth(request: Request):
    try:
        body = await request.json()
    except ValueError:
        raise HTTPException(status_code=400, detail="invalid json")
    text = str(body.get("text", "")).strip() if isinstance(body, dict) else ""
    if not text or len(text) > MAX_TEXT:
        raise HTTPException(status_code=400, detail=f"text required, max {MAX_TEXT} chars")
    ref = consented_voice(str(body.get("voice", "")))
    import anyio
    try:
        audio = await anyio.to_thread.run_sync(tts.synth, text, ref)
    except Exception as e:  # don't echo text; type only
        tts.error = type(e).__name__
        return JSONResponse({"detail": "tts failed: " + type(e).__name__}, status_code=500)
    tts.error = None
    return Response(content=audio, media_type="audio/wav")
