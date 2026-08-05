# CLAUDE.md -- prism-android

Guidance for agents working in this repository.

## What this is

**AGPL Android client kit for Prism.** Metered inference against
[prism-control-plane](https://github.com/skyphusion-labs/prism-control-plane); conversation
history / RAG / artifacts stay on [prism](https://github.com/skyphusion-labs/prism) (separate
API when needed).

**Kit v0.1.0:** `ControlPlaneClient` (Bearer `pcp_` enroll, me, models, chat, SSE stream).
No full app module yet. Aviation-grade `main`.

## Related

| Repo | Role |
| --- | --- |
| [prism](https://github.com/skyphusion-labs/prism) | Playground Worker (`play.skyphusion.org`) |
| [prism-control-plane](https://github.com/skyphusion-labs/prism-control-plane) | Metered inference (`play-proxy.skyphusion.org`) -- **primary kit target** |
| [prism-ios](https://github.com/skyphusion-labs/prism-ios) | Sibling Swift kit (parity source for shapes) |
| [prism-mcp](https://github.com/skyphusion-labs/prism-mcp) | Agent MCP door (hosted) |

## Layout

- `prism-kit` -- Kotlin JVM library
  - `ControlPlaneClient.kt` -- control-plane surface
  - `HttpJson.kt` -- OkHttp + kotlinx.serialization
  - `SseParser.kt` -- OpenAI-compatible + playground SSE
  - `Models.kt` -- contract DTOs
- App module: not yet

## Commands

```bash
./gradlew :prism-kit:test --no-daemon
```

## CI

- `.github/workflows/ci.yml` -- push/PR `main`: Gradle kit tests on `ubuntu-latest`
- Coverage + CodeQL present; public repo, GitHub-hosted only

## Contract rules (do not invent)

- Auth: `Authorization: Bearer pcp_<key_id>_<secret>` only. Never return CF credentials to the app.
- Path versioning: `/v1/...` additive only.
- `GET /v1/models`: branch on `spendable`; do not show unspendable as callable.
- `client_revoked` (401) is terminal: drop key, re-enroll.
- Prompt/completion text is never stored by the control plane.

## Conventions

- Conventional Commits; AGPL-3.0-only.
- No em-dashes / en-dashes in prose.
- Never a plaintext secret in a tracked file.
- Prefer parity with iOS `PrismKit` shapes where practical.
