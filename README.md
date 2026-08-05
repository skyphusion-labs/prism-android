# prism-android

**License:** AGPL-3.0-only  
**API (metered inference):** [prism-control-plane](https://github.com/skyphusion-labs/prism-control-plane)  
**Playground (history/RAG/etc.):** [prism](https://github.com/skyphusion-labs/prism)  
**Sibling:** [prism-ios](https://github.com/skyphusion-labs/prism-ios)

## What this is

AGPL **Android client kit** for Prism: metered chat (and later multimodal + quota UX)
against the commercial control plane. Goal is easier access to a curated model set with
cost-recovery hosting, not a closed app.

## Layout

- `prism-kit` -- Kotlin JVM library (OkHttp + kotlinx.serialization)
  - `ControlPlaneClient` -- `pcp_` bearer enroll / me / models / chat / stream
  - Contract: control-plane `docs/CONTRACT.md` + `openapi.yaml`
- App module still to be added (Compose UI)

## Status

**Kit v0.1.0:** control-plane HTTP client with unit tests (MockWebServer). No app UI yet.

## Commands

```bash
./gradlew :prism-kit:test --no-daemon
```

## Control plane usage (kit)

```kotlin
val client = ControlPlaneClient() // https://play-proxy.skyphusion.org
// After out-of-band enrollment token:
client.enroll(enrollmentToken = "…", label = "Pixel 9")
// Persist client.clientKey in EncryptedSharedPreferences / Keystore.

val models = client.listModels().data.filter { it.spendable != false }
val reply = client.chat(model = models.first().id, user = "Hello")
```

Streaming:

```kotlin
client.chatCompletionsStream(
  ControlPlaneChatRequest(
    model = id,
    messages = listOf(ControlPlaneChatMessage("user", "Hi")),
    stream = true,
  ),
).collect { event -> /* ChatStreamEvent.Delta / Done */ }
```

## Related

- Live proxy: https://play-proxy.skyphusion.org  
- Playground: https://play.skyphusion.org  
- iOS kit: https://github.com/skyphusion-labs/prism-ios  
