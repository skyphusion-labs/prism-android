plugins {
  kotlin("jvm") version "2.4.10" apply false
  kotlin("plugin.serialization") version "2.4.10" apply false
  kotlin("android") version "2.4.10" apply false
  kotlin("plugin.compose") version "2.4.10" apply false
  id("com.android.application") version "8.13.2" apply false
}

// Dependabot: Netty / BouncyCastle / jose4j / jdom2 arrive only on the
// Android Gradle Plugin + tooling *build* classpath (grpc-netty, signing,
// jetifier). They are not on the app releaseRuntimeClasspath. Force patched
// versions so the dependency graph / Dependabot alerts clear without waiting
// on a Google AGP pin. See docs/dependabot.md.
//
// Note: strings are inlined inside each force() because buildscript {} has its
// own script scope and cannot see top-level vals from the outer root script.

buildscript {
  repositories {
    google()
    mavenCentral()
  }
  configurations.configureEach {
    resolutionStrategy {
      force(
        "io.netty:netty-buffer:4.1.136.Final",
        "io.netty:netty-codec:4.1.136.Final",
        "io.netty:netty-codec-http:4.1.136.Final",
        "io.netty:netty-codec-http2:4.1.136.Final",
        "io.netty:netty-codec-socks:4.1.136.Final",
        "io.netty:netty-common:4.1.136.Final",
        "io.netty:netty-handler:4.1.136.Final",
        "io.netty:netty-handler-proxy:4.1.136.Final",
        "io.netty:netty-resolver:4.1.136.Final",
        "io.netty:netty-transport:4.1.136.Final",
        "io.netty:netty-transport-native-unix-common:4.1.136.Final",
        "org.bouncycastle:bcprov-jdk18on:1.84",
        "org.bouncycastle:bcpkix-jdk18on:1.84",
        "org.bouncycastle:bcutil-jdk18on:1.84",
        "org.bitbucket.b_c:jose4j:0.9.6",
        "org.jdom:jdom2:2.0.6.1",
        "com.google.protobuf:protobuf-java:3.25.5",
        "com.google.protobuf:protobuf-kotlin:3.25.5",
        "com.google.protobuf:protobuf-java-util:3.25.5",
        "org.apache.commons:commons-compress:1.26.0",

      )
    }
  }
}

allprojects {
  buildscript {
    repositories {
      google()
      mavenCentral()
    }
    configurations.configureEach {
      resolutionStrategy {
        force(
          "io.netty:netty-buffer:4.1.136.Final",
          "io.netty:netty-codec:4.1.136.Final",
          "io.netty:netty-codec-http:4.1.136.Final",
          "io.netty:netty-codec-http2:4.1.136.Final",
          "io.netty:netty-codec-socks:4.1.136.Final",
          "io.netty:netty-common:4.1.136.Final",
          "io.netty:netty-handler:4.1.136.Final",
          "io.netty:netty-handler-proxy:4.1.136.Final",
          "io.netty:netty-resolver:4.1.136.Final",
          "io.netty:netty-transport:4.1.136.Final",
          "io.netty:netty-transport-native-unix-common:4.1.136.Final",
          "org.bouncycastle:bcprov-jdk18on:1.84",
          "org.bouncycastle:bcpkix-jdk18on:1.84",
          "org.bouncycastle:bcutil-jdk18on:1.84",
          "org.bitbucket.b_c:jose4j:0.9.6",
          "org.jdom:jdom2:2.0.6.1",
        "com.google.protobuf:protobuf-java:3.25.5",
        "com.google.protobuf:protobuf-kotlin:3.25.5",
        "com.google.protobuf:protobuf-java-util:3.25.5",
        "org.apache.commons:commons-compress:1.26.0",

        )
      }
    }
  }
  configurations.configureEach {
    resolutionStrategy {
      force(
        "io.netty:netty-buffer:4.1.136.Final",
        "io.netty:netty-codec:4.1.136.Final",
        "io.netty:netty-codec-http:4.1.136.Final",
        "io.netty:netty-codec-http2:4.1.136.Final",
        "io.netty:netty-codec-socks:4.1.136.Final",
        "io.netty:netty-common:4.1.136.Final",
        "io.netty:netty-handler:4.1.136.Final",
        "io.netty:netty-handler-proxy:4.1.136.Final",
        "io.netty:netty-resolver:4.1.136.Final",
        "io.netty:netty-transport:4.1.136.Final",
        "io.netty:netty-transport-native-unix-common:4.1.136.Final",
        "org.bouncycastle:bcprov-jdk18on:1.84",
        "org.bouncycastle:bcpkix-jdk18on:1.84",
        "org.bouncycastle:bcutil-jdk18on:1.84",
        "org.bitbucket.b_c:jose4j:0.9.6",
        "org.jdom:jdom2:2.0.6.1",
        "com.google.protobuf:protobuf-java:3.25.5",
        "com.google.protobuf:protobuf-kotlin:3.25.5",
        "com.google.protobuf:protobuf-java-util:3.25.5",
        "org.apache.commons:commons-compress:1.26.0",

      )
    }
  }
}
