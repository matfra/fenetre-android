plugins {
    id("com.android.application")
}

android {
    namespace = "cam.fenetre.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "cam.fenetre.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    val cameraXVersion = "1.6.1"

    implementation("androidx.activity:activity-ktx:1.12.0-alpha08")
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-core:$cameraXVersion")
    implementation("androidx.camera:camera-extensions:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-service:2.10.0")
}
