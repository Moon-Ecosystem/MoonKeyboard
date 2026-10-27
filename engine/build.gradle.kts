import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin, no Android: layouts, language packs and decoding, testable on the JVM
plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

java {
  sourceCompatibility = JavaVersion.VERSION_11
  targetCompatibility = JavaVersion.VERSION_11
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_11 } }

dependencies {
  implementation(libs.kotlinx.serialization.json)
  testImplementation(libs.junit)
}
