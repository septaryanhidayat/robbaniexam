plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.ryan.robbaniexam"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ryan.robbaniexam"
        minSdk = 24
        targetSdk = 35
        versionCode = 4
        versionName = "2.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystoreFile = file("robbani-release.jks")
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = System.getenv("ROBBANI_KEYSTORE_PASS") ?: "robbani2026"
                keyAlias = System.getenv("ROBBANI_KEY_ALIAS") ?: "robbani"
                keyPassword = System.getenv("ROBBANI_KEY_PASS") ?: "robbani2026"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val releaseSign = signingConfigs.findByName("release")
            if (releaseSign?.storeFile?.exists() == true) {
                signingConfig = releaseSign
            }
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
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.activity.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.ktx)

    // CameraX & QR Scanner
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.zxing.embedded)

    // Networking & Utilities
    implementation(libs.gson)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
