# CLAUDE.md -- prism-android

Guidance for agents working in this repository.

## What this is

**AGPL Android client for Prism**, branded **Prism for Android**. Metered inference against
[prism-control-plane](https://github.com/skyphusion-labs/prism-control-plane) (`play-proxy`). History /
RAG / artifacts remain on [prism](https://github.com/skyphusion-labs/prism) (developer playground mode
only in the app).

**Status: v1.0.0** (tag `v1.0.0` on `main`; `versionName` 1.0.0 / `versionCode` 19;
`applicationId` **`org.skyphusion.prism`**). iOS 1.0.0 product parity. Plane **1.0.0**. GitHub Release
ships signed `app-release.aab`. Play Internal: `docs/PLAY-INTERNAL.md`. README mirrors prism-ios
(Mermaid + full catalog). Aviation-grade `main`.

## Related

| Repo | Role |
| --- | --- |
| [prism-control-plane](https://github.com/skyphusion-labs/prism-control-plane) | Metered inference -- primary target **1.0.0** |
| [prism](https://github.com/skyphusion-labs/prism) | Playground Worker **1.0.0** |
| [prism-ios](https://github.com/skyphusion-labs/prism-ios) | Sibling Swift kit + shell **1.0.0** |
| [prism-mcp](https://github.com/skyphusion-labs/prism-mcp) | Agent MCP door **1.0.0** |

## Layout

- `prism-kit` -- JVM (OkHttp, kotlinx.serialization, coroutines)
  - `ControlPlaneClient` (chat SSE, image, video, speech, STT, music, jobs, redeem)
  - `PrismClient` (playground auth, models, chat/stream, compact)
  - `VideoClipDuration`, `ConversationCompact`, `HttpJson`, `SseParser`, `Models`, `SecretStore`
- `app` -- Android application (Compose Material3)
  - enroll / Chat·Image·Video·More / sessions / settings / BillingManager
- `docs/` -- ARCHITECTURE, MODELS, PLAY-INTERNAL, RELEASE-1.0
- `scripts/` -- `full-model-matrix-smoke.py`, `re-smoke-failures.py`

## 1.0 product surface

- Async plane jobs (video, music, speech, gpt-image-2) + Prefer + poll + forceSync on resume
- Video clip length picker (per-model CF limits; kit `VideoClipDuration`)
- Use in chat / Animate handoffs; inline text-file attach (not RAG)
- Play Billing credit packs → `POST /v1/store/redeem` (`platform=google_play`)
- Biometric lock, Usage dual-pool, live + file STT, TTS/music Play/Stop, balance widget

## Commands

```bash
export JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"
./gradlew :prism-kit:test --no-daemon
./gradlew :app:assembleDebug --no-daemon
./gradlew :app:bundleRelease --no-daemon   # keystore.properties gitignored
python3 scripts/full-model-matrix-smoke.py
```

## CI

- **kit** job: unit tests, no Android SDK
- **app** job: `setup-android` + `:app:assembleDebug`
- Public repo; GitHub-hosted only

## Contract rules

- Auth: `Authorization: Bearer pcp_<key_id>_<secret>` only
- Branch on `spendable` for model picker
- STT body is JSON `{ model, audio }` (data URL or base64), not multipart
- Package name for Play redeem: `org.skyphusion.prism` (plane `ANDROID_PACKAGE_NAME`)
- `client_revoked` / 401: clear stored key, return to enroll
- Never log device keys or enrollment tokens
- Never a plaintext secret in a tracked file

## Conventions

- Conventional Commits; AGPL-3.0-only
- No em-dashes / en-dashes in prose
- Prefer parity with iOS PrismKit + App shell
- README shape matches prism-ios

## Release / deploy

Merge feature PR to `main`, annotated SemVer tag `vX.Y.Z` for GitHub Release (+ AAB asset).
Play Internal: signed AAB via `bundleRelease` + Console upload (`docs/PLAY-INTERNAL.md`,
`docs/RELEASE-1.0.md`).

## Crew + identity

Conrad laptop commits: `Conrad Rockenhaus <conrad@skyphusion.org>`. Crew on dischord: member
identity via `sudo -u <member>`.
