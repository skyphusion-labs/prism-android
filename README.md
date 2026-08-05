# prism-android

**License:** AGPL-3.0-only  
**API (metered inference):** [prism-control-plane](https://github.com/skyphusion-labs/prism-control-plane)  
**Playground (history/RAG/etc.):** [prism](https://github.com/skyphusion-labs/prism)  
**Sibling:** [prism-ios](https://github.com/skyphusion-labs/prism-ios)

## What this is

AGPL **Android client** for Prism: enroll a device, pick a spendable model, and chat
against the commercial control plane (`play-proxy.skyphusion.org`). Device keys stay
in EncryptedSharedPreferences (Android Keystore).

## Layout

| Module | Role |
|--------|------|
| `prism-kit` | JVM library: `ControlPlaneClient`, SSE, `SecretStore` |
| `app` | Compose shell: enroll, model picker, chat, settings |

## Status

**v0.1.0 app shell** on top of the kit. Control-plane only (no playground session/RAG yet).

## Commands

```bash
# Kit unit tests (no Android SDK)
./gradlew :prism-kit:test --no-daemon

# Debug APK (needs Android SDK + local.properties sdk.dir or ANDROID_HOME)
./gradlew :app:assembleDebug --no-daemon
```

## App flow

1. **Enroll** with a one-time enrollment token (or import an existing `pcp_` key).
2. **Models** load from `GET /v1/models`; unspendable entries are greyed out.
3. **Chat** uses non-stream or SSE stream (`POST /v1/chat/completions`).
4. **Settings** shows balance and can forget the device key.

## Kit usage

```kotlin
val client = ControlPlaneClient()
client.enroll(enrollmentToken = "…", label = "Pixel 9")
// Persist client.clientKey via SecretStore

val models = client.listModels().data.filter { it.spendable != false }
val reply = client.chat(model = models.first().id, user = "Hello")
```

## Related

- Live proxy: https://play-proxy.skyphusion.org  
- Playground: https://play.skyphusion.org  
- iOS: https://github.com/skyphusion-labs/prism-ios  
