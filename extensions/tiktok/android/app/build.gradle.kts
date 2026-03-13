plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.tobz.aio_extension_tiktok"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tobz.aio_extension_tiktok"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    packagingOptions {
        resources {
            excludes += setOf(
                "META-INF/proguard/androidx-*.pro",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.md"
            )
        }
    }
}

dependencies {
    compileOnly(project(":extension-framework"))
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    implementation("com.google.code.gson:gson:2.10.1")
}
