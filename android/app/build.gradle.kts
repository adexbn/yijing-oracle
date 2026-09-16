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
        versionCode = 4
        versionName = "1.3"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            versionNameSuffix = "-debug"
        }

        /**
         * 发给测试同学用的包。
         *
         * 走 release 而不是 debug：BuildConfig.DEBUG=false、不带 `-debug` 版本后缀、
         * 清单里也没有 debuggable，别人拿到手就是普通应用，看不出是内部测试版。
         * 诊断入口在隐藏的设置页里（长按首页「我的」→「日志与诊断」）。
         *
         * 这里用默认的 debug 签名密钥签名，是为了让测试同学能在旧包上直接覆盖安装
         * （同 applicationId + 同签名才能原地升级）。正式上架前再换成正式密钥。
         */
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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