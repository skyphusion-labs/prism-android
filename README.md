# prism-android

**License:** AGPL-3.0-only  
**API:** [prism](https://github.com/skyphusion-labs/prism)  
**Control plane:** [prism-control-plane](https://github.com/skyphusion-labs/prism-control-plane)  
**Sibling:** [prism-ios](https://github.com/skyphusion-labs/prism-ios)

## What this is

AGPL **Android client** for Prism: chat, multimodal modalities, and (later)
subscription / quota UX against the commercial control plane. Goal is easier
access to a curated model set with cost-recovery hosting, not a closed app.

## Layout (skeleton)

- `prism-kit` -- Kotlin library module (API client, models)
- App module to be added when UI work starts
- CI runs unit tests + coverage on Ubuntu (no emulator required for kit tests)

## Status

Skeleton only. Aviation-grade `main`. Next: Bearer auth client, chat + stream
against the public or self-hosted Prism API.

## Related

- Playground: https://play.skyphusion.org  
- iOS: https://github.com/skyphusion-labs/prism-ios
