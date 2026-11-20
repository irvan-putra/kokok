plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "2.0.20"
}

android {
    namespace = "com.vanipy.kokok"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vanipy.kokok"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "0.2.0"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.maxHeapSize = "640m"; it.maxParallelForks = 1 }
    }

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.crypto.tink:tink-android:1.13.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
