import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// ---------------------------------------------------------------- 签名
// 密钥与口令放在工程根目录的 keystore.properties（已 gitignore，绝不入库）。
// 这个文件不存在时（比如别人 clone 下来），release 就退回未签名包，
// 而不是让构建直接失败——debug 构建任何时候都不受影响。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseSigning = keystorePropsFile.exists() &&
    keystoreProps.getProperty("storeFile") != null

android {
    namespace = "com.novelreader.pdf"
    // Android 15
    compileSdk = 35

    defaultConfig {
        applicationId = "com.novelreader.pdf"
        // 只在自己用的 Android 14 / 15 设备上跑，直接砍掉历史包袱
        minSdk = 34
        targetSdk = 35
        versionCode = 10
        versionName = "2.2.4"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                // storeFile 的路径写在 keystore.properties 里，相对工程根目录
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 没有 keystore.properties 时不设签名，产出 -unsigned 包（行为与之前一致）
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

ksp {
    // 导出 Room schema，方便以后加字段时写 Migration
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    // ---------- AndroidX 基础 ----------
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.documentfile)
    implementation(libs.google.material)

    // ---------- 生命周期 / 协程 ----------
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // ---------- 持久化：Room ----------
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // 章节目录的导出/导入/预置仍用 JSON
    implementation(libs.kotlinx.serialization.json)

    // ---------- OCR：只用于「自动生成目录」时识别页眉标题 ----------
    implementation(libs.mlkit.text.chinese)

    // ---------- JVM 单元测试 ----------
    testImplementation(libs.junit)

    // ---------- PDF 渲染 ----------
    // 只用系统 android.graphics.pdf.PdfRenderer，无任何第三方 PDF 库、无 native so
}
