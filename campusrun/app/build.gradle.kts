import org.gradle.api.tasks.Sync
import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 站点地址不写进仓库：本地放 `campusrun/target.properties`（已 gitignore），CI 用环境变量注入。
 * 缺省为空，App 会提示"未配置站点"，而不是拿空地址去发请求。
 */
val targetProperties = Properties().apply {
    val file = rootProject.file("target.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun targetValue(env: String, key: String): String =
    (System.getenv(env) ?: targetProperties.getProperty(key) ?: "").trim()

fun configString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val targetCasHost = targetValue("TARGET_CAS_HOST", "target.casHost")
val targetPlatformHost = targetValue("TARGET_PLATFORM_HOST", "target.platformHost")
val targetPlatformEntry = targetValue("TARGET_PLATFORM_ENTRY", "target.platformEntry")
val targetLoginJsPath = targetValue("TARGET_LOGIN_JS_PATH", "target.loginJsPath")

/**
 * Release 签名：CI 从 Secrets 注入，本地可用 `campusrun/keystore.properties`（已 gitignore）。
 * 更新功能靠覆盖安装，签名必须跨版本一致；没配置时仍然能构建未签名的 release，
 * CI 会退回 debug 签名（见 .github/workflows/build.yml）。
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun keystoreValue(key: String): String? =
    (System.getenv(key) ?: keystoreProperties.getProperty(key))?.takeIf { it.isNotBlank() }

val releaseStoreFile = keystoreValue("CAMPUSRUN_KEYSTORE_PATH")
    ?.let { rootProject.file(it) }
    ?.takeIf { it.exists() }
val releaseStorePassword = keystoreValue("CAMPUSRUN_KEYSTORE_PASSWORD")
val releaseKeyAlias = keystoreValue("CAMPUSRUN_KEY_ALIAS")
val releaseKeyPassword = keystoreValue("CAMPUSRUN_KEY_PASSWORD")
val hasReleaseSigning = releaseStoreFile != null &&
    releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null

/**
 * 版本号以 versionName 为唯一来源：
 * versionCode = major × 10000 + minor × 100 + patch。
 * 例如 0.1.0 → 100，0.1.1 → 101，0.2.0 → 200，1.0.0 → 10000。
 * minor 与 patch 必须小于 100，避免跨位进位冲突。
 */
val appVersionName = "0.1.0"
val appVersionCode = appVersionName
    .substringBefore('-')
    .substringBefore('+')
    .split('.')
    .let { parts ->
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
        require(minor in 0..99 && patch in 0..99) {
            "versionName 的 minor/patch 必须在 0..99 之间：$appVersionName"
        }
        major * 10_000 + minor * 100 + patch
    }

android {
    namespace = "com.happyrun.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.happyrun.app"
        minSdk = 24
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName

        buildConfigField("String", "TARGET_CAS_HOST", configString(targetCasHost))
        buildConfigField("String", "TARGET_PLATFORM_HOST", configString(targetPlatformHost))
        buildConfigField("String", "TARGET_PLATFORM_ENTRY", configString(targetPlatformEntry))
        buildConfigField("String", "TARGET_LOGIN_JS_PATH", configString(targetLoginJsPath))
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        buildConfig = true
    }

    packaging {
        // OpenVPN 的启动器是"伪装成 .so 的可执行文件"，必须解压成真实文件才能 exec（Android 10+ 不允许从临时目录执行）
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/libovpnexec.so"
        }
    }
}

// 轨迹模板的唯一来源是 campusrun/templates/；构建前同步到 assets，避免手工复制出现两份不一致的 JSON
val syncTemplates = tasks.register<Sync>("syncTemplates") {
    from(rootProject.file("templates")) {
        include("stadiums.json")
        include("*/stadium.json")
        include("*/geometry.json")
    }
    into(layout.projectDirectory.dir("src/main/assets/templates"))
}

/**
 * VPN 配置整份走注入，仓库里不留任何内容（远端、CA、tls-auth 密钥都在里面）。
 *
 * 来源按优先级：`campusrun/vpn-profile.ovpn`（本机，已 gitignore）→ 环境变量
 * `VPN_PROFILE_B64`（整份配置的 base64，给 CI 用）。两者都没有时生成目录留空，
 * App 会在连接时明确报"未配置 VPN 配置"，而不是拿半截配置去连。
 */
val syncVpnProfile = tasks.register("syncVpnProfile") {
    val localFile = rootProject.file("vpn-profile.ovpn")
    val outputDir = layout.buildDirectory.dir("generated/vpn-assets")

    inputs.property("vpnProfilePresent", localFile.exists())
    inputs.property("vpnProfileLength", if (localFile.exists()) localFile.length() else 0L)
    outputs.dir(outputDir)

    doLast {
        val out = outputDir.get().file("openvpn/student.ovpn").asFile
        val envProfile = System.getenv("VPN_PROFILE_B64")

        val profile = when {
            localFile.exists() && localFile.length() > 0L ->
                localFile.readText(Charsets.UTF_8)

            !envProfile.isNullOrBlank() ->
                String(Base64.getDecoder().decode(envProfile), Charsets.UTF_8)

            else -> null
        }

        if (profile == null) {
            out.parentFile?.mkdirs()
            out.delete()
            return@doLast
        }

        out.parentFile.mkdirs()
        out.writeText(
            profile.removePrefix("\uFEFF").replace("\r\n", "\n"),
            Charsets.UTF_8,
        )
    }
}

// 生成后的配置作为额外的 assets 源目录参与打包（仓库里没有同名文件，不会冲突）
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/vpn-assets"))

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(syncTemplates, syncVpnProfile)
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.1")
    implementation("com.google.android.material:material:1.11.0")
    // document-start 注入 navigator.geolocation 依赖该库
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    // 原生换票：拿 WebView 里现成的 CASTGC 自己走一遍 CAS，不经界面
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // 原生 CAS 登录页解析（form#loginForm / 二验表单）
    implementation("org.jsoup:jsoup:1.17.2")
    // 多账号的密码只落在这份加密存储里（主密钥在 AndroidKeyStore）
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
