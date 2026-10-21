plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Har GitHub build ka number naya version banta hai, taki purane app ke upar update ho sake.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.ary.focusguard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ary.focusguard"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // Har build ek hi key se sign hogi. Key badli toh update nahi hoga,
    // aur purana app hatana padega (jo guard rokta hai).
    signingConfigs {
        create("stable") {
            storeFile = file("../keystore/focusguard.jks")
            storePassword = "focusguard"
            keyAlias = "focusguard"
            keyPassword = "focusguard"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("stable")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
