# Prism model catalog (control plane)

Snapshot from live `GET https://play-proxy.skyphusion.org/v1/models` (2026-08-07 UTC).

**93 models** across modalities. Prices and availability change;
the app always loads the live catalog after enroll. This page is documentation only.

**Auth:** `Authorization: Bearer pcp_<key_id>_<secret>`.
With **Hide unspendable** on (default), only `spendable: true` models appear in pickers.

## Counts

| Modality | Count |
|----------|------:|
| chat | 44 |
| image | 21 |
| video | 19 |
| tts | 3 |
| stt | 4 |
| music | 1 |
| voice | 1 |
| **total** | **93** |

## chat (44)

| Model id | Display name | Tier | Stream | Capabilities | Price |
|----------|--------------|------|:------:|--------------|-------|
| `@cf/aisingapore/gemma-sea-lion-v4-27b-it` | SEA-LION v4 27B (SE Asian langs) | standard | yes | — | $0.35 / $0.56 per MTok in/out |
| `@cf/deepseek-ai/deepseek-r1-distill-qwen-32b` | DeepSeek R1 32B | standard | yes | — | $0.50 / $4.88 per MTok in/out |
| `@cf/google/gemma-4-26b-a4b-it` | Gemma 4 26B (vision) | standard | yes | — | $0.10 / $0.30 per MTok in/out |
| `@cf/ibm-granite/granite-4.0-h-micro` | Granite 4.0 Micro (IBM) | standard | yes | — | $0.02 / $0.11 per MTok in/out |
| `@cf/meta/llama-3.2-11b-vision-instruct` | Llama 3.2 11B (vision) | standard | yes | — | $0.05 / $0.68 per MTok in/out |
| `@cf/meta/llama-3.2-1b-instruct` | Llama 3.2 1B (tiny, cheap) | standard | yes | — | $0.03 / $0.20 per MTok in/out |
| `@cf/meta/llama-3.2-3b-instruct` | Llama 3.2 3B | standard | yes | — | $0.05 / $0.34 per MTok in/out |
| `@cf/meta/llama-3.3-70b-instruct-fp8-fast` | Llama 3.3 70B (fp8) | standard | yes | — | $0.29 / $2.25 per MTok in/out |
| `@cf/meta/llama-4-scout-17b-16e-instruct` | Llama 4 Scout (MoE, vision) | standard | yes | — | $0.27 / $0.85 per MTok in/out |
| `@cf/mistralai/mistral-small-3.1-24b-instruct` | Mistral Small 3.1 (vision) | standard | yes | — | $0.35 / $0.56 per MTok in/out |
| `@cf/moonshotai/kimi-k2.6` | Kimi K2.6 (1T) | standard | yes | — | $0.95 / $4.00 per MTok in/out |
| `@cf/moonshotai/kimi-k2.7-code` | Kimi K2.7 Code (1T, vision) | standard | yes | — | $0.95 / $4.00 per MTok in/out |
| `@cf/nvidia/nemotron-3-120b-a12b` | Nemotron 3 120B (NVIDIA, agentic) | standard | yes | — | $0.50 / $1.50 per MTok in/out |
| `@cf/openai/gpt-oss-120b` | GPT-OSS 120B (reasoning) | standard | yes | — | $0.35 / $0.75 per MTok in/out |
| `@cf/openai/gpt-oss-20b` | GPT-OSS 20B | standard | yes | — | $0.20 / $0.30 per MTok in/out |
| `@cf/qwen/qwen2.5-coder-32b-instruct` | Qwen2.5 Coder 32B | standard | yes | — | $0.66 / $1.00 per MTok in/out |
| `@cf/qwen/qwen3-30b-a3b-fp8` | Qwen3 30B MoE | standard | yes | — | $0.05 / $0.34 per MTok in/out |
| `@cf/qwen/qwq-32b` | QwQ 32B (reasoning) | standard | yes | — | $0.66 / $1.00 per MTok in/out |
| `@cf/zai-org/glm-4.7-flash` | GLM-4.7 Flash (Z.AI, 100+ lang) | standard | yes | — | $0.06 / $0.40 per MTok in/out |
| `@cf/zai-org/glm-5.2` | GLM-5.2 (Z.AI, agentic coding) | standard | yes | — | $1.40 / $4.40 per MTok in/out |
| `anthropic/claude-fable-5` | Claude Fable 5 (Anthropic) | premium | yes | — | $10.00 / $50.00 per MTok in/out |
| `anthropic/claude-haiku-4-5` | Claude Haiku 4.5 (Anthropic) | premium | yes | — | $1.00 / $5.00 per MTok in/out |
| `anthropic/claude-opus-4-6` | Claude Opus 4.6 (Anthropic) | premium | yes | — | $5.00 / $25.00 per MTok in/out |
| `anthropic/claude-opus-4-7` | Claude Opus 4.7 (Anthropic) | premium | yes | — | $5.00 / $25.00 per MTok in/out |
| `anthropic/claude-opus-4-8` | Claude Opus 4.8 (Anthropic) | premium | yes | — | $5.00 / $25.00 per MTok in/out |
| `anthropic/claude-opus-5` | Claude Opus 5 (Anthropic) | premium | yes | — | $5.00 / $25.00 per MTok in/out |
| `anthropic/claude-sonnet-4-6` | Claude Sonnet 4.6 (Anthropic) | premium | yes | — | $3.00 / $15.00 per MTok in/out |
| `anthropic/claude-sonnet-5` | Claude Sonnet 5 (Anthropic) | premium | yes | — | $2.00 / $10.00 per MTok in/out |
| `google/gemini-3.1-pro` | Gemini 3.1 Pro (Google) | premium | yes | — | $2.00 / $12.00 per MTok in/out |
| `google/gemini-3.5-flash` | Gemini 3.5 Flash (Google) | premium | yes | — | $1.50 / $9.00 per MTok in/out |
| `google/gemini-3.6-flash` | Gemini 3.6 Flash (Google) | premium | yes | — | $1.50 / $7.50 per MTok in/out |
| `moonshotai/kimi-k3` | Kimi K3 (Moonshot, 1M ctx) | premium | yes | — | $3.00 / $15.00 per MTok in/out |
| `openai/gpt-5.4` | GPT-5.4 (OpenAI) | premium | yes | — | $2.50 / $15.00 per MTok in/out |
| `openai/gpt-5.4-mini` | GPT-5.4 mini (OpenAI) | premium | yes | — | $0.75 / $4.50 per MTok in/out |
| `openai/gpt-5.5` | GPT-5.5 (OpenAI) | premium | yes | — | $5.00 / $30.00 per MTok in/out |
| `openai/gpt-5.5-pro` | GPT-5.5 Pro (OpenAI, Responses) | premium | yes | — | $30.00 / $180.00 per MTok in/out |
| `openai/gpt-5.6-luna` | GPT-5.6 Luna (OpenAI, Responses) | premium | yes | — | $0.10 / $0.60 per MTok in/out |
| `openai/gpt-5.6-sol` | GPT-5.6 Sol (OpenAI, Responses) | premium | yes | — | $5.00 / $30.00 per MTok in/out |
| `openai/gpt-5.6-terra` | GPT-5.6 Terra (OpenAI, Responses) | premium | yes | — | $1.00 / $6.00 per MTok in/out |
| `openai/o4-mini` | o4-mini (OpenAI, reasoning) | premium | yes | — | $1.10 / $4.40 per MTok in/out |
| `xai/grok-4.20-0309-reasoning` | Grok 4.20 Reasoning (xAI) | premium | yes | — | $1.25 / $2.50 per MTok in/out |
| `xai/grok-4.20-multi-agent-0309` | Grok 4.20 Multi-Agent (xAI) | premium | yes | — | $1.25 / $2.50 per MTok in/out |
| `xai/grok-4.3` | Grok 4.3 (xAI) | premium | yes | — | $1.25 / $2.50 per MTok in/out |
| `xai/grok-4.5` | Grok 4.5 (xAI) | premium | yes | — | $2.00 / $6.00 per MTok in/out |

