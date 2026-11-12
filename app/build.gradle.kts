import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Each CI push increments GITHUB_RUN_NUMBER, so versionCode always grows and the
 * APK installs over the previous build (same signing key is committed in keystore/).
 */
val ciRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
val versionBase = System.getenv("VERSION_BASE")?.toIntOrNull() ?: 1000

val signingProps = Properties().apply {
    val f = rootProject.file("keystore/signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.fogofwar.game"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fogofwar.game"
        minSdk = 26
        targetSdk = 34
        versionCode = versionBase + ciRunNumber
        versionName = "1.0.$ciRunNumber"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/release.p12")
            storeType = "PKCS12"
            storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                ?: signingProps.getProperty("storePassword", "fogofwar123")
            keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                ?: signingProps.getProperty("keyAlias", "fogofwar")
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
                ?: signingProps.getProperty("keyPassword", "fogofwar123")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // Same key as release: a debug build still updates an installed install.
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.android.gms:play-services-nearby:19.3.0")

    testImplementation("junit:junit:4.13.2")
}
