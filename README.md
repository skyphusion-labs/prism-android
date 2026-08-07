# prism-android

**License:** [AGPL-3.0-only](LICENSE)  
**Privacy:** https://skyphusion.org/privacy.html  
**Source:** this repo (`https://github.com/skyphusion-labs/prism-android`)  
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
| `prism-kit` | JVM: `ControlPlaneClient`, `PrismClient` (playground compact), `SecretStore` |
| `app` | Compose shell: enroll, **Chat / Image / Video** tabs, settings |

## Status

**v0.9.0** -- iOS 0.8.6–0.8.8 media fixes: plane **async jobs** for video/music/speech
(poll + persist + force-sync on foreground), honest long-gen copy, 420s budgets,
Play/Stop for music/TTS, Grok media-ready probe. Prior **0.8.1**: legal links.

**Beta distribution (non-Android-native friendly):** [docs/PLAY-INTERNAL.md](docs/PLAY-INTERNAL.md)

Create the three in-app products in Play Console (same product ids as App Store). Production
redeem needs Worker secret `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` on the plane.

Grok video works when the plane is **0.4.14+**. Prefer Seedance Fast / Veo if you hit 7003.

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

// Playground Worker compact (session cookie or Access headers)
val play = PrismClient.create()
play.restoreSessionToken(sessionToken)
play.compactConversation(id = "conv_…", keepRecent = 2, model = "…")
play.clearConversationCompact(id = "conv_…")
```

## Related

- Live proxy: https://play-proxy.skyphusion.org  
- Playground: https://play.skyphusion.org  
- iOS: https://github.com/skyphusion-labs/prism-ios  
- Contract: [docs/CONTRACT.md](https://github.com/skyphusion-labs/prism-control-plane/blob/main/docs/CONTRACT.md)
