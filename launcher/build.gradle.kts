plugins {
    id("com.android.application")
}

// Same numbering as the probe: the GitHub Actions run number, so every build installs as an update.
val ciRun = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.airodynamic.adas"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.airodynamic.adas"
        minSdk = 28
        targetSdk = 25          // same reasons as the probe (no permission pop-ups, DiLink behaviour)
        versionCode = ciRun
        versionName = "0.4.$ciRun"   // Phase 4
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        create("fixed") {
            storeFile = rootProject.file("keystore/adas-atto3.jks")
            storePassword = "adasatto3"
            keyAlias = "adas"
            keyPassword = "adasatto3"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("fixed") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/**", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    // Self-update through the car's own ADB (Apache 2.0)
    implementation("dev.mobile:dadb:1.2.8") {
        exclude(group = "org.junit.platform")
        exclude(group = "org.junit.jupiter")
        exclude(group = "org.opentest4j")
        exclude(group = "org.apiguardian")
    }
}
