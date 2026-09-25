plugins {
    id("com.android.application")
}

android {
    namespace = "com.yijing.app"
    compileSdk = 37

    /**
     * NDK 版本必须固定：native 推理桥（src/main/cpp）要用 NDK 编译。
     * 本机若没把 NDK 装进 SDK，可在 android/local.properties 里补一行
     * `ndk.dir=<你的 NDK 绝对路径>` 覆盖；AGP 不会把它提交进仓库。
     */
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.yijing.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 4
        versionName = "1.3"

        ndk {
            // 只打这两个 ABI：jniLibs 里预编译的 MNN 3.6.1 只提供这两套，
            // 同时也能避免在 x86 模拟器上白编一份用不到的产物。
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                // MNN 的 Expr/LLM 头大量使用 C++17 与 RTTI，必须维持默认的
                // -frtti；-fno-exceptions 也不能加（引擎内部有 throw/catch）。
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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

    // 本地小模型推理：MNN 3.6.1（官方 Android 预编译包，CPU / OpenCL / Vulkan）
    // 预编译好的 .so 直接放在 src/main/jniLibs/<abi>/，JNI 桥由 src/main/cpp 现场编译，
    // 因此这里不再需要 llama.cpp 的 AAR。
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
}