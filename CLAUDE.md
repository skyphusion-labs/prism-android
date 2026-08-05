# CLAUDE.md -- prism-android

Guidance for agents working in this repository.

## What this is

**AGPL Android client kit for Prism.** Chat, multimodal modalities, and (later) subscription /
quota UX against the commercial control plane. Goal is easier access to a curated model set with
cost-recovery hosting, not a closed app.

**Status: skeleton only.** Honest status matches `README.md`. Aviation-grade `main` (PR + CI +
coverage). No full app module yet; next work is a Bearer auth client and chat + stream against the
public or self-hosted Prism API.

## Related

| Repo | Role |
| --- | --- |
| [prism](https://github.com/skyphusion-labs/prism) | Inference playground Worker (`play.skyphusion.org`) |
| [prism-control-plane](https://github.com/skyphusion-labs/prism-control-plane) | Commercial multi-tenant control plane (live control plane; this client is still a skeleton kit) |
| [prism-ios](https://github.com/skyphusion-labs/prism-ios) | Sibling iOS kit (live control plane; this client is still a skeleton kit) |

## Layout

- `prism-kit` -- Kotlin library module (API client, models)
- App module to be added when UI work starts
- Root Gradle: `settings.gradle.kts`, `build.gradle.kts`, `gradlew`

## Commands

```bash
./gradlew :prism-kit:test --no-daemon   # unit tests (Ubuntu CI; no emulator for kit tests)
```

## CI

- `.github/workflows/ci.yml` -- push/PR to `main`: Gradle kit tests on `ubuntu-latest`
- Coverage workflow present; public repo uses GitHub-hosted runners only (fork-safe)

## Conventions

- No em-dashes (U+2014) or en-dashes (U+2013) in source or docs; use commas, semicolons, or `--`.
- Handle / username default: `skyphusion`.
- Conventional Commits. License: AGPL-3.0-only.
- Do not invent production deploy docs for a skeleton; keep status honest.

## Crew + identity

Crew work as their own identity (`sudo -u <member> bash -lc '...'`). Conrad laptop commits:
`Conrad Rockenhaus <conrad@skyphusion.org>`.
