# Android beta (Play Internal testing)

You already have iOS on TestFlight. This is the Android twin for friends/testers.
You do **not** need to be an Android daily-driver; everything is laptop + browser.

## What you ship

- **App id:** `org.skyphusion.prism`
- **Version:** 1.0.0 (`versionCode` 19)
- **Product packs** (same ids as iOS / plane): see `StoreProducts` in prism-kit
- **Plane redeem:** Worker secret `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` (Play Console service account)

## One-time Play Console setup

1. Open [Google Play Console](https://play.google.com/console) → create app **Prism** (or open it).
2. **Testing → Internal testing** → create a track if empty.
3. **Setup → App integrity** / signing: accept Play App Signing (default).
4. **Monetize → Products → In-app products**: create the three consumables with the **same product ids** as App Store / `StoreProducts`:
   - `org.skyphusion.prism.credit.5`
   - `org.skyphusion.prism.credit.20`
   - `org.skyphusion.prism.credit.50`
5. **Users and permissions**: add yourself as admin.
6. **Internal testing → Testers**: create email list, add tester Gmail accounts (they need a Google account on the device).
7. Link a **service account** JSON for server-side purchase verification (plane redeem). Grant it Play Android Developer API access on this app. Put the JSON in plane secret `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`.
8. **Store listing → Privacy policy:** `https://skyphusion.org/privacy.html`

## Build a release AAB (on your Mac)

```bash
cd ~/dev/prism-android
export JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"

# keystore.properties at repo root (gitignored), e.g.:
#   storeFile=/Users/YOU/.config/skyphusion/android/prism-upload.jks
#   storePassword=…
#   keyAlias=prism
#   keyPassword=…

./gradlew :app:bundleRelease
ls -la app/build/outputs/bundle/release/app-release.aab
```

First-time keystore (do this once; back up the jks + passwords offline):

```bash
keytool -genkey -v \
  -keystore ~/.config/skyphusion/android/prism-upload.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias prism
```

## Upload + invite (browser)

1. Play Console → **Internal testing** → **Create new release**.
2. Upload `app-release.aab`.
3. Release notes example:

   ```
   1.0.0 -- Prism for Android (iOS 1.0 parity)
   Async video/music/speech/image jobs, video clip length picker,
   Use in chat / Animate handoffs, live STT, Play top-up, biometric lock.
   ```

4. **Review release** → **Start rollout to Internal testing**.
5. Copy the **opt-in link** (Testers tab). Testers open it on the phone once, accept, then install **Prism** from Play (Internal testing).

Testers need:

- Google account on the device
- Be on your internal tester list
- Open the opt-in link once before install

## Sideload for you only (no Play)

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
# debug intent inject (debug builds only):
# am start -n org.skyphusion.prism/.app.MainActivity -e pcp_key "$PCP"
```

Debug builds do not exercise Play Billing against production products; use Internal testing for real IAP.

## Smoke for beta (same spirit as TestFlight)

1. Enroll with token or paste `pcp_` key.
2. Chat stream + non-stream; attach photo; mic (file STT + live STT).
3. Image + video (Seedance Fast / Veo); clip length control; wait for notification on long jobs.
4. Use in chat / Animate from image result.
5. More → Usage refresh; Settings → top-up (Internal track + products).
6. Settings → Require biometrics; background app; unlock.
7. Optional: home screen widget → Prism balance.

## Version map

| Platform | Marketing | Notes |
|----------|-----------|--------|
| iOS | **1.0.0** | TestFlight / App Store path |
| Android | **1.0.0** | This guide; Play Internal |

## Legal

Play Console **Store listing → Privacy policy:**

`https://skyphusion.org/privacy.html`

In-app (Settings → Legal & open source): privacy, bundled AGPL LICENSE, source
repos (`prism-android`, control plane, playground Worker). Same set as iOS.

## Support

Plane health: `curl -sS https://play-proxy.skyphusion.org/health`  
Support: support@skyphusion.org  
Architecture: [ARCHITECTURE.md](ARCHITECTURE.md) · Models: [MODELS.md](MODELS.md)