## image (21)

| Model id | Display name | Tier | Stream | Capabilities | Price |
|----------|--------------|------|:------:|--------------|-------|
| `@cf/black-forest-labs/flux-1-schnell` | FLUX-1 schnell (fast) | standard | — | text-to-image | $0.000477/request |
| `@cf/black-forest-labs/flux-2-dev` | FLUX 2 Dev (multi-reference) | standard | — | text-to-image, image-input | $0/request |
| `@cf/black-forest-labs/flux-2-klein-4b` | FLUX 2 Klein 4B (faster) | standard | — | text-to-image, image-input | $0/request |
| `@cf/black-forest-labs/flux-2-klein-9b` | FLUX 2 Klein 9B (frontier) | standard | — | text-to-image, image-input | $0/request |
| `@cf/leonardo/lucid-origin` | Lucid Origin (Leonardo) | standard | — | text-to-image | $0.0103/request |
| `@cf/leonardo/phoenix-1.0` | Phoenix 1.0 (Leonardo) | standard | — | text-to-image | $0.00858/request |
| `@cf/lykon/dreamshaper-8-lcm` | Dreamshaper 8 LCM (fast SD) | standard | — | text-to-image | $0/request |
| `@cf/stabilityai/stable-diffusion-xl-base-1.0` | Stable Diffusion XL (SDXL) | standard | — | text-to-image | $1e-06/request |
| `bytedance/seedream-5-lite` | Seedream 5 Lite (ByteDance) | premium | — | text-to-image | $0.02/request |
| `bytedance/seedream-5-pro` | Seedream 5 Pro (ByteDance) | premium | — | text-to-image | $0.04/request |
| `google/imagen-4` | Imagen 4 (Google) | premium | — | text-to-image | $0.04/request |
| `google/nano-banana-2` | Nano Banana 2 (Google) | premium | — | text-to-image, image-input | $0.04/request |
| `google/nano-banana-2-lite` | Nano Banana 2 Lite (Google) | premium | — | text-to-image, image-input | $0.02/request |
| `google/nano-banana-pro` | Nano Banana Pro (Google) | premium | — | text-to-image, image-input | $0.04/request |
| `openai/gpt-image-1.5` | GPT Image 1.5 (OpenAI) | premium | — | text-to-image, image-input | $0.04/request |
| `openai/gpt-image-2` | GPT Image 2 (OpenAI) | premium | — | text-to-image, image-input | $0.055/request |
| `recraft/recraftv4` | Recraft V4 (art-directed, opaque) | premium | — | text-to-image | $0.04/request |
| `recraft/recraftv4-1` | Recraft V4.1 (art-directed, opaque) | premium | — | text-to-image | $0.04/request |
| `recraft/recraftv4-1-pro` | Recraft V4.1 Pro (art-directed, opaque) | premium | — | text-to-image | $0.08/request |
| `xai/grok-imagine-image` | Grok Imagine Image (xAI) | premium | — | text-to-image, image-input | $0.02/request |
| `xai/grok-imagine-image-quality` | Grok Imagine Image Quality (xAI) | premium | — | text-to-image, image-input | $0.05/request |

