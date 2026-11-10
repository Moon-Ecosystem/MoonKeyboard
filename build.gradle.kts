plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.spotless)
}

// ./gradlew spotlessCheck lints every Kotlin file with ktlint; spotlessApply fixes what it can
spotless {
  val ktlintVersion = libs.versions.ktlint.get()
  val style = mapOf(
    "indent_size" to "2",
    // IntelliJ's Kotlin style, the closest to how this code is already written
    "ktlint_code_style" to "intellij_idea",
    // Long lines are left to judgement
    "max_line_length" to "off",
    // Composables are named like classes
    "ktlint_function_naming_ignore_when_annotated_with" to "Composable",
    // The code's own style: short if/else and enums on one line, comments right under the line
    // above, a few related statements on one line, and several arguments to a line
    "ktlint_standard_multiline-if-else" to "disabled",
    "ktlint_standard_blank-line-before-declaration" to "disabled",
    "ktlint_standard_blank-line-between-when-conditions" to "disabled",
    "ktlint_standard_enum-wrapping" to "disabled",
    "ktlint_standard_statement-wrapping" to "disabled",
    "ktlint_standard_argument-list-wrapping" to "disabled",
  )
  kotlin {
    target("**/*.kt")
    targetExclude("**/build/**")
    ktlint(ktlintVersion).editorConfigOverride(style)
  }
  kotlinGradle {
    target("**/*.gradle.kts")
    targetExclude("**/build/**")
    ktlint(ktlintVersion).editorConfigOverride(style)
  }
}
