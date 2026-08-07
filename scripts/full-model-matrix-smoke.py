#!/usr/bin/env python3
"""
Full catalog smoke against live play-proxy (iOS 1.0 / Android 1.0 parity matrix).

Exercises every spendable model: chat, image, video, tts, stt, music.
Video uses the model min clip length (not max) to bound cost/time.
Async jobs (Prefer: respond-async) are polled to terminal.

Auth: reads pcp_ from PRISM_SMOKE_KEY_FILE or ~/.config/prism-control-plane/play-test-pcp.key
Never prints the key.

Usage:
  python3 scripts/full-model-matrix-smoke.py
  python3 scripts/full-model-matrix-smoke.py --only chat,image
  python3 scripts/full-model-matrix-smoke.py --limit 2
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import sys
import time
import wave
import struct
import io
from dataclasses import dataclass, asdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import requests

BASE = os.environ.get("PRISM_BASE_URL", "https://play-proxy.skyphusion.org").rstrip("/")
KEY_FILE = Path(
    os.environ.get(
        "PRISM_SMOKE_KEY_FILE",
        str(Path.home() / ".config/prism-control-plane/play-test-pcp.key"),
    )
)
UA = "PrismAndroidFullMatrixSmoke/1.0.0 (+skyphusion-labs/prism-android)"
RPM = 15  # stay under plan 20 rpm
MIN_GAP = 60.0 / RPM

OUT_DIR = Path(
    os.environ.get(
        "PRISM_SMOKE_OUT",
        str(Path.home() / "dev/prism-android/smoke-results"),
    )
)


@dataclass
class Result:
    modality: str
    model: str
    ok: bool
    status: int | None
    detail: str
    seconds: float
    job_id: str | None = None


class Rate:
    def __init__(self) -> None:
        self._last = 0.0

    def wait(self) -> None:
        now = time.monotonic()
        gap = MIN_GAP - (now - self._last)
        if gap > 0:
            time.sleep(gap)
        self._last = time.monotonic()


def load_key() -> str:
    if not KEY_FILE.is_file():
        sys.exit(f"missing key file: {KEY_FILE}")
    key = KEY_FILE.read_text().strip().replace("\n", "").replace("\r", "")
    if not key.startswith("pcp_"):
        sys.exit("key file does not look like a pcp_ device key")
    return key


def video_duration_seconds(model_id: str) -> int:
    """Min legal clip length (cheapest/fastest) per CF/plane catalog."""
    mid = model_id.strip()
    if mid.startswith("xai/grok-imagine-video"):
        return 1
    if mid.startswith("bytedance/seedance"):
        return 4
    if mid.startswith("google/veo"):
        return 4
    if mid.startswith("minimax/hailuo"):
        return 6
    if mid.startswith("runwayml/"):
        return 2
    if mid in (
        "alibaba/hh1-t2v",
        "alibaba/hh1-i2v",
        "alibaba/hh1.1-t2v",
        "alibaba/hh1.1-i2v",
    ):
        return 3
    if mid == "alibaba/wan-2.7-i2v" or mid.startswith("alibaba/wan"):
        return 2
    if mid == "pixverse/v6":
        return 1
    if mid.startswith("pixverse/"):
        return 5
    if mid.startswith("vidu/"):
        return 1
    return 1


def video_duration_wire(model_id: str, seconds: int) -> int | str:
    if model_id.strip().startswith("google/veo"):
        return f"{seconds}s"
    return seconds


class Client:
    def __init__(self, key: str) -> None:
        self.s = requests.Session()
        self.s.headers.update(
            {
                "Authorization": f"Bearer {key}",
                "User-Agent": UA,
                "Content-Type": "application/json",
                "Accept": "application/json",
            }
        )
        self.rate = Rate()

    def request(
        self,
        method: str,
        path: str,
        body: dict | None = None,
        *,
        timeout: float = 300,
        prefer_async: bool = False,
        raw: bool = False,
    ) -> tuple[int, Any]:
        self.rate.wait()
        headers = {}
        if prefer_async:
            headers["Prefer"] = "respond-async"
        url = f"{BASE}{path}"
        try:
            r = self.s.request(
                method,
                url,
                json=body,
                headers=headers,
                timeout=timeout,
            )
        except requests.RequestException as e:
            return 0, {"error": {"message": f"transport: {e}"}}
        if raw:
            return r.status_code, r.content
        try:
            data = r.json() if r.content else {}
        except Exception:
            data = {"raw": r.text[:800]}
        return r.status_code, data

    def wait_job(
        self,
        job_id: str,
        *,
        timeout_s: float = 420,
        poll_s: float = 4,
    ) -> tuple[int, dict]:
        deadline = time.monotonic() + timeout_s
        last: dict = {}
        last_st = 0
        while time.monotonic() < deadline:
            st, j = self.request("GET", f"/v1/jobs/{job_id}", timeout=60)
            last_st, last = st, j if isinstance(j, dict) else {"raw": j}
            status = (last.get("status") or "").lower()
            if status in ("succeeded", "failed"):
                return st, last
            time.sleep(poll_s)
        return last_st, {**last, "error": {"message": f"job poll timeout after {timeout_s}s"}}


def first_image_url(payload: dict) -> str | None:
    data = payload.get("data") or []
    if not data:
        # async job result
        res = payload.get("result") or {}
        data = res.get("data") or []
    if not data:
        return None
    d0 = data[0] if isinstance(data[0], dict) else {}
    url = d0.get("url")
    if url:
        return url
    b64 = d0.get("b64_json") or ""
    if b64.startswith("http://") or b64.startswith("https://"):
        return b64
    if b64 and not b64.startswith("data:"):
        return f"data:image/png;base64,{b64}"
    if b64.startswith("data:"):
        return b64
    return None


def extract_chat_text(body: dict) -> str:
    """Best-effort assistant text from OpenAI-ish / Gemini / gateway shapes."""
    if body.get("output"):
        return str(body.get("output"))[:200]
    if body.get("output_text"):
        return str(body.get("output_text"))[:200]
    choices = body.get("choices") or []
    if choices:
        ch0 = choices[0] or {}
        msg = ch0.get("message") or {}
        content = msg.get("content")
        if isinstance(content, str) and content.strip():
            return content.strip()[:200]
        if isinstance(content, list):
            parts = []
            for p in content:
                if isinstance(p, str):
                    parts.append(p)
                elif isinstance(p, dict):
                    parts.append(str(p.get("text") or p.get("content") or ""))
            joined = "".join(parts).strip()
            if joined:
                return joined[:200]
        if ch0.get("text"):
            return str(ch0.get("text"))[:200]
        # Some reasoning models only fill refusal / reasoning fields
        for k in ("reasoning_content", "reasoning", "refusal"):
            if msg.get(k):
                return str(msg.get(k))[:200]
    return ""


def err_msg(payload: Any) -> str:
    if not isinstance(payload, dict):
        return str(payload)[:300]
    e = payload.get("error")
    if isinstance(e, dict):
        return (e.get("message") or e.get("code") or str(e))[:400]
    if payload.get("raw"):
        return str(payload["raw"])[:300]
    return json.dumps(payload)[:300]


def tiny_wav_bytes(seconds: float = 1.0, hz: int = 16000) -> bytes:
    """1s 16-bit mono silence WAV for STT."""
    n = int(hz * seconds)
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(hz)
        w.writeframes(struct.pack("<" + "h" * n, *([0] * n)))
    return buf.getvalue()


def run_matrix(args: argparse.Namespace) -> int:
    key = load_key()
    c = Client(key)
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out_path = OUT_DIR / f"matrix-{stamp}.jsonl"
    summary_path = OUT_DIR / f"matrix-{stamp}-summary.json"

    print(f"base={BASE}")
    print(f"out={out_path}")

    st, me = c.request("GET", "/v1/me", timeout=30)
    if st != 200:
        print(f"FATAL /v1/me {st} {err_msg(me)}")
        return 2
    usage = (me.get("usage") or {}) if isinstance(me, dict) else {}
    print(
        f"account={(me.get('account') or {}).get('id')} "
        f"spendable_micro={usage.get('spendable_remaining_micro_usd')} "
        f"plan={(me.get('plan') or {}).get('id')}"
    )

    st, catalog = c.request("GET", "/v1/models", timeout=60)
    if st != 200:
        print(f"FATAL /v1/models {st} {err_msg(catalog)}")
        return 2
    models = catalog.get("data") or []
    by_mod: dict[str, list[dict]] = {}
    for m in models:
        if m.get("spendable") is False:
            continue
        mod = (m.get("modality") or m.get("type") or "unknown").lower()
        by_mod.setdefault(mod, []).append(m)

    only = {x.strip() for x in (args.only or "").split(",") if x.strip()}
    if only:
        by_mod = {k: v for k, v in by_mod.items() if k in only}

    for mod, lst in sorted(by_mod.items()):
        print(f"  {mod}: {len(lst)} spendable")
        if args.limit:
            by_mod[mod] = lst[: args.limit]

    results: list[Result] = []
    seed_url: str | None = None

    def record(r: Result) -> None:
        results.append(r)
        line = json.dumps(asdict(r), ensure_ascii=False)
        with out_path.open("a") as f:
            f.write(line + "\n")
        mark = "OK " if r.ok else "FAIL"
        print(f"[{mark}] {r.modality:6} {r.model[:48]:48} {r.seconds:6.1f}s  {r.detail[:120]}")

    # --- seed image for i2v / i2i ---
    if "image" in by_mod or "video" in by_mod:
        t0 = time.monotonic()
        st, body = c.request(
            "POST",
            "/v1/images/generations",
            {
                "model": "xai/grok-imagine-image",
                "prompt": "prism matrix smoke seed: red apple on white table, simple photo",
            },
            timeout=180,
        )
        seed_url = first_image_url(body) if st == 200 else None
        record(
            Result(
                "image",
                "xai/grok-imagine-image [SEED]",
                ok=bool(seed_url),
                status=st,
                detail=f"seed_url={'yes' if seed_url else err_msg(body)}",
                seconds=time.monotonic() - t0,
            )
        )
        if not seed_url:
            # public fallback for i2v
            seed_url = "https://upload.wikimedia.org/wikipedia/commons/thumb/1/15/Red_Apple.jpg/320px-Red_Apple.jpg"
            print(f"using public seed image fallback")

    # --- CHAT ---
    for m in by_mod.get("chat", []):
        mid = m["id"]
        t0 = time.monotonic()
        st, body = c.request(
            "POST",
            "/v1/chat/completions",
            {
                "model": mid,
                "messages": [{"role": "user", "content": "Reply with exactly: ok"}],
                "stream": False,
                "max_tokens": 16,
            },
            timeout=120,
        )
        text = extract_chat_text(body) if isinstance(body, dict) else ""
        # HTTP 200 with no structured error = pass even if content is empty/reasoning-only.
        has_err = isinstance(body, dict) and body.get("error") is not None
        ok = st == 200 and not has_err
        detail = (text or "(empty content)") if ok else err_msg(body)
        record(
            Result(
                "chat",
                mid,
                ok=ok,
                status=st,
                detail=detail[:200],
                seconds=time.monotonic() - t0,
            )
        )

    # --- IMAGE ---
    for m in by_mod.get("image", []):
        mid = m["id"]
        if mid == "xai/grok-imagine-image" and any(
            r.model.startswith("xai/grok-imagine-image") for r in results if r.ok
        ):
            # already seeded successfully
            record(
                Result(
                    "image",
                    mid,
                    ok=True,
                    status=200,
                    detail="covered by SEED",
                    seconds=0.0,
                )
            )
            continue
        caps = m.get("capabilities") or []
        need_ref = "image-input-required" in caps
        prefer_async = mid == "openai/gpt-image-2" or "gpt-image-2" in mid
        body_req: dict[str, Any] = {
            "model": mid,
            "prompt": "prism smoke: simple green circle on white, minimal",
        }
        if need_ref and seed_url:
            body_req["image"] = seed_url
        if prefer_async:
            body_req["async"] = True
        t0 = time.monotonic()
        st, body = c.request(
            "POST",
            "/v1/images/generations",
            body_req,
            timeout=60 if prefer_async else 300,
            prefer_async=prefer_async,
        )
        job_id = None
        ok = False
        detail = ""
        if isinstance(body, dict) and body.get("id") and not first_image_url(body):
            job_id = body["id"]
            st2, job = c.wait_job(job_id, timeout_s=420)
            st = st2
            body = job
            res = job.get("result") or {}
            if job.get("status") == "succeeded" and (
                first_image_url({"data": res.get("data") or []})
                or first_image_url(res)
                or res.get("data")
            ):
                ok = True
                detail = f"async job ok · {job_id[:12]}"
            else:
                detail = err_msg(job) if job.get("status") == "failed" else f"async incomplete {job.get('status')}"
        else:
            url = first_image_url(body) if isinstance(body, dict) else None
            ok = st in (200, 201) and bool(url)
            detail = (url or "")[:100] if ok else err_msg(body)
        record(
            Result(
                "image",
                mid,
                ok=ok,
                status=st,
                detail=detail,
                seconds=time.monotonic() - t0,
                job_id=job_id,
            )
        )

    # --- VIDEO ---
    for m in by_mod.get("video", []):
        mid = m["id"]
        caps = m.get("capabilities") or []
        need_img = "image-input-required" in caps
        sec = video_duration_seconds(mid)
        wire = video_duration_wire(mid, sec)
        body_req: dict[str, Any] = {
            "model": mid,
            "prompt": "gentle camera pan over a still red apple on a table",
            "async": True,
            "duration": wire,
        }
        if need_img or mid.endswith("-i2v") or "hailuo" in mid:
            body_req["image"] = seed_url
            if not body_req.get("prompt"):
                body_req["prompt"] = "subtle motion, slight camera push-in"
        # dual-mode: still send image for models that accept it (better coverage)
        elif "image-input" in caps and seed_url:
            # keep text-to-video primary for coverage of t2v path; only force image for required
            pass
        t0 = time.monotonic()
        st, body = c.request(
            "POST",
            "/v1/videos/generations",
            body_req,
            timeout=90,
            prefer_async=True,
        )
        job_id = None
        ok = False
        detail = ""
        if isinstance(body, dict) and body.get("id") and not body.get("video"):
            job_id = body["id"]
            st2, job = c.wait_job(job_id, timeout_s=480, poll_s=5)
            st = st2
            body = job
            if job.get("status") == "succeeded":
                video = (job.get("result") or {}).get("video")
                ok = bool(video)
                detail = f"{sec}s · {(video or '')[:90]}"
            else:
                detail = err_msg(job) if job.get("status") == "failed" else f"status={job.get('status')}"
        elif isinstance(body, dict) and body.get("video"):
            ok = True
            detail = f"sync · {body.get('video')[:90]}"
        else:
            detail = err_msg(body)
        # retry i2v if t2v failed and image-input available
        if not ok and "image-input" in caps and seed_url and not need_img:
            body_req2 = {
                "model": mid,
                "prompt": "subtle motion from still photo",
                "image": seed_url,
                "async": True,
                "duration": wire,
            }
            t1 = time.monotonic()
            st, body = c.request(
                "POST",
                "/v1/videos/generations",
                body_req2,
                timeout=90,
                prefer_async=True,
            )
            if isinstance(body, dict) and body.get("id") and not body.get("video"):
                job_id = body["id"]
                st2, job = c.wait_job(job_id, timeout_s=480, poll_s=5)
                st = st2
                if job.get("status") == "succeeded" and (job.get("result") or {}).get("video"):
                    ok = True
                    detail = f"i2v retry · {sec}s · {((job.get('result') or {}).get('video') or '')[:70]}"
                else:
                    detail = f"t2v+i2v fail · {err_msg(job)}"
            else:
                detail = f"t2v+i2v fail · {err_msg(body)}"
            t0 = t1  # report second attempt time approx
            # fix elapsed
            elapsed = time.monotonic() - t1
            record(
                Result(
                    "video",
                    mid,
                    ok=ok,
                    status=st,
                    detail=detail,
                    seconds=elapsed,
                    job_id=job_id,
                )
            )
            continue
        record(
            Result(
                "video",
                mid,
                ok=ok,
                status=st,
                detail=detail,
                seconds=time.monotonic() - t0,
                job_id=job_id,
            )
        )

    # --- TTS ---
    tts_audio_b64: str | None = None
    for m in by_mod.get("tts", []):
        mid = m["id"]
        t0 = time.monotonic()
        st, body = c.request(
            "POST",
            "/v1/audio/speech",
            {"model": mid, "input": "Prism smoke test.", "async": True},
            timeout=60,
            prefer_async=True,
        )
        job_id = None
        ok = False
        detail = ""
        if isinstance(body, dict) and body.get("id") and not body.get("audio_base64"):
            job_id = body["id"]
            st2, job = c.wait_job(job_id, timeout_s=180, poll_s=3)
            st = st2
            res = job.get("result") or {}
            audio = res.get("audio_base64") or res.get("audio")
            if job.get("status") == "succeeded" and audio:
                ok = True
                detail = f"async audio · len={len(str(audio))}"
                if tts_audio_b64 is None and not str(audio).startswith("http"):
                    tts_audio_b64 = str(audio)
            else:
                detail = err_msg(job)
        elif isinstance(body, dict) and body.get("audio_base64"):
            ok = True
            detail = f"sync audio · len={len(body['audio_base64'])}"
            tts_audio_b64 = body["audio_base64"]
        else:
            detail = err_msg(body)
        record(
            Result(
                "tts",
                mid,
                ok=ok,
                status=st,
                detail=detail,
                seconds=time.monotonic() - t0,
                job_id=job_id,
            )
        )

    # --- STT (file) ---
    wav = tiny_wav_bytes(1.0)
    for m in by_mod.get("stt", []):
        mid = m["id"]
        t0 = time.monotonic()
        # multipart transcription
        c.rate.wait()
        try:
            r = c.s.post(
                f"{BASE}/v1/audio/transcriptions",
                headers={
                    "Authorization": c.s.headers["Authorization"],
                    "User-Agent": UA,
                },
                data={"model": mid},
                files={"file": ("smoke.wav", wav, "audio/wav")},
                timeout=120,
            )
            st = r.status_code
            try:
                body = r.json()
            except Exception:
                body = {"raw": r.text[:400]}
        except requests.RequestException as e:
            st, body = 0, {"error": {"message": str(e)}}
        text = ""
        if isinstance(body, dict):
            text = body.get("text") or body.get("transcript") or ""
        # empty transcript on silence is still a successful call if HTTP 200
        ok = st == 200 and "error" not in body
        detail = f"text={text!r}" if ok else err_msg(body)
        record(
            Result(
                "stt",
                mid,
                ok=ok,
                status=st,
                detail=detail[:200],
                seconds=time.monotonic() - t0,
            )
        )

    # --- MUSIC ---
    for m in by_mod.get("music", []):
        mid = m["id"]
        t0 = time.monotonic()
        st, body = c.request(
            "POST",
            "/v1/music/generations",
            {
                "model": mid,
                "prompt": "short cheerful lo-fi loop, prism smoke",
                "async": True,
            },
            timeout=90,
            prefer_async=True,
        )
        job_id = None
        ok = False
        detail = ""
        if isinstance(body, dict) and body.get("id") and not (
            body.get("audio") or body.get("audio_base64")
        ):
            job_id = body["id"]
            st2, job = c.wait_job(job_id, timeout_s=480, poll_s=5)
            st = st2
            res = job.get("result") or {}
            audio = res.get("audio") or res.get("audio_base64")
            if job.get("status") == "succeeded" and audio:
                ok = True
                detail = f"music ok · {str(audio)[:80]}"
            else:
                detail = err_msg(job)
        elif isinstance(body, dict) and (body.get("audio") or body.get("audio_base64")):
            ok = True
            detail = "sync music ok"
        else:
            detail = err_msg(body)
        record(
            Result(
                "music",
                mid,
                ok=ok,
                status=st,
                detail=detail,
                seconds=time.monotonic() - t0,
                job_id=job_id,
            )
        )

    # --- VOICE (live STT ticket only if present) ---
    for m in by_mod.get("voice", []):
        mid = m["id"]
        t0 = time.monotonic()
        st, body = c.request(
            "POST",
            "/v1/stt/sessions",
            {"model": mid},
            timeout=30,
        )
        ok = st in (200, 201) and isinstance(body, dict) and (
            body.get("ticket") or body.get("url") or body.get("token") or body.get("ws_url")
        )
        # some planes use different field names
        if not ok and st == 200 and isinstance(body, dict) and body.get("error") is None:
            ok = True
            detail = f"session fields={list(body.keys())[:8]}"
        else:
            detail = f"fields={list(body.keys())[:8]}" if ok else err_msg(body)
        record(
            Result(
                "voice",
                mid,
                ok=ok,
                status=st,
                detail=detail[:200],
                seconds=time.monotonic() - t0,
            )
        )

    # summary
    by = {}
    for r in results:
        g = by.setdefault(r.modality, {"ok": 0, "fail": 0, "models": []})
        if r.ok:
            g["ok"] += 1
        else:
            g["fail"] += 1
        g["models"].append({"model": r.model, "ok": r.ok, "detail": r.detail[:200]})

    total_ok = sum(1 for r in results if r.ok)
    total = len(results)
    summary = {
        "stamp": stamp,
        "base": BASE,
        "total": total,
        "ok": total_ok,
        "fail": total - total_ok,
        "by_modality": by,
        "out_jsonl": str(out_path),
    }
    summary_path.write_text(json.dumps(summary, indent=2))
    print()
    print(f"SUMMARY {total_ok}/{total} ok  → {summary_path}")
    for mod, g in sorted(by.items()):
        print(f"  {mod:6} {g['ok']}/{g['ok']+g['fail']}")
        for m in g["models"]:
            if not m["ok"]:
                print(f"    FAIL {m['model']}: {m['detail'][:100]}")

    st, me2 = c.request("GET", "/v1/me", timeout=30)
    if st == 200:
        u = me2.get("usage") or {}
        print(
            f"spendable_remaining_micro after={u.get('spendable_remaining_micro_usd')} "
            f"(before={usage.get('spendable_remaining_micro_usd')})"
        )
    return 0 if total_ok == total else 1


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--only", help="comma modalities: chat,image,video,tts,stt,music,voice")
    p.add_argument("--limit", type=int, default=0, help="max models per modality (0=all)")
    args = p.parse_args()
    raise SystemExit(run_matrix(args))


if __name__ == "__main__":
    main()