## video (19)

| Model id | Display name | Tier | Stream | Capabilities | Price |
|----------|--------------|------|:------:|--------------|-------|
| `alibaba/hh1-i2v` | HappyHorse 1.0 I2V (Alibaba, image-to-video) | premium | — | text-to-video, image-input | $0.25/request |
| `alibaba/hh1-t2v` | HappyHorse 1.0 T2V (Alibaba) | premium | — | text-to-video | $0.25/request |
| `alibaba/hh1.1-i2v` | HappyHorse 1.1 I2V (Alibaba, image-to-video) | premium | — | text-to-video, image-input | $0.25/request |
| `alibaba/hh1.1-t2v` | HappyHorse 1.1 T2V (Alibaba) | premium | — | text-to-video | $0.25/request |
| `alibaba/wan-2.7-i2v` | Wan 2.7 I2V (Alibaba, image-to-video) | premium | — | text-to-video, image-input | $0.25/request |
| `bytedance/seedance-2.0` | Seedance 2.0 (ByteDance) | premium | — | text-to-video, image-input | $0.5/request |
| `bytedance/seedance-2.0-fast` | Seedance 2.0 Fast (ByteDance) | premium | — | text-to-video, image-input | $0.3/request |
| `bytedance/seedance-2.0-mini` | Seedance 2.0 Mini (ByteDance) | premium | — | text-to-video, image-input | $0.2/request |
| `google/veo-3.1` | Veo 3.1 (Google) | premium | — | text-to-video | $3.2/request |
| `google/veo-3.1-fast` | Veo 3.1 Fast (Google) | premium | — | text-to-video | $1.2/request |
| `minimax/hailuo-2.3` | Hailuo 2.3 (MiniMax) | premium | — | image-input, image-input-required | $0.5/request |
| `minimax/hailuo-2.3-fast` | Hailuo 2.3 Fast (MiniMax) | premium | — | image-input, image-input-required | $0.3/request |
| `pixverse/v5.6` | PixVerse v5.6 | premium | — | text-to-video | $0.2/request |
| `pixverse/v6` | PixVerse v6 | premium | — | text-to-video | $0.25/request |
| `runwayml/gen-4.5` | Gen-4.5 (RunwayML) | premium | — | text-to-video, image-input | $0.5/request |
| `vidu/q3-pro` | Vidu Q3 Pro | premium | — | text-to-video | $0.3/request |
| `vidu/q3-turbo` | Vidu Q3 Turbo | premium | — | text-to-video | $0.2/request |
| `xai/grok-imagine-video` | Grok Imagine Video (xAI) | premium | — | text-to-video, image-input | $0.4/request |
| `xai/grok-imagine-video-1.5-preview` | Grok Imagine Video 1.5 (xAI, preview) | premium | — | text-to-video, image-input | $0.64/request |

