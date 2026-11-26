import java.security.SecureRandom
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// ---------- 构建时注入加密配置（服务器地址 + 门禁密钥） ----------
// 真实值来自 app/secrets.properties（gitignored，模板见 secrets.properties.example）；
// 以 AES-GCM 加密后打包进 BuildConfig，运行时解密，App 自动连接服务器。
// 无 secrets 文件时（如公开仓库裸构建），三项为空 → App 回退到手动配置页。

fun hexToBytes(hex: String): ByteArray =
    ByteArray(hex.length / 2) { i -> ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte() }

fun bytesToHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

fun encryptConfig(json: String, keyHex: String): String {
    val key = hexToBytes(keyHex)
    val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
    val ct = c.doFinal(json.toByteArray(Charsets.UTF_8))
    return bytesToHex(iv + ct)
}

fun keyHalves(keyHex: String): Pair<String, String> {
    // 密钥拆成两个异或半片打包，避免完整密钥以字符串形式出现在产物中（混淆级保护）
    val key = hexToBytes(keyHex)
    val mask = ByteArray(32).also { SecureRandom().nextBytes(it) }
    val other = ByteArray(32) { i -> (key[i].toInt() xor mask[i].toInt()).toByte() }
    return bytesToHex(mask) to bytesToHex(other)
}

val secretsFile = file("secrets.properties")
val secretsProps = if (secretsFile.exists()) Properties().apply {
    secretsFile.inputStream().use { load(it) }
} else null

val ldrServerUrl = secretsProps?.getProperty("ldr.serverUrl")?.trim() ?: ""
val ldrGate = secretsProps?.getProperty("ldr.gate")?.trim() ?: ""
val ldrEncKey = secretsProps?.getProperty("ldr.encKey")?.trim() ?: ""
val ldrAmapKey = secretsProps?.getProperty("ldr.amapKey")?.trim() ?: ""

// 签名配置：CI 用稳定密钥库（否则 runner 临时 debug 签名每次不同 → 应用内更新
// 覆盖安装失败 + 高德 Key 的 SHA1 绑定失效地图白屏）。本地无密钥库时回退默认 debug 签名。
val ldrKeystoreFile = secretsProps?.getProperty("ldr.keystoreFile")?.trim() ?: ""
val ldrKeystorePass = secretsProps?.getProperty("ldr.keystorePass")?.trim() ?: ""
val ldrKeystoreAlias = secretsProps?.getProperty("ldr.keystoreAlias")?.trim() ?: ""
val ldrKeystore = listOf(file(ldrKeystoreFile), rootProject.file(ldrKeystoreFile))
    .firstOrNull { ldrKeystoreFile.isNotBlank() && it.exists() }
val ldrHasSigning = ldrKeystore != null && ldrKeystorePass.isNotBlank() && ldrKeystoreAlias.isNotBlank()
if (secretsProps != null && ldrKeystoreFile.isNotBlank() && !ldrHasSigning) {
    throw GradleException("secrets.properties 中的签名密钥库配置不完整或文件不存在：ldr.keystoreFile=$ldrKeystoreFile")
}
logger.lifecycle(
    "LDRLife signing: " + if (ldrHasSigning) "custom keystore ${ldrKeystore!!.name} (stable SHA1)" else "default debug keystore (ephemeral)"
)

val ldrHasSecrets = ldrServerUrl.isNotBlank() && ldrGate.isNotBlank() && ldrEncKey.length == 64

if (secretsProps != null && !ldrHasSecrets) {
    throw GradleException("secrets.properties 存在但字段不完整：ldr.serverUrl / ldr.gate 必填，ldr.encKey 必须为 64 位 hex")
}

val ldrCfgEnc: String = if (ldrHasSecrets) encryptConfig("{\"url\":\"$ldrServerUrl\",\"gate\":\"$ldrGate\"}", ldrEncKey) else ""
// keyHalves 必须只调用一次：两次调用会生成两个不同掩码，导致运行时异或还原失败
val ldrHalves: Pair<String, String> = if (ldrHasSecrets) keyHalves(ldrEncKey) else ("" to "")
val ldrKeyA: String = ldrHalves.first
val ldrKeyB: String = ldrHalves.second

android {
    namespace = "com.mengpolar.ldrlife"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mengpolar.ldrlife"
        minSdk = 26
        targetSdk = 34
        versionCode = 13
        versionName = "0.0.13"

        buildConfigField("String", "LDR_CONFIG_ENC", "\"$ldrCfgEnc\"")
        buildConfigField("String", "LDR_KEY_A", "\"$ldrKeyA\"")
        buildConfigField("String", "LDR_KEY_B", "\"$ldrKeyB\"")
        manifestPlaceholders["amapKey"] = ldrAmapKey
    }

signingConfigs {
    if (ldrHasSigning) {
        create("ldr") {
            storeFile = ldrKeystore
            storePassword = ldrKeystorePass
            keyAlias = ldrKeystoreAlias
            keyPassword = ldrKeystorePass
        }
    }
}

buildTypes {
    debug {
        // 本地/CI debug 包统一用稳定签名，保证应用内更新可覆盖安装
        if (ldrHasSigning) signingConfig = signingConfigs.getByName("ldr")
    }
    release {
        // M0 暂不混淆；后续开启时补充 BouncyCastle/SQLCipher keep 规则
        isMinifyEnabled = false
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        if (ldrHasSigning) signingConfig = signingConfigs.getByName("ldr")
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
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 加密本地存储：Room + SQLCipher
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("net.zetetic:sqlcipher-android:4.6.1")
    implementation("androidx.sqlite:sqlite:2.4.0")

    // 纯 Java 加密实现（X25519 / Ed25519 / XChaCha20-Poly1305），免 JNI
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    // 配对码二维码
    implementation("com.google.zxing:core:3.5.3")
    // 高德 3D 地图（M2 位置共享；API Key 经 secrets.properties 注入 manifest）
    implementation("com.amap.api:3dmap:10.0.600")
}
