# Dependabot notes (prism-android)

## App runtime vs build classpath

`./gradlew :app:dependencyInsight` on `releaseRuntimeClasspath` shows **no**
Netty, BouncyCastle, jose4j, or jdom2. The app ships OkHttp + Compose + Play
Billing. Those Maven alerts are **transitive of the Android Gradle Plugin and
related build tooling** (e.g. `io.grpc:grpc-netty` under AGP, jetifier jdom2,
signing / PKCS stacks for BC, jose4j under AGP utilities).

They do **not** ship inside the release AAB/APK as app network code paths.

## What we force

Root `build.gradle.kts` forces:

| Group / module | Forced version | Why |
|----------------|----------------|-----|
| `io.netty:*` | `4.2.17.Final` | HTTP/2, codec, handler CVEs |
| `org.bouncycastle:*` | `1.85` | GOST CTR + LDAP + bcpkix. There is no `1.85.2`. |
| `org.bitbucket.b_c:jose4j` | `0.9.6` | compressed JWE DoS |
| `org.jdom:jdom2` | `2.0.6.1` | XXE |
| `org.apache.commons:commons-lang3` | `3.20.0` | Uncontrolled recursion on long inputs (GHSA-j288-q9x7-2f5v) |
| `org.apache.commons:commons-compress` | `1.28.0` | Compress CVEs / Dependabot bump |

AGP is also bumped (see root plugins block) so Google's pin moves forward when it can.

## Ignored majors

`.github/dependabot.yml` ignores majors for:

| Coordinate | Until |
|---|---|
| `gradle` (wrapper) | AGP 9. AGP 8.13.2 uses `InternalProblems`, removed in Gradle 9.6. |
| `com.android.application` | Dedicated AGP 9 migration. Closed #53 (8.13.2 -> 9.3.1). |
| `com.android.billingclient:billing-ktx` | A dedicated Billing 8/9 migration. 9.x returns `QueryProductDetailsResult` instead of `List<ProductDetails>`. |
| `org.bouncycastle:bc{prov,pkix,util}-jdk18on` `1.85.2` | Not published. Latest is `1.85`. Closed #52. |

okhttp 5 is *not* ignored. It needs compileSdk 36 (same as `activity-compose` 1.13) and is a separate major PR.

## Residual / cannot fully fix alone

1. **Plugin classpath isolation.** Gradle resolves AGP on a plugin classpath that is not always fully covered by project `buildscript` / `configurations` forces. If Dependabot still reports the same GHSA after merge, the residual is **upstream AGP** (or a Gradle plugin-classpath force that needs a newer Gradle/AGP). Re-check with `./gradlew buildEnvironment | rg netty`.
2. **We do not vendor AGP.** Replacing Google's build stack to eliminate Netty is out of scope; forces + AGP bumps are the supported path.
3. **False urgency for mobile clients.** Exploit classes (HTTP/2 reset on a server, request smuggling on a reverse proxy) target **servers** using Netty as an HTTP stack. Our use is AGP talking to Google build services during compile, not an internet-facing Netty server.

## Verify after bump

```bash
./gradlew buildEnvironment | rg "netty-codec-http2|bcprov-jdk18on|jose4j|jdom2"
./gradlew :app:assembleRelease
./gradlew :prism-kit:test
```

## Build-classpath forces (protobuf / commons)

Also forced on the AGP build classpath (tooling only, not app runtime):

| Module | Version |
|--------|---------|
| `com.google.protobuf:protobuf-java` (+ kotlin/util) | `4.35.1` |
| `org.apache.commons:commons-compress` | `1.28.0` |
| `org.apache.commons:commons-lang3` | `3.20.0` |
