plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.ksp)
    kotlin("plugin.serialization") version "2.0.21"
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.odinga.spotune"
    compileSdk {
        version = release(36)
    }

    buildFeatures {
        buildConfig = true
    }
    
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    defaultConfig {
        applicationId = "com.odinga.spotune"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.5.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.add("arm64-v8a")
        }
    }
    
    signingConfigs {
        create("release") {
            storeFile = file("/home/media/certs/uvo_signing_key.jks")
            storePassword = "odinga"
            keyAlias = "uvo"
            keyPassword = "odinga"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

ksp {
    //arg("room.incremental", "false")
    arg("room.schemaLocation", "$projectDir/schemas")
}

configurations.configureEach {
    resolutionStrategy {
        force("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
        force("org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3")
        force("org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.7.3")
        force("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.7.3")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.webkit:webkit:1.13.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(libs.androidx.media3.common)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("androidx.room:room-runtime:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation(libs.hilt)
    ksp(libs.hilt.compiler)
    implementation("com.github.jens-muenker:fuzzywuzzy-kotlin:1.0.1")
    implementation("org.jsoup:jsoup:1.22.1")
    implementation("commons-io:commons-io:2.5")
    implementation("org.apache.commons:commons-compress:1.12")
}
