# Prism for Android 1.0.0 release

**App name:** Prism (launcher) / Prism for Android  
**Version:** 1.0.0 (`versionCode` 19)  
**Application id:** `org.skyphusion.prism`  
**License:** AGPL-3.0-only  
**Privacy:** https://skyphusion.org/privacy.html  
**Plane:** play-proxy `prism-control-plane` **0.4.36+** recommended (async jobs, store redeem, Grok video)

Sibling product: [Prism for iOS 1.0.0](https://github.com/skyphusion-labs/prism-ios).

## What ships

- Control-plane client: chat (stream + non-stream), image, video, music, TTS, STT (file + live)
- Async Workflow jobs: video, music, speech, gpt-image-2 (Prefer + poll + forceSync)
- Video clip length picker (per-model CF limits)
- Cross-modal handoffs: Use in chat, Animate, inline text files (not RAG)
- Credit top-up: Play Billing packs → `POST /v1/store/redeem` (`platform=google_play`)
- Biometric lock, Usage dual-pool, balance home widget, legal links

## IAP (consumables)

Same product ids as iOS / plane `store-products.ts`:

| Product ID | USD credit |
|------------|------------|
| `org.skyphusion.prism.credit.5` | 5 |
| `org.skyphusion.prism.credit.20` | 20 |
| `org.skyphusion.prism.credit.50` | 50 |

Plane secret: `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` for server verify.

## Smoke (2026-08-07)

Full live catalog matrix via `scripts/full-model-matrix-smoke.py` against play-proxy:

| Modality | Result |
|----------|--------|
| chat | 41/44 first pass; 3/3 re-smoke after max_tokens fix |
| image | 22/22 |
| video | 19/19 |
| music | 1/1 |
| voice (STT session) | 1/1 |
| tts | 2/3 (MeloTTS CF AiError 3043 upstream) |
| stt | 4/4 after JSON body fix |

See [MODELS.md](MODELS.md) for the catalog snapshot.

## Artifacts

| Artifact | Path / location |
|----------|-----------------|
| Git tag | `v1.0.0` on `main` |
| GitHub Release | github.com/skyphusion-labs/prism-android/releases/tag/v1.0.0 |
| Play AAB | `app/build/outputs/bundle/release/app-release.aab` (signed upload key) |
| Play track | **Internal testing** (see [PLAY-INTERNAL.md](PLAY-INTERNAL.md)) |

## Build signed AAB

```bash
cd ~/dev/prism-android
export JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"
# keystore.properties at repo root (gitignored) → prism-upload.jks
./gradlew :app:bundleRelease
ls -la app/build/outputs/bundle/release/app-release.aab
```

## Play Internal checklist

1. Play Console → app `org.skyphusion.prism` → **Testing → Internal testing**
2. **Create new release** → upload AAB → notes `1.0.0 iOS parity: async media, handoffs, duration picker`
3. **Start rollout to Internal testing**
4. Testers open opt-in link once, then install from Play
5. Smoke: enroll `pcp_`, chat, image, video, usage, top-up (sandbox if needed)

## Support

- Health: `curl -sS https://play-proxy.skyphusion.org/health`
- Email: support@skyphusion.org
- Architecture: [ARCHITECTURE.md](ARCHITECTURE.md)
