# CLAUDE.md -- prism-android

Guidance for agents working in this repository.

## What this is

**AGPL Android client for Prism.** Metered inference against
[prism-control-plane](https://github.com/skyphusion-labs/prism-control-plane). History / RAG /
artifacts remain on [prism](https://github.com/skyphusion-labs/prism) (developer playground only
in the app).

**v1.0.0:** iOS 1.0.0 parity -- async media jobs, video duration picker, Use in chat / Animate /
inline text attach, Play Billing, biometric, Usage, live STT. Docs: `docs/ARCHITECTURE.md`,
`docs/MODELS.md`, `docs/RELEASE-1.0.md`, `docs/PLAY-INTERNAL.md`.

## Related

| Repo | Role |
| --- | --- |
| [prism-control-plane](https://github.com/skyphusion-labs/prism-control-plane) | Metered inference -- primary target |
| [prism](https://github.com/skyphusion-labs/prism) | Playground Worker |
| [prism-ios](https://github.com/skyphusion-labs/prism-ios) | Sibling Swift kit + shell |
| [prism-mcp](https://github.com/skyphusion-labs/prism-mcp) | Agent MCP door |

## Layout

- `prism-kit` -- JVM (OkHttp, kotlinx.serialization, coroutines)
  - `ControlPlaneClient` (chat SSE, image, video, speech, STT, music, jobs, redeem)
  - `PrismClient` (playground auth, models, chat/stream, compact)
  - `VideoClipDuration`, `ConversationCompact`, `HttpJson`, `SseParser`, `Models`, `SecretStore`
- `app` -- Android application (Compose Material3)
  - enroll / Chat·Image·Video·More / sessions / settings / BillingManager

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
- `client_revoked` / 401: clear stored key, return to enroll
- Never log device keys or enrollment tokens
- Never a plaintext secret in a tracked file

## Conventions

- Conventional Commits; AGPL-3.0-only
- No em-dashes / en-dashes in prose
- Prefer parity with iOS PrismKit + App shell

## Release / deploy

**Tag-gated production narrative.** Merge feature PR to `main`, then annotated SemVer tag
`vX.Y.Z` for GitHub Release. Play Internal: signed AAB via `bundleRelease` + Console upload
(`docs/PLAY-INTERNAL.md`, `docs/RELEASE-1.0.md`).
