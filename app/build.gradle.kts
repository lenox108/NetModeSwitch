plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.netmode.switcher"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.netmode.switcher"
        minSdk = 34
        targetSdk = 36
        versionCode = 10
        versionName = "1.0"
    }

    buildFeatures {
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
}
