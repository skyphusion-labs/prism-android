# Android beta (Play Internal testing)

You already have iOS on TestFlight. This is the Android twin for friends/testers.
You do **not** need to be an Android daily-driver; everything is laptop + browser.

## What you ship

- **App id:** `org.skyphusion.prism`
- **Product packs** (same ids as iOS / plane): see `StoreProducts` in prism-kit
- **Plane redeem:** Worker secret `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` (Play Console service account)

## One-time Play Console setup

1. Open [Google Play Console](https://play.google.com/console) → create app **Prism** (or open it).
2. **Testing → Internal testing** → create a track if empty.
3. **Setup → App integrity** / signing: accept Play App Signing (default).
4. **Monetize → Products → In-app products**: create the three consumables with the **same product ids** as App Store / `StoreProducts`.
5. **Users and permissions**: add yourself as admin.
6. **Internal testing → Testers**: create email list, add tester Gmail accounts (they need a Google account on the device).
7. Link a **service account** JSON for server-side purchase verification (plane redeem). Grant it Play Android Developer API access on this app. Put the JSON in plane secret `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`.

## Build a release APK/AAB (on your Mac)

```bash
cd ~/dev/prism-android
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home

# Debug (sideload / USB, fine for you only)
./gradlew :app:assembleDebug

# Release bundle for Play (needs a keystore; first time only)
# If you do not have a keystore yet, generate one:
# keytool -genkey -v -keystore ~/keys/prism-upload.jks -keyalg RSA -keysize 2048 -validity 10000 -alias prism
#
# Create app/keystore.properties (gitignored) with:
#   storeFile=/Users/YOU/keys/prism-upload.jks
#   storePassword=…
#   keyAlias=prism
#   keyPassword=…
#
# Then wire signingConfigs in app/build.gradle.kts if not already, or use Android Studio:
#   Build → Generate Signed Bundle / APK → Android App Bundle
./gradlew :app:bundleRelease   # when signing is configured
```

**Easiest if signing is not wired yet:** open the project in **Android Studio** →
**Build → Generate Signed Bundle / APK** → Android App Bundle → create/use upload keystore →
finish. Output: `app/release/app-release.aab`.

## Upload + invite (browser)

1. Play Console → **Internal testing** → **Create new release**.
2. Upload the `.aab` (or signed APK).
3. Release notes: e.g. `0.8.0 biometric lock, live STT, usage, vision`.
4. **Review release** → **Start rollout to Internal testing**.
5. Copy the **opt-in link** (or share from Testers tab). Testers open it on the phone,
   accept, then install **Prism** from Play (Internal testing).

Testers need:

- Google account on the device
- Be on your internal tester list
- Open the opt-in link once before install

## Sideload for you only (no Play)

```bash
# USB debugging on; device connected
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug builds do not exercise Play Billing against production products; use Internal testing for real IAP.

## Smoke for beta (same as TestFlight checklist)

1. Enroll with token or paste `pcp_` key.
2. Chat stream + non-stream; attach photo; mic menu (file STT + live STT).
3. Image + video (Seedance); leave video tab, wait for notification.
4. More → Usage refresh; Settings → top-up (Internal track + products).
5. Settings → Require biometrics; background app; unlock.
6. Optional: home screen → widgets → Prism balance.

## Version map

| Platform | Marketing | Notes |
|----------|-----------|--------|
| iOS main | 0.8.3 (+ 0.8.4 branch) | TestFlight live |
| Android main | **0.8.0** | This guide; matches 0.8.4 product deltas |

## Legal (required in-app + store listing)

Play Console **Store listing → Privacy policy** URL:

`https://skyphusion.org/privacy.html`

In-app (Settings → Legal & open source): privacy, bundled AGPL LICENSE, source
repos (`prism-android`, control plane, playground Worker). Same set as iOS.

## Support

Plane health: `curl -sS https://play-proxy.skyphusion.org/health`  
Support mail in app: support@skyphusion.org
