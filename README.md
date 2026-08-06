# prism-android

**License:** AGPL-3.0-only  
**API (metered inference):** [prism-control-plane](https://github.com/skyphusion-labs/prism-control-plane)  
**Playground (history/RAG/etc.):** [prism](https://github.com/skyphusion-labs/prism)  
**Sibling:** [prism-ios](https://github.com/skyphusion-labs/prism-ios)

## What this is

AGPL **Android client** for Prism against the commercial control plane
(`play-proxy.skyphusion.org`). Device keys stay in EncryptedSharedPreferences
(Android Keystore).

## Layout

| Module | Role |
|--------|------|
| `prism-kit` | JVM library: `ControlPlaneClient` (chat SSE, image, video), `SecretStore` |
| `app` | Compose shell: enroll, **Chat / Image / Video** tabs, settings |

## Status

**v0.2.0** -- plane feature parity with iOS media tabs (not playground dual-backend / StoreKit):

- Chat + stream
- Image generation (`POST /v1/images/generations`) with optional i2i ref
- Video generation (`POST /v1/videos/generations`) with optional i2v ref
- Model pickers respect spendable + sort Grok video last; prefer Veo / Seedance Fast defaults
- User-facing error map (402 balance, 7003, ZDR / plane 0.4.14+)

Grok video works when the plane is **0.4.14+** (ZDR `output.upload_url`). Prefer Veo / Seedance Fast if you hit 7003 on older deploys.

## Commands

```bash
./gradlew :prism-kit:test --no-daemon
./gradlew :app:assembleDebug --no-daemon
```

## App flow

1. **Import** a `pcp_` key (or Advanced enroll with a one-time token).
2. **Chat / Image / Video** bottom tabs after enroll.
3. **Settings** shows balance and can forget the device key.

## Kit usage

```kotlin
val client = ControlPlaneClient()
client.setClientKey("pcp_…") // or enroll(...)

val models = client.listModels().data.filter { it.spendable != false }
val reply = client.chat(model = models.first { it.modality == "chat" }.id, user = "Hello")
val img = client.generateImage(model = "xai/grok-imagine-image", prompt = "a red cube")
val vid = client.generateVideo(model = "google/veo-3.1-fast", prompt = "ocean waves")
```

## Related

- Live proxy: https://play-proxy.skyphusion.org  
- Playground: https://play.skyphusion.org  
- iOS: https://github.com/skyphusion-labs/prism-ios  
- Contract: [docs/CONTRACT.md](https://github.com/skyphusion-labs/prism-control-plane/blob/main/docs/CONTRACT.md)
