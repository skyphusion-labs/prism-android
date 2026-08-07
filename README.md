# prism-android

**License:** [AGPL-3.0-only](LICENSE)  
**Version:** 1.0.0  
**Privacy:** https://skyphusion.org/privacy.html  
**Source:** https://github.com/skyphusion-labs/prism-android  
**Metered API:** [prism-control-plane](https://github.com/skyphusion-labs/prism-control-plane)  
**Playground Worker:** [prism](https://github.com/skyphusion-labs/prism)  
**Sibling:** [prism-ios](https://github.com/skyphusion-labs/prism-ios)

## What this is

AGPL **Android client** for Prism. Primary path is the commercial control plane at
`play-proxy.skyphusion.org` (device key `pcp_…` in EncryptedSharedPreferences). Optional
developer mode talks to the playground Worker for chat history; media doors stay plane-only.

## Documentation

| Doc | Contents |
|-----|----------|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | How Android fits the plane (Mermaid flowcharts) |
| [docs/MODELS.md](docs/MODELS.md) | Full live catalog snapshot (chat / image / video / …) |
| [docs/PLAY-INTERNAL.md](docs/PLAY-INTERNAL.md) | Play Console Internal testing beta |
| [docs/RELEASE-1.0.md](docs/RELEASE-1.0.md) | 1.0.0 cut notes, AAB, smoke |

## Layout

| Module | Role |
|--------|------|
| `prism-kit` | JVM: `ControlPlaneClient`, `PrismClient`, `VideoClipDuration`, `StoreProducts` |
| `app` | Compose Material3: enroll, Chat / Image / Video / More, billing, biometric |

## Status (1.0.0)

Parity with **Prism for iOS 1.0.0**:

- Async plane jobs (video, music, speech, gpt-image-2) + forceSync on resume
- Video clip length picker (per-model CF limits)
- Use in chat / Animate handoffs; inline text-file attach (not RAG)
- Play Billing credit packs; Usage dual-pool; live + file STT; TTS/music Play/Stop
- Full catalog matrix smoke: image/video green; MeloTTS CF flaky (prefer Aura-2)

## App flow

1. Import a `pcp_` device key (or Advanced enroll with a one-time token).
2. Tabs: **Chat · Image · Video · More** (Audio, Music, Usage, Settings).
3. Top up in Settings (Play Internal track for real IAP).
4. Optional: biometric lock; home-screen balance widget.

## Commands

```bash
export JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"
./gradlew :prism-kit:test
./gradlew :app:assembleDebug
./gradlew :app:bundleRelease   # needs keystore.properties (gitignored)

# Full live model matrix (needs pcp_ key file)
python3 scripts/full-model-matrix-smoke.py
```

## Kit usage

```kotlin
val client = ControlPlaneClient()
client.setClientKey("pcp_…") // or enroll(...)

val models = client.listModels().data.filter { it.spendable != false }
val reply = client.chat(model = models.first { it.modality == "chat" }.id, user = "Hello")
val img = client.generateImage(model = "xai/grok-imagine-image", prompt = "a red cube")
val vid = client.generateVideo(
  model = "google/veo-3.1-fast",
  prompt = "ocean waves",
  durationSeconds = 8,
)
```

## Related

- Live proxy: https://play-proxy.skyphusion.org  
- Playground: https://play.skyphusion.org  
- Plane contract: [docs/CONTRACT.md](https://github.com/skyphusion-labs/prism-control-plane/blob/main/docs/CONTRACT.md)  
- Support: support@skyphusion.org  
