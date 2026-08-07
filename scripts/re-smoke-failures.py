#!/usr/bin/env python3
"""Re-smoke models that failed the full matrix after smoke/kit fixes."""

from __future__ import annotations

import base64
import json
import struct
import time
import wave
import io
from pathlib import Path

import requests

BASE = "https://play-proxy.skyphusion.org"
KEY_FILE = Path.home() / ".config/prism-control-plane/play-test-pcp.key"
UA = "PrismAndroidReSmoke/1.0"
OUT = Path.home() / "dev/prism-android/smoke-results" / "re-smoke-failures.json"

CHAT = ["moonshotai/kimi-k3", "openai/gpt-5.5", "openai/o4-mini"]
STT = [
    "@cf/openai/whisper-large-v3-turbo",
    "@cf/openai/whisper",
    "@cf/openai/whisper-tiny-en",
    "@cf/deepgram/nova-3",
]
TTS = ["@cf/myshell-ai/melotts"]


def key() -> str:
    k = KEY_FILE.read_text().strip().replace("\n", "").replace("\r", "")
    assert k.startswith("pcp_")
    return k


def session() -> requests.Session:
    s = requests.Session()
    s.headers.update(
        {
            "Authorization": f"Bearer {key()}",
            "User-Agent": UA,
            "Content-Type": "application/json",
            "Accept": "application/json",
        }
    )
    return s


def tiny_wav() -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(16000)
        n = 16000
        w.writeframes(struct.pack("<" + "h" * n, *([0] * n)))
    return buf.getvalue()


def chat_text(body: dict) -> str:
    choices = body.get("choices") or []
    if choices:
        msg = (choices[0] or {}).get("message") or {}
        c = msg.get("content")
        if isinstance(c, str) and c.strip():
            return c.strip()[:200]
    return ""


def wait_job(s: requests.Session, job_id: str, timeout_s: float = 180) -> dict:
    deadline = time.monotonic() + timeout_s
    last: dict = {}
    while time.monotonic() < deadline:
        r = s.get(f"{BASE}/v1/jobs/{job_id}", timeout=60)
        last = r.json() if r.content else {}
        if (last.get("status") or "").lower() in ("succeeded", "failed"):
            return last
        time.sleep(3)
    return {**last, "status": "timeout"}


def main() -> int:
    s = session()
    results = []

    def rec(mod: str, model: str, ok: bool, st: int | None, detail: str, secs: float):
        results.append(
            {
                "modality": mod,
                "model": model,
                "ok": ok,
                "status": st,
                "detail": detail[:400],
                "seconds": round(secs, 2),
            }
        )
        print(f"[{'OK ' if ok else 'FAIL'}] {mod:5} {model[:48]:48} {secs:6.1f}s  {detail[:120]}")

    print(f"re-smoke base={BASE}")

    # Chat: max_tokens 256 (reasoning models need budget; product omits → plan ceiling)
    for mid in CHAT:
        t0 = time.monotonic()
        r = s.post(
            f"{BASE}/v1/chat/completions",
            json={
                "model": mid,
                "messages": [{"role": "user", "content": "Reply with exactly: ok"}],
                "stream": False,
                "max_tokens": 256,
            },
            timeout=120,
        )
        body = r.json() if r.content else {}
        text = chat_text(body) if isinstance(body, dict) else ""
        ok = r.status_code == 200 and not (isinstance(body, dict) and body.get("error"))
        detail = text or (json.dumps(body.get("error") or body)[:200] if not ok else "(empty content)")
        rec("chat", mid, ok, r.status_code, detail, time.monotonic() - t0)
        time.sleep(4)

    # STT: JSON data URL (plane contract; matches prism-kit)
    data_url = "data:audio/wav;base64," + base64.b64encode(tiny_wav()).decode("ascii")
    for mid in STT:
        t0 = time.monotonic()
        r = s.post(
            f"{BASE}/v1/audio/transcriptions",
            json={"model": mid, "audio": data_url},
            timeout=120,
        )
        body = r.json() if r.content else {}
        text = (body.get("text") if isinstance(body, dict) else None) or ""
        ok = r.status_code == 200 and not (isinstance(body, dict) and body.get("error"))
        detail = f"text={text!r}" if ok else json.dumps(body)[:200]
        rec("stt", mid, ok, r.status_code, detail, time.monotonic() - t0)
        time.sleep(4)

    # MeloTTS: CF has been flaky (AiError 3043); one retry
    for mid in TTS:
        t0 = time.monotonic()
        ok = False
        detail = ""
        st = 0
        for attempt in range(2):
            r = s.post(
                f"{BASE}/v1/audio/speech",
                headers={"Prefer": "respond-async"},
                json={"model": mid, "input": "Prism smoke test.", "async": True},
                timeout=60,
            )
            st = r.status_code
            body = r.json() if r.content else {}
            if body.get("id") and not body.get("audio_base64"):
                job = wait_job(s, body["id"])
                res = job.get("result") or {}
                audio = res.get("audio_base64") or res.get("audio")
                if job.get("status") == "succeeded" and audio:
                    ok = True
                    detail = f"async audio len={len(str(audio))}"
                    break
                detail = json.dumps(job.get("error") or job)[:250]
            elif body.get("audio_base64"):
                ok = True
                detail = f"sync audio len={len(body['audio_base64'])}"
                break
            else:
                detail = json.dumps(body)[:250]
            time.sleep(4)
        rec("tts", mid, ok, st, detail, time.monotonic() - t0)

    ok_n = sum(1 for r in results if r["ok"])
    print(f"\nRE-SMOKE {ok_n}/{len(results)} ok")
    for r in results:
        if not r["ok"]:
            print(f"  still FAIL {r['modality']} {r['model']}: {r['detail'][:160]}")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps({"ok": ok_n, "total": len(results), "results": results}, indent=2))
    print(f"wrote {OUT}")
    return 0 if ok_n == len(results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
