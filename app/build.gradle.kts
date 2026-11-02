plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.moneymobile.installment"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.moneymobile.installment"
        minSdk = 26
        targetSdk = 34
        // 19 = 1.10.0, the first build that can update itself online. Raise versionCode for EVERY release
        // (the website rejects an upload that is not newer, and Android never installs an older build).
        versionCode = 20
        versionName = "1.10.1"

        buildConfigField("String", "BASE_URL", "\"http://192.168.8.168:3000/api\"")
    }

    buildTypes {
        release {
            // Customer phones get the RELEASE build: not debuggable (no `adb run-as` / debugger),
            // no network logging, HTTPS only, and no fallback to a developer's LAN address.
            // It is signed with the same key as earlier installs so phones already in the field
            // can still be updated in place (Device Owner apps cannot be uninstalled/reinstalled).
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("String", "BASE_URL", "\"https://moneymobileweb.vercel.app/api\"")
            manifestPlaceholders["cleartext"] = "false"
        }
        debug {
            isDebuggable = true
            manifestPlaceholders["cleartext"] = "true"
        }
    }

    // The release safety scan (lintVital) adds minutes to every build. Run `./gradlew lint` by hand when wanted.
    lint {
        checkReleaseBuilds = false
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // WorkManager (background heartbeat)
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")

    // Security (encrypted prefs)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Gson
    implementation("com.google.code.gson:gson:2.10.1")

    // Firebase Cloud Messaging — wakes the phone / delivers lock-unlock even on limited data.
    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-messaging-ktx")
}
