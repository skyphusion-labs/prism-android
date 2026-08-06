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

**v0.3.1** -- plane media + Play Billing + iOS 0.6.x polish:

- Chat + stream; Image / Video tabs
- Spend preview (unit/token catalog rate), media elapsed timer, session media history (cap 20)
- Chat cancel + retry last failed message; video retry same prompt; model switch keeps chat context
- Play Billing consumables → `POST /v1/store/redeem` (`platform=google_play`; plane **0.4.16+**)
- Model pickers: spendable filter, Grok video last, Veo / Seedance Fast defaults

Create the three in-app products in Play Console (same product ids as App Store). Production
redeem needs Worker secret `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` on the plane.

Grok video works when the plane is **0.4.14+**. Prefer Veo / Seedance Fast if you hit 7003.

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
