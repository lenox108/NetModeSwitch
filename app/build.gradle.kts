plugins {
    alias(libs.plugins.android.application)
}

android {
    // hev-socks5-tunnel is run as a program by the root shell, so it has to exist as a file.
    packaging { jniLibs { useLegacyPackaging = true } }
    namespace = "com.netmode.switcher"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.netmode.switcher"
        minSdk = 34
        targetSdk = 36
        versionCode = 74
        versionName = "3.19"
    }

    // One code base, two builds: "modern" for Android 14+ as it always was, "a10" down to
    // Android 10, where every newer API is behind a version check (see Compat).
    flavorDimensions += "api"
    productFlavors {
        create("modern") {
            dimension = "api"
        }
        create("a10") {
            dimension = "api"
            minSdk = 29
            versionNameSuffix = "-a10"
        }
    }

    buildFeatures {
        // The Shizuku user service talks over AIDL.
        aidl = true
        buildConfig = true
        viewBinding = true
    }

    buildTypes {
        release {
            // R8 keep rules live in proguard-rules.pro: the AIDL stubs and the root service
            // are only ever referenced across a process boundary, so R8 cannot see them.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Personal build: sign release with the debug key so it is installable directly.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    androidResources {
        localeFilters += listOf("en", "ru")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.libsu.core)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    testImplementation(libs.junit)
}
