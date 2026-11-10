import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Pocket 版本规则，详见 android-native/VERSIONING.md：
 * 正式版 versionName = <major>.<minor>.<patch>，versionCode 取该基线最大值（build = 99）
 * 测试版 versionName = <major>.<minor>.<patch>-<channel>.<build>，build = 1..98
 * versionCode = (major * 10000 + minor * 100 + patch) * 100 + build
 * 例：0.1.21 -> (0 * 10000 + 1 * 100 + 21) * 100 + 99 = 12199
 *     0.1.21-internal.1 -> (0 * 10000 + 1 * 100 + 21) * 100 + 1 = 12101
 */
fun pocketVersionCodeOf(versionName: String): Int {
    val core = versionName.substringBefore('-')
    val parts = core.split('.')
    require(parts.size == 3) { "versionName 必须形如 0.1.21 或 0.1.21-internal.1：$versionName" }
    val numbers = parts.map { it.toIntOrNull() ?: error("versionName 含非数字段：$versionName") }
    val build = if (versionName.contains('-')) {
        val suffix = versionName.substringAfter('-')
        require('.' in suffix) { "测试版 versionName 必须形如 0.1.21-internal.1：$versionName" }
        suffix.substringAfterLast('.').toIntOrNull() ?: error("测试版 build 序号含非数字：$versionName")
    } else {
        99
    }
    val (major, minor, patch) = numbers
    require(major in 0..99 && minor in 0..99 && patch in 0..99 && build in 1..99) {
        "versionName 各段必须在 0..99、build 在 1..99：$versionName"
    }
    return (major * 10_000 + minor * 100 + patch) * 100 + build
}

// 正式版默认版本号；测试包构建时传 -PpocketVersionName=0.1.26-internal.1 覆盖，不改文件。
val pocketVersionName = providers.gradleProperty("pocketVersionName").orNull ?: "0.1.26"
val pocketVersionCode = pocketVersionCodeOf(pocketVersionName)

val signingProperties = Properties()
val signingPropertiesFile = file("signing.properties")
if (signingPropertiesFile.exists()) {
    FileInputStream(signingPropertiesFile).use(signingProperties::load)
}

android {
    namespace = "com.profer.pocket.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.profer.pocket"
        minSdk = 26
        targetSdk = 35
        versionCode = pocketVersionCode
        versionName = pocketVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 正式版用发布签名，debug 用默认签名；同 applicationId 会因签名不一致装不上。
        // 标签名用占位符区分，避免桌面上两个 Profer 图标分不清。
        manifestPlaceholders["appLabel"] = "@string/app_name"
    }

    signingConfigs {
        if (signingPropertiesFile.exists()) {
            create("release") {
                storeFile = file(signingProperties.getProperty("storeFile"))
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // 与正式版共存：包名加 .debug 后缀，不覆盖也不冲突。
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appLabel"] = "Profer Debug"
        }
        release {
            isMinifyEnabled = false
            if (signingPropertiesFile.exists()) signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        /**
         * 体验包：与正式版同一套优化（R8 + 资源裁剪），但用 debug 签名并加 `.internal` 后缀。
         *
         * 用途是「在真机上按正式版的表现验收」：正式包没有签名配置时打不出可安装的 APK，
         * 而 debug 包不优化（无内联、Compose 组合期检查、全量 Log），帧率和内存都不代表线上。
         * 独立 applicationId 让它与正式版、debug 版同时安装，同机对比同一路径的掉帧。
         */
        create("internal") {
            initWith(getByName("release"))
            applicationIdSuffix = ".internal"
            manifestPlaceholders["appLabel"] = "Profer Test"
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // 依赖模块也一起检：feature 模块的问题不该等到合到 app 才暴露。
        checkDependencies = true
        checkReleaseBuilds = true
        abortOnError = true
        // 已知技术债（Compose 文案未资源化）不在这里压制；
        // 真出现 HardcodedText 级别的错误，宁可让构建失败。
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

/**
 * 体验包必须使用测试基线版本号（如 `0.1.26-internal.1`）。
 *
 * 同一个 versionName 只能对应一个 APK：正式包 `0.1.26` 发布后，体验包若沿用会破坏版本追溯。
 * 这条检查只在真正构建 internal 任务时触发，不影响 debug / release / 单测。
 */
val requestedInternal = gradle.startParameter.taskNames.any { it.contains("Internal") }
check(!requestedInternal || pocketVersionName.contains('-')) {
    "体验包必须传测试版本号，例如 -PpocketVersionName=0.1.26-internal.1（当前为 $pocketVersionName）"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:sessions"))
    implementation(project(":feature:connect"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.window)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
