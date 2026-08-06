plugins {
  id("com.android.application")
  kotlin("android")
  kotlin("plugin.compose")
}

android {
  namespace = "org.skyphusion.prism.app"
  compileSdk = 35

  defaultConfig {
    applicationId = "org.skyphusion.prism"
    minSdk = 26
    targetSdk = 35
    versionCode = 10
    versionName = "0.4.1"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
      )
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures {
    compose = true
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

kotlin {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
  }
}

dependencies {
  implementation(project(":prism-kit"))

  val composeBom = platform("androidx.compose:compose-bom:2025.07.00")
  implementation(composeBom)
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.ui:ui-tooling-preview")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.material:material-icons-extended")
  implementation("androidx.activity:activity-compose:1.10.1")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
  implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
  implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
  implementation("androidx.security:security-crypto:1.0.0")
  implementation("io.coil-kt:coil-compose:2.7.0")
  implementation("com.android.billingclient:billing-ktx:7.1.1")

  debugImplementation("androidx.compose.ui:ui-tooling")
}
