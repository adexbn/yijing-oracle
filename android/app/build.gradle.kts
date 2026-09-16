plugins {
    id("com.android.application")
}

android {
    namespace = "com.yijing.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.yijing.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"
    }

    buildFeatures {
        // PerfPanel 用 BuildConfig.DEBUG 决定调试图标是否挂出来
        //（埋点本身另有开关，默认关闭，见 PerfTrace.enabled）
        buildConfig = true
    }

    buildTypes {
        debug {
            // debug 包：结果页底部挂调试面板，版本号带 -debug 便于区分；
            // 但埋点默认仍是关闭的，需要时在面板里手动打开。
            isMinifyEnabled = false
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("cn.6tail:lunar:1.7.7")

    // 本地小模型推理（llama.cpp）
    implementation("dev.ffmpegkit-maintained:llama-android:0.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
}