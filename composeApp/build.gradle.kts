import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties

// Supabase URL/key come from local.properties (never committed) and are baked into both builds,
// so the phone only needs an email + password to join the shared database.
val localProps = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
// Public release builds (-Pfifteen.public=true) leave them out, so each user connects their own project.
val publicBuild = providers.gradleProperty("fifteen.public").orNull == "true"
val supabaseUrl = if (publicBuild) "" else localProps.getProperty("supabase.url", "").trim()
val supabaseKey = if (publicBuild) "" else localProps.getProperty("supabase.key", "").trim()

val generateConfig = tasks.register("generateFifteenConfig") {
    val outDir = layout.buildDirectory.dir("generated/fifteen/kotlin")
    inputs.property("url", supabaseUrl)
    inputs.property("key", supabaseKey)
    outputs.dir(outDir)
    doLast {
        val file = outDir.get().file("app/fifteen/BuildConfig.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "package app.fifteen\n\n" +
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
                implementation("net.java.dev.jna:jna-platform:5.15.0")
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
    namespace = "app.fifteen"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.fifteen"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.1.0"
    }
    signingConfigs {
        localProps.getProperty("release.storeFile")?.let { path ->
            create("release") {
                storeFile = file(path)
                storePassword = localProps.getProperty("release.storePassword")
                keyAlias = localProps.getProperty("release.keyAlias")
                keyPassword = localProps.getProperty("release.keyPassword")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
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
        mainClass = "app.fifteen.DesktopMainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Fifteen"
            packageVersion = "1.1.0"
            modules("java.prefs", "java.net.http", "jdk.crypto.ec", "jdk.unsupported")
            // A small app: a capped heap and the simple collector keep memory low and give it back to Windows.
            // TieredStopAtLevel=1 keeps only the light compiler: measured about 40% less start-up CPU
            // (36 s -> 19-23 s of CPU time), with the same idle CPU.
            jvmArgs += listOf("-Xms16m", "-Xmx256m", "-XX:+UseSerialGC", "-Xss512k", "-XX:TieredStopAtLevel=1", "-XX:ReservedCodeCacheSize=32m")
            windows {
                menu = true
                shortcut = true
                perUserInstall = true
                menuGroup = "Fifteen"
                upgradeUuid = "8E1C4B6A-2D3F-4A9B-B7C1-5F0E9D2A6C38"
            }
            description = "Fifteen-minute focus tracker"
            vendor = "Personal Project"
        }
    }
}
