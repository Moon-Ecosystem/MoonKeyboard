import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Build-time only: compiles source word lists into the language-pack files the app ships
plugins {
  alias(libs.plugins.kotlin.jvm)
  application
}

java {
  sourceCompatibility = JavaVersion.VERSION_11
  targetCompatibility = JavaVersion.VERSION_11
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_11 } }

// -PmainClass=com.moonkeyboard.tools.EvaluateKt runs the accuracy evaluation instead
application { mainClass = providers.gradleProperty("mainClass").orElse("com.moonkeyboard.tools.BuildLexiconKt") }

dependencies { implementation(project(":engine")) }
