# Architecture -- Prism for Android and the control plane

How **prism-android** fits into the Prism stack: commercial metered inference on
[prism-control-plane](https://github.com/skyphusion-labs/prism-control-plane), optional playground
on [prism](https://github.com/skyphusion-labs/prism), sibling product [prism-ios](https://github.com/skyphusion-labs/prism-ios).

## System context

```mermaid
flowchart TB
  subgraph clients [Clients]
    Android["prism-android<br/>Compose app + prism-kit"]
    iOS["prism-ios<br/>SwiftUI + PrismKit"]
    Web["play.skyphusion.org<br/>browser"]
  end

  subgraph edge [Edge]
    Proxy["play-proxy.skyphusion.org<br/>prism-control-plane Worker"]
  end

  subgraph plane [Control plane data plane]
    D1[(D1 accounts / keys / usage)]
    R2[(R2 signed media)]
    Jobs[Async Workflow jobs<br/>video music speech image]
    Catalog[Model catalog + pricing]
  end

  subgraph upstream [Upstream inference]
    CF["Cloudflare AI Gateway<br/>Workers AI + Unified Billing"]
    Providers["OpenAI · Anthropic · Google<br/>xAI · ByteDance · …"]
  end

  subgraph playground [Optional playground]
    Worker["play.skyphusion.org<br/>prism Worker"]
    History[(Conversation history / RAG)]
  end

  Android -->|"Bearer pcp_…"| Proxy
  iOS -->|"Bearer pcp_…"| Proxy
  Web -->|"Bearer pcp_…"| Proxy
  Proxy --> D1
  Proxy --> R2
  Proxy --> Jobs
  Proxy --> Catalog
  Proxy --> CF
  CF --> Providers
  Android -.->|"developer mode only<br/>session cookie"| Worker
  Worker --> History
```

## Auth and money

```mermaid
sequenceDiagram
  participant Op as Operator
  participant Plane as play-proxy
  participant App as prism-android
  participant Play as Google Play Billing

  Op->>Plane: mint one-time enrollment token
  App->>Plane: POST /v1/clients enrollment_token
  Plane-->>App: pcp_ key once store EncryptedSharedPreferences
  App->>Plane: GET /v1/me, GET /v1/models
  Plane-->>App: balance dual-pool + catalog
  App->>Play: buy credit pack consumable
  Play-->>App: purchase token
  App->>Plane: POST /v1/store/redeem platform=google_play
  Plane-->>App: verified credit balance refresh
```

Device key format: `pcp_<key_id>_<secret>`. Never logged. On `client_revoked` / 401 the app clears
the key and returns to enroll.

## Inference doors (plane)

| Door | Method / path | Android surface |
|------|----------------|-----------------|
| Chat | `POST /v1/chat/completions` (+ SSE stream) | Chat tab |
| Image | `POST /v1/images/generations` | Image tab |
| Video | `POST /v1/videos/generations` | Video tab + duration picker |
| Speech TTS | `POST /v1/audio/speech` | More → Audio |
| STT file | `POST /v1/audio/transcriptions` JSON `{model,audio}` | Audio + chat mic |
| STT live | `GET /v1/stt/stream` WebSocket | Live STT |
| Music | `POST /v1/music/generations` | More → Music |
| Jobs | `GET /v1/jobs/:id` | poll after Prefer: respond-async |
| Usage | `GET /v1/usage`, `GET /v1/me` | More → Usage, balance chips |
| Redeem | `POST /v1/store/redeem` | Settings top-up |

Long-run media (video, music, speech, gpt-image-2) uses **Prefer: respond-async** → 202 job id →
poll until terminal. Job ids persist across process death; **forceSync** on foreground resume.

## App modules

```mermaid
flowchart LR
  subgraph app [app module]
    UI[Compose UI<br/>Chat Image Video More]
    VM[AppViewModel]
    Bill[BillingManager]
    Sec[EncryptedPrefsSecretStore]
  end

  subgraph kit [prism-kit JVM]
    CPC[ControlPlaneClient]
    PC[PrismClient playground]
    VCD[VideoClipDuration]
    Models[Models DTOs]
  end

  UI --> VM
  VM --> CPC
  VM --> PC
  VM --> Sec
  Bill --> CPC
  CPC --> Models
  CPC --> VCD
  CPC -->|"HTTPS play-proxy"| Plane[(control plane)]
```

| Module | Role |
|--------|------|
| `prism-kit` | Pure JVM HTTP client, DTOs, duration catalog, secret key names |
| `app` | Compose shell, enroll, tabs, billing, biometric lock, widgets |

## Cross-modal handoffs (1.0)

```mermaid
flowchart LR
  Img[Image result / history]
  Chat[Chat draft]
  Vid[Video i2v ref]

  Img -->|"Use in chat"| Chat
  Img -->|"Animate"| Vid
  Chat -->|"Animate on attach / turn"| Vid
  Chat -->|"Text file inline"| Prompt[User message fenced text<br/>not RAG]
```

## Privacy / data residency

- **Plane chat transcript:** client-owned (device sessions + export). Plane meters tokens; does not store chat history for plane clients.
- **Playground mode (developer):** server conversations on the Worker; media doors disabled.
- **Keys:** Android Keystore-backed EncryptedSharedPreferences only.
- **Legal:** AGPL-3.0-only; privacy https://skyphusion.org/privacy.html

## Related contracts

- Plane OpenAPI / CONTRACT: control-plane `docs/`
- Model list snapshot: [MODELS.md](MODELS.md)
- Play Internal beta: [PLAY-INTERNAL.md](PLAY-INTERNAL.md)
- Release: [RELEASE-1.0.md](RELEASE-1.0.md)
