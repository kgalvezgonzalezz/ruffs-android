plugins {
  alias(libs.plugins.android.application)
}

android {
  namespace = "com.ruffs.companion"
  compileSdk = 37

  defaultConfig {
    applicationId = "com.ruffs.companion"
    minSdk = 26
    targetSdk = 36
    versionCode = 2
    versionName = "1.1"
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Firmado con la clave de depuración para poder instalarlo directamente.
      // Antes de distribuirlo, crea tu propio keystore (Build > Generate Signed App Bundle/APK).
      signingConfig = signingConfigs.getByName("debug")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures {
    buildConfig = true
    aidl = false
    shaders = false
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.activity.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.webkit)
  implementation(libs.androidx.swiperefreshlayout)
  implementation(libs.material)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.play.services.code.scanner)
}
