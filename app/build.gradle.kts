import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
}

// Clave de firma propia (la crea scripts/crear-clave-firma.ps1; keystore.properties
// no se sube a git). Sin ella, "release" se firma con la clave de depuración.
val keystoreProps = Properties().apply {
  val f = rootProject.file("keystore.properties")
  if (f.exists()) f.inputStream().use { load(it) }
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

  signingConfigs {
    if (keystoreProps.getProperty("storeFile") != null) {
      create("release") {
        storeFile = file(keystoreProps.getProperty("storeFile"))
        storePassword = keystoreProps.getProperty("storePassword")
        keyAlias = keystoreProps.getProperty("keyAlias")
        keyPassword = keystoreProps.getProperty("keyPassword")
      }
    }
  }

  buildTypes {
    release {
      // Sin ofuscar: el puente JavaScript ↔ Android depende de nombres de métodos
      // y así la versión publicada se comporta igual que la probada.
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
