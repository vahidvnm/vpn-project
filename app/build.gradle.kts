import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val xrayCoreVersion = "v26.9.9"
val xrayCoreSha256 = "9ecf4c921568d8f4cb8550d3bafe08ff6f1d1984f45a6ad183dcdf52ee9302de"
val xrayCoreAar = layout.projectDirectory.file("libs/libv2ray.aar")
val downloadXrayCoreAar by tasks.registering {
    outputs.file(xrayCoreAar)
    outputs.upToDateWhen { false }
    doLast {
        val target = xrayCoreAar.asFile
        target.parentFile.mkdirs()

        fun sha256(file: java.io.File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        }

        if (target.isFile && sha256(target) == xrayCoreSha256) return@doLast
        if (target.exists() && !target.delete()) {
            throw GradleException("Could not remove the existing Xray AAR before verification.")
        }

        val url = "https://github.com/2dust/AndroidLibXrayLite/releases/download/$xrayCoreVersion/libv2ray.aar"
        val temporary = target.resolveSibling("${target.name}.download")
        temporary.delete()
        try {
            val connection = URI(url).toURL().openConnection().apply {
                connectTimeout = 15_000
                readTimeout = 60_000
            }
            connection.getInputStream().use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            val actualSha256 = sha256(temporary)
            if (actualSha256 != xrayCoreSha256) {
                throw GradleException(
                    "Xray AAR SHA-256 mismatch: expected $xrayCoreSha256, got $actualSha256."
                )
            }
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }
}

tasks.configureEach {
    if (name == "preBuild" || name == "preDebugBuild" || name == "preReleaseBuild") {
        dependsOn(downloadXrayCoreAar)
    }
}

android {
    namespace = "com.vpnproject.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vpnproject.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-dev"

        testInstrumentationRunner = "android.test.InstrumentationTestRunner"

        ndk {
            // Keep the debug APK practical for phone testing. The embedded Xray
            // AAR ships native libraries for multiple ABIs; the user's test
            // devices are modern arm64 phones, so CI builds the arm64 APK first.
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
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

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(files(xrayCoreAar))
    implementation("com.wireguard.android:tunnel:1.0.20230706")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("androidx.core:core:1.15.0")

    testImplementation("junit:junit:4.13.2")
}
