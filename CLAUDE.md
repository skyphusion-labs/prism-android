# CLAUDE.md -- prism-android

Guidance for agents working in this repository.

## What this is

**AGPL Android client for Prism.** Metered inference against
[prism-control-plane](https://github.com/skyphusion-labs/prism-control-plane). History / RAG /
artifacts remain on [prism](https://github.com/skyphusion-labs/prism) (not yet wired in the app).

**v0.3.3:** iOS 0.6.3 parity: UI prefs persist, Thinking indicator, share transcript, image retry,
About links, brand accent. Grok video **0.4.14+**; Play redeem **0.4.16+**.

## Related

| Repo | Role |
| --- | --- |
| [prism-control-plane](https://github.com/skyphusion-labs/prism-control-plane) | Metered inference -- primary target |
| [prism](https://github.com/skyphusion-labs/prism) | Playground Worker |
| [prism-ios](https://github.com/skyphusion-labs/prism-ios) | Sibling Swift kit + shell (feature lead) |
| [prism-mcp](https://github.com/skyphusion-labs/prism-mcp) | Agent MCP door |

## Layout

- `prism-kit` -- JVM (OkHttp, kotlinx.serialization, coroutines)
  - `ControlPlaneClient` (chat SSE, image, video), `HttpJson`, `SseParser`, `Models`, `SecretStore`
- `app` -- Android application (Compose Material3)
  - `EncryptedPrefsSecretStore`, `AppViewModel`, enroll / PlaneShell tabs / media / settings

## Commands

```bash
./gradlew :prism-kit:test --no-daemon
./gradlew :app:assembleDebug --no-daemon   # needs Android SDK
```

## CI

- **kit** job: unit tests, no Android SDK
- **app** job: `setup-android` + `:app:assembleDebug`
- Public repo; GitHub-hosted only

## Contract rules

- Auth: `Authorization: Bearer pcp_<key_id>_<secret>` only
- Branch on `spendable` for model picker
- `client_revoked` / 401: clear stored key, return to enroll
- Never log device keys or enrollment tokens
- Never a plaintext secret in a tracked file

## Conventions

- Conventional Commits; AGPL-3.0-only
- No em-dashes / en-dashes in prose
- Prefer parity with iOS PrismKit + App shell
