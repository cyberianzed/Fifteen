import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties

// Supabase URL/key come from local.properties (never committed) and are baked into both builds,
// so the phone only needs an email + password to join the shared database.
val localProps = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
val supabaseUrl = localProps.getProperty("supabase.url", "").trim()
val supabaseKey = localProps.getProperty("supabase.key", "").trim()

val generateConfig = tasks.register("generateNimishamConfig") {
    val outDir = layout.buildDirectory.dir("generated/nimisham/kotlin")
    inputs.property("url", supabaseUrl)
    inputs.property("key", supabaseKey)
    outputs.dir(outDir)
    doLast {
        val file = outDir.get().file("app/nimisham/BuildConfig.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "package app.nimisham\n\n" +
                "internal object BakedConfig {\n" +
                "    const val SUPABASE_URL = \"${supabaseUrl.replace("\"", "")}\"\n" +
                "    const val SUPABASE_KEY = \"${supabaseKey.replace("\"", "")}\"\n" +
                "}\n"
        )
    }
}

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.application")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateConfig)
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
            }
        }
        // Both targets are JVMs: HTTP, files and java.time live here once.
        val jvmShared by creating { dependsOn(commonMain.get()) }
        androidMain.get().dependsOn(jvmShared)
        androidMain.dependencies {
            implementation("androidx.activity:activity-compose:1.10.1")
            implementation("androidx.core:core-ktx:1.15.0")
            implementation("androidx.lifecycle:lifecycle-service:2.8.7")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        val desktopMain by getting {
            dependsOn(jvmShared)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.1")
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(compose.desktop.uiTestJUnit4)
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

android {
    namespace = "app.nimisham"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.nimisham"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }
    buildTypes {
        getByName("release") { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

compose.desktop {
    application {
        mainClass = "app.nimisham.DesktopMainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Nimisham"
            packageVersion = "1.0.0"
            modules("java.prefs", "java.net.http", "jdk.crypto.ec", "jdk.unsupported")
            windows {
                menu = true
                shortcut = true
                perUserInstall = true
                menuGroup = "Nimisham"
                upgradeUuid = "8E1C4B6A-2D3F-4A9B-B7C1-5F0E9D2A6C38"
            }
            description = "Fifteen-minute focus tracker"
            vendor = "Personal Project"
        }
    }
}
