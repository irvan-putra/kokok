plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vanipy.kokok"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vanipy.kokok"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("com.google.crypto.tink:tink-android:1.13.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
