import java.util.Properties
import groovy.json.JsonSlurper

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("rust")
}

val tauriProperties = Properties().apply {
    val propFile = file("tauri.properties")
    if (propFile.exists()) {
        propFile.inputStream().use { load(it) }
    }
}

// CI nightlies need increasing install versions even when the app's base
// version is unchanged. Local builds retain Tauri's versioning.
val nightlyVersionCode = System.getenv("LUMIVERSE_ANDROID_VERSION_CODE")?.let {
    it.toIntOrNull()?.takeIf { code -> code in 1..2100000000 }
        ?: error("LUMIVERSE_ANDROID_VERSION_CODE must be between 1 and 2100000000")
}
val nightlyVersionName = System.getenv("LUMIVERSE_ANDROID_VERSION_NAME")?.also {
    require(it.matches(Regex("[A-Za-z0-9.+-]{1,128}"))) { "Invalid LUMIVERSE_ANDROID_VERSION_NAME" }
}
require((nightlyVersionCode == null) == (nightlyVersionName == null)) {
    "Set both Android nightly version variables together"
}

// Optional client configuration. Provider/service-account keys never enter the APK.
val firebaseConfigFile = System.getenv("LUMIVERSE_ANDROID_FIREBASE_CONFIG")?.let { file(it) }
    ?: file("../../../../../firebase/google-services.json")
val firebaseConfig = firebaseConfigFile.takeIf { it.isFile }?.let {
    JsonSlurper().parse(it) as Map<*, *>
}

android {
    compileSdk = 36
    buildToolsVersion = "36.1.0"
    namespace = "chat.lumiverse.prototype"
    defaultConfig {
        manifestPlaceholders["usesCleartextTraffic"] = "false"
        applicationId = "chat.lumiverse.prototype"
        minSdk = 29
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = nightlyVersionCode ?: tauriProperties.getProperty("tauri.android.versionCode", "1").toInt()
        versionName = nightlyVersionName ?: tauriProperties.getProperty("tauri.android.versionName", "1.0")
        firebaseConfig?.let { config ->
            val project = config["project_info"] as Map<*, *>
            val client = (config["client"] as List<*>).map { it as Map<*, *> }.firstOrNull {
                val info = it["client_info"] as Map<*, *>
                (info["android_client_info"] as Map<*, *>)["package_name"] == applicationId
            } ?: error("Firebase configuration must contain the Android application package")
            resValue("string", "google_app_id", (client["client_info"] as Map<*, *>)["mobilesdk_app_id"].toString())
            resValue("string", "gcm_defaultSenderId", project["project_number"].toString())
            resValue("string", "project_id", project["project_id"].toString())
            resValue("string", "google_api_key", ((client["api_key"] as List<*>).first() as Map<*, *>)["current_key"].toString())
        }
    }
    buildTypes {
        getByName("debug") {
            manifestPlaceholders["usesCleartextTraffic"] = "true"
            isDebuggable = true
            isJniDebuggable = true
            isMinifyEnabled = false
            packaging {
                jniLibs.keepDebugSymbols.add("*/arm64-v8a/*.so")
                jniLibs.keepDebugSymbols.add("*/armeabi-v7a/*.so")
                jniLibs.keepDebugSymbols.add("*/x86/*.so")
                jniLibs.keepDebugSymbols.add("*/x86_64/*.so")
            }
        }
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(
                *fileTree(".") { include("**/*.pro") }
                    .plus(getDefaultProguardFile("proguard-android-optimize.txt"))
                    .toList().toTypedArray()
            )
        }
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        buildConfig = true
    }
    sourceSets.getByName("main").java.srcDir("../../../../../src")
    sourceSets.getByName("main").java.exclude("**/MainActivity.java")
    packaging.jniLibs.useLegacyPackaging = true
}

rust {
    rootDirRel = "../../../"
}

dependencies {
    implementation("androidx.work:work-runtime:2.10.5")
    implementation("com.google.firebase:firebase-messaging:24.1.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.0")
    androidTestImplementation("androidx.work:work-testing:2.10.5")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

apply(from = "tauri.build.gradle.kts")
