plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val versionCodeEnv = (System.getenv("VERSION_CODE") ?: "1").toInt()
val keystoreFile: String? = System.getenv("KEYSTORE_FILE")

android {
    namespace = "si.trax.turnback"
    compileSdk = 35

    defaultConfig {
        applicationId = "si.trax.turnback"
        minSdk = 26
        targetSdk = 35
        versionCode = versionCodeEnv
        versionName = "0.1.$versionCodeEnv"
    }

    signingConfigs {
        create("release") {
            if (keystoreFile != null) {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystoreFile != null) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("org.osmdroid:osmdroid-android:6.1.20")
}
