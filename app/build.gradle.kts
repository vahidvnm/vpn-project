import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val xrayCoreVersion = "v26.9.9"
val xrayCoreAar = layout.projectDirectory.file("libs/libv2ray.aar")
val downloadXrayCoreAar by tasks.registering {
    outputs.file(xrayCoreAar)
    doLast {
        val target = xrayCoreAar.asFile
        if (target.exists() && target.length() > 0L) return@doLast
        target.parentFile.mkdirs()
        val url = "https://github.com/2dust/AndroidLibXrayLite/releases/download/$xrayCoreVersion/libv2ray.aar"
        URI(url).toURL().openStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
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
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vpnproject.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-dev"

        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
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

    testImplementation("junit:junit:4.13.2")
}
