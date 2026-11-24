# prism-android

This file is the guide for any coding agent and any contributor. Follow it.

## What this project is

This is the AGPL Android client for Prism.
The app name is Prism for Android.
`prism-kit` is the JVM library.
`app` is the Compose Material3 application.
The application id is `org.skyphusion.prism`.

The version name and version code are in `app/build.gradle.kts` and `prism-kit/build.gradle.kts`.
Do not copy a version into this file.
See the latest `v*` tag.

The metered API is [prism-control-plane](https://github.com/skyphusion-labs/prism-control-plane).
The default host is `https://play-proxy.skyphusion.org`.
History, RAG, and artifacts stay on [prism](https://github.com/skyphusion-labs/prism).
Playground mode in the app is optional.
The sibling iOS app is [prism-ios](https://github.com/skyphusion-labs/prism-ios).
The MCP door is [prism-mcp](https://github.com/skyphusion-labs/prism-mcp).
Read each repo for its version.

## Layout

- `prism-kit` talks to the control plane and, in playground mode, to prism.
- `app` is enroll, chat, image, video, sessions, settings, and Play Billing.
- `docs/` holds architecture, the model list, Play Internal, and the 1.0 release notes.
- `scripts/` holds smoke scripts. They are not the CI gate.

The product surface is in [README.md](README.md) and [docs/RELEASE-1.0.md](docs/RELEASE-1.0.md).

## How to work

```bash
export JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"
./gradlew :prism-kit:test --no-daemon
./gradlew :app:assembleDebug --no-daemon
./gradlew :app:bundleRelease --no-daemon
```

`bundleRelease` needs `keystore.properties`.
That file is gitignored.

CI is [.github/workflows/ci.yml](.github/workflows/ci.yml).
The kit job runs unit tests.
It does not need the Android SDK.
The app job builds `assembleDebug`.
The repo is public.
CI uses GitHub-hosted runners.

## Contract rules

Send `Authorization: Bearer pcp_<key_id>_<secret>` only.
Branch the model picker on `spendable`.
The STT body is JSON `{ model, audio }`.
Audio is a data URL or base64.
It is not multipart.
The Play redeem package name is `org.skyphusion.prism`.
The plane setting is `ANDROID_PACKAGE_NAME`.
On `client_revoked` or HTTP 401, clear the stored key and return to enroll.
Play Billing credit packs call `POST /v1/store/redeem` with `platform=google_play`.
See [docs/PLAY-INTERNAL.md](docs/PLAY-INTERNAL.md).

Match iOS PrismKit behavior when you change a client rule.
The README shape matches prism-ios.

## Release

Merge the feature pull request to `main`.
An annotated tag `v*` builds the GitHub Release and the signed `.aab`.
Play Internal upload steps are in [docs/PLAY-INTERNAL.md](docs/PLAY-INTERNAL.md) and [docs/RELEASE-1.0.md](docs/RELEASE-1.0.md).

A version changes only in a release pull request.
Feature and fix pull requests do not bump it.
Put the tag on that pull request's merge commit.
The version of record is `versionName` and `versionCode` in the two Gradle files above.
The tag shape is `v*`.
If that version already has a tag, the cycle is closed.
The next release pull request bumps.
If the manifest version has no tag, stop.
Find why before you bump again.

## Docs

Write Simplified Technical English.
Use short sentences.
Use one idea per sentence.
Use the active present tense.
Use plain words.
Use the same word every time.

Do not use an em dash (U+2014) or an en dash (U+2013).
Use a comma, a semicolon, parentheses, or two hyphens (`--`).

State the limit next to the capability.
Do not turn a measured result into a promise.
Use Conventional Commits.
The license is AGPL-3.0-only.

## Safety

Do not put a secret in a tracked file.
Do not log a device key or an enrollment token.
Do not put a secret in an issue.
