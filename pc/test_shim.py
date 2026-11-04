"""GPU-free smoke tests for shim.py (no Ollama, no Chatterbox needed). Run: python3 -m pytest pc/test_shim.py"""
import json
import os
import sys
import tempfile
from pathlib import Path

import httpx

TMP = Path(tempfile.mkdtemp())
os.environ.update(VSMS_TOKEN="t" * 43, VSMS_DATA_DIR=str(TMP), VSMS_OLLAMA_URL="http://ollama.test")
sys.path.insert(0, str(Path(__file__).parent))
import shim  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

c = TestClient(shim.app)
AUTH = {"Authorization": "Bearer " + "t" * 43}


def voice(vid, consent):
    d = TMP / "voices" / vid
    d.mkdir(parents=True, exist_ok=True)
    (d / "reference.wav").write_bytes(b"RIFF")
    if consent is not None:
        (d / "consent.json").write_text(json.dumps(consent))


def test_healthz_open():
    assert c.get("/healthz").json() == {"ok": True}


def test_auth_required():
    for path in ("/status",):
        assert c.get(path).status_code == 401
        assert c.get(path, headers={"Authorization": "Bearer wrong"}).status_code == 401
    assert c.post("/tts", json={"text": "hi", "voice": "x"}).status_code == 401
    assert c.post("/api/chat", json={"messages": []}).status_code == 401


def test_consent_gate(monkeypatch):
    monkeypatch.setattr(shim.tts, "synth", lambda text, ref: b"RIFFwav")
    voice("none", None)
    voice("bad", {"consent": True, "ownership": "consented", "recorded_at": "x"})  # no who/how
    voice("me", {"consent": True, "ownership": "own", "recorded_at": "2026-10-31"})
    assert c.post("/tts", headers=AUTH, json={"text": "hi", "voice": "none"}).status_code == 403
    assert c.post("/tts", headers=AUTH, json={"text": "hi", "voice": "bad"}).status_code == 403
    assert c.post("/tts", headers=AUTH, json={"text": "hi", "voice": "../etc"}).status_code == 400
    r = c.post("/tts", headers=AUTH, json={"text": "hi", "voice": "me"})
    assert r.status_code == 200 and r.headers["content-type"] == "audio/wav" and r.content == b"RIFFwav"
    assert c.get("/status", headers=AUTH).json()["voices"] == ["me"]


def test_chat_proxy_forces_model(monkeypatch):
    seen = {}

    async def fake_post(self, url, json=None, **kw):
        seen.update(url=url, body=json)
        return httpx.Response(200, json={"message": {"role": "assistant", "content": "yo"}})
    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    r = c.post("/api/chat", headers=AUTH, json={"model": "llama3.1", "stream": True, "messages": [{"role": "user", "content": "x"}]})
    assert r.json()["message"]["content"] == "yo"
    assert seen["url"] == "http://ollama.test/api/chat"
    assert seen["body"]["model"] == shim.DEFAULT_MODEL and seen["body"]["stream"] is False
