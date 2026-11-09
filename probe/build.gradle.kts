plugins {
    id("com.android.application")
}

// Version code comes from the GitHub Actions run number, so every build is newer
// than the last one and installs as an update (your data is kept).
val ciRun = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.airodynamic.adasprobe"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.airodynamic.adasprobe"
        minSdk = 28
        // Kept low on purpose (same as OverDrive): no runtime permission pop-ups,
        // storage access works, and the shell helper behaves the same on DiLink.
        targetSdk = 25
        versionCode = ciRun
        versionName = "0.0.$ciRun"
    }

    signingConfigs {
        // One fixed key in the repo, so every new APK installs over the old one
        // without uninstalling (rule 3: never lose saved settings).
        create("fixed") {
            storeFile = rootProject.file("keystore/adas-atto3.jks")
            storePassword = "adasatto3"
            keyAlias = "adas"
            keyPassword = "adasatto3"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("fixed")
        }
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
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/**")
    }
}

dependencies {
    // Small ADB client (Apache 2.0) used to start our shell helper through the car's own ADB.
    implementation("dev.mobile:dadb:1.2.8")
}
