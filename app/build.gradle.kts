import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
}

// Signing key paths live in the git-ignored local.properties; without them the default debug key is used
val localProperties = Properties().apply {
  rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
  namespace = "com.moonkeyboard"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.moonkeyboard"
    minSdk = 24
    targetSdk = 36
    versionCode = 3
    versionName = "0.3"
  }

  signingConfigs {
    localProperties.getProperty("signing.storeFile")?.let { path ->
      create("upload") {
        storeFile = file(path)
        storePassword = file(localProperties.getProperty("signing.storePasswordFile")).readText().trim()
        keyAlias = localProperties.getProperty("signing.keyAlias")
        keyPassword = storePassword
      }
    }
  }

  buildTypes {
    debug { signingConfigs.findByName("upload")?.let { signingConfig = it } }
    release {
      isMinifyEnabled = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
      signingConfigs.findByName("upload")?.let { signingConfig = it }
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures { compose = true }
}

dependencies {
  implementation(project(":engine"))
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Installs the baseline profiles bundled with Compose so its code is compiled ahead of time, not JIT-ed while typing
  implementation(libs.androidx.profileinstaller)
  debugImplementation(libs.androidx.compose.ui.tooling)
  testImplementation(libs.junit)
}
