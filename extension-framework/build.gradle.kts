plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.tobz.aio.extension"
    compileSdk = 34

    defaultConfig {
        minSdk = 23
        targetSdk = 34
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    // No external dependencies — pure interface
}
