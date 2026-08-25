plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.kotlin.compose)
}

val channel = project.findProperty("channel")?.toString() ?: "stable"

android {
    namespace = "com.vcodec.smartencoder"
    compileSdk = 35
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.vcodec.smartencoder"
        if (channel == "dev") {
            applicationIdSuffix = ".dev"
            manifestPlaceholders["appName"] = "Smart Encoder-dev"
        } else {
            manifestPlaceholders["appName"] = "Smart Encoder"
        }
        minSdk = 26
        targetSdk = 34
        versionCode = 11
        versionName = if (channel == "dev") "2.7.9-dev" else "2.7.9"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
                abiFilters("arm64-v8a", "x86_64") // Modern 64-bit devices (ARM) & Emulators (x86_64)
            }
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    signingConfigs {
        // CI release signing: stable keystore provided via env vars (GitHub Secrets).
        // Falls back to the debug key for local development.
        create("ciRelease") {
            val ksPath = System.getenv("SIGNING_KEYSTORE_PATH")
            val ksPassword = System.getenv("SIGNING_KEYSTORE_PASSWORD")
            val keyAlias = System.getenv("SIGNING_KEY_ALIAS")
            val keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            if (ksPath != null && ksPassword != null && keyAlias != null && keyPassword != null && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = ksPassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val ci = signingConfigs.getByName("ciRelease")
            signingConfig = if (ci.storeFile != null) ci else signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Media3 Transformer & ExoPlayer
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.ui)

    // Background Processing
    implementation(libs.androidx.work.runtime.ktx)

    // Room Database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.compose.ui.tooling)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

