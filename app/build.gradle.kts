plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.yash.zapper"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.yash.zapper"
        minSdk = 22
        // Kept at 28: Fire OS is Android 7-11 based, and this keeps auto-launch on wake working
        // without the extra background restrictions newer targets bring.
        targetSdk = 28
        versionCode = 18
        versionName = "3.4"
    }

    // One fixed key for every build, so new versions install over old ones (Android rejects
    // updates signed with a different key). Personal sideloaded app, so the key lives in the repo.
    signingConfigs {
        create("zapper") {
            storeFile = file("zapper.jks")
            storePassword = "59YFHdpcOljoZ8rLTkV5d1cu"
            keyAlias = "zapper"
            keyPassword = "59YFHdpcOljoZ8rLTkV5d1cu"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("zapper")
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
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
}
