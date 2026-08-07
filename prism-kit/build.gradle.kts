plugins {
  kotlin("jvm")
  kotlin("plugin.serialization")
}

group = "org.skyphusion.prism"
version = "1.0.0"

dependencies {
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
  implementation("com.squareup.okhttp3:okhttp:4.12.0")

  testImplementation(kotlin("test"))
  testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.test {
  useJUnitPlatform()
  testLogging {
    events("passed", "skipped", "failed")
  }
  // A green run that names no tests cannot tell "everything passed" from "nothing ran", and the
  // CI log printed neither a test name nor a count. Print the population, and refuse an empty
  // one: zero executed tests is a harness failure, never a pass.
  afterSuite(
    KotlinClosure2<TestDescriptor, TestResult, Unit>({ desc, result ->
      if (desc.parent == null) {
        println(
          "prism-kit tests: ${result.testCount} run, ${result.failedTestCount} failed, " +
            "${result.skippedTestCount} skipped",
        )
        if (result.testCount == 0L) {
          throw GradleException("No tests executed; an empty run is a harness failure, not a pass.")
        }
      }
    }),
  )
}

kotlin {
  jvmToolchain(17)
}