## tts (3)

| Model id | Display name | Tier | Stream | Capabilities | Price |
|----------|--------------|------|:------:|--------------|-------|
| `@cf/deepgram/aura-2-en` | Aura-2 English (Deepgram) | standard | — | — | $0.03/k_characters |
| `@cf/deepgram/aura-2-es` | Aura-2 Spanish (Deepgram) | standard | — | — | $0.03/k_characters |
| `@cf/myshell-ai/melotts` | MeloTTS (multilingual) | standard | — | — | $0.000205/audio_minute |

## stt (4)

| Model id | Display name | Tier | Stream | Capabilities | Price |
|----------|--------------|------|:------:|--------------|-------|
| `@cf/deepgram/nova-3` | Deepgram Nova-3 (accurate) | standard | — | — | $0.0052/audio_minute |
| `@cf/openai/whisper` | Whisper (general purpose) | standard | — | — | $0.000453/audio_minute |
| `@cf/openai/whisper-large-v3-turbo` | Whisper Large v3 Turbo (best) | standard | — | — | $0.000513/audio_minute |
| `@cf/openai/whisper-tiny-en` | Whisper Tiny EN (fast, beta) | standard | — | — | $0/audio_minute |

## music (1)

| Model id | Display name | Tier | Stream | Capabilities | Price |
|----------|--------------|------|:------:|--------------|-------|
| `minimax/music-2.6` | MiniMax Music 2.6 | premium | — | — | $0.05/request |

## voice (1)

| Model id | Display name | Tier | Stream | Capabilities | Price |
|----------|--------------|------|:------:|--------------|-------|
| `@cf/deepgram/flux` | Deepgram Flux (live mic) | standard | — | — | $0.0077/audio_minute |

## Client notes (Android 1.0)

- **Video duration:** user picker clamped to CF limits (`VideoClipDuration` / plane `video-duration.ts`).
  Defaults: most models 5s, Hailuo 6, Veo string enum up to `8s`.
- **Hailuo** requires a first-frame image (`image-input-required`).
- **Async jobs:** video, music, speech always Prefer-async; gpt-image-2 plane auto-async; poll `GET /v1/jobs/:id`.
- **MeloTTS** (`@cf/myshell-ai/melotts`): intermittent CF AiError 3043; prefer Deepgram Aura-2.
- **Live voice** (`@cf/deepgram/flux`): WebSocket STT (`/v1/stt/stream`), not file STT.
- **Grok video:** needs plane 0.4.14+ (ZDR upload path); signed media via play-proxy.

Regenerate this file from a device key:

```bash
curl -sS -H "Authorization: Bearer $PCP" https://play-proxy.skyphusion.org/v1/models | jq .
```
