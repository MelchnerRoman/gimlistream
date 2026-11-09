plugins {
    id("com.android.application") version "9.5.1"
}

android {
    namespace = "com.gimlistream"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.gimlistream"
        minSdk = 26 // Android 8: adaptive icon only, no legacy PNGs
        targetSdk = 37 // Android 17
        versionCode = 4
        versionName = "0.4.0"
    }

    buildTypes {
        release {
            // ponytail: signed with the local debug key so the APK can be sideloaded; use a real keystore for Play Store.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}
