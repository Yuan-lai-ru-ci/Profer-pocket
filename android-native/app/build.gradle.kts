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

val pocketVersionName = "0.1.21"
val pocketVersionCode = pocketVersionCodeOf(pocketVersionName)

/**
 * 测试通道（AList）地址只在本地注入，不进仓库、不进公开分发的正式包：
 * 1. 在 android-native/local.properties 写 proferInternalUpdateBaseUrl=http://<host>/<dir>/
 * 2. 或构建时传 -PproferInternalUpdateBaseUrl=...
 * 正式包构建必须加 -PpocketOfficialBuild=true，地址不会写进 APK。
 * 未配置时该值为空字符串，连点版本号 10 次只提示测试通道未配置。
 */
val pocketLocalProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) localPropertiesFile.inputStream().use { load(it) }
}
val pocketOfficialBuild = providers.gradleProperty("pocketOfficialBuild").isPresent
val pocketInternalUpdateBaseUrl = if (pocketOfficialBuild) {
    ""
} else {
    providers.gradleProperty("proferInternalUpdateBaseUrl").orNull
        ?: pocketLocalProperties.getProperty("proferInternalUpdateBaseUrl")
        ?: ""
}

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
        buildConfigField("String", "INTERNAL_UPDATE_BASE_URL", "\"$pocketInternalUpdateBaseUrl\"")
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
        release {
            isMinifyEnabled = false
            if (signingPropertiesFile.exists()) signingConfig = signingConfigs.getByName("release")
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

    buildFeatures {
        compose = true
        buildConfig = true
    }
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
}
