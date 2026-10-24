// dsh-pet Android app 模块
// 源项目：https://github.com/PC2005-cloud/dsh-pet （MIT License，脚本保留原始版权声明）
// 说明：本 app 是 DSH 桌面宠物插件的 Android 原生移植——渲染层复用原项目 Web 代码
//       （打包为 assets/web/dsh-pet.js），Kotlin 只做平台桥接（悬浮窗触摸层 / 文件持久化 /
//       OkHttp 模型调用 / 系统通知 / 触觉反馈 / WebViewAssetLoader 素材映射）。
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dsh.pet"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dsh.pet"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "1.0.9"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // 素材（webm / config.jsonc / 字体 / 图标）以原始文件形式打包，不做压缩，
    // 避免 aapt 对 .webm/.ttf 二次处理；读取走 WebViewAssetLoader + AssetFile。
    androidResources {
        noCompress += listOf("webm", "ttf", "jsonc")
    }

    // 语言裁剪（保留中英，其余走默认）
    defaultConfig {
        resourceConfigurations += listOf("zh", "en")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    // WebViewAssetLoader：把 /dsh-pet-7340/pic、/font、/thumb、config.jsonc 映射到 assets
    implementation("androidx.webkit:webkit:1.11.0")
    // 模型调用（OpenAI 兼容协议）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}