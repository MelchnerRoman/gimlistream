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
        // Single source of truth: ../Cargo.toml. versionCode = major*10000 + minor*100 + patch.
        val v = Regex("""(?m)^version = "(\d+)\.(\d+)\.(\d+)"""").find(file("../Cargo.toml").readText())!!.groupValues
        versionCode = v[1].toInt() * 10000 + v[2].toInt() * 100 + v[3].toInt()
        versionName = "${v[1]}.${v[2]}.${v[3]}"
    }

    buildTypes {
        release {
            // ponytail: signed with the local debug key so the APK can be sideloaded; use a real keystore for Play Store.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}
