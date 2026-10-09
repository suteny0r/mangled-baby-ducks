import com.google.protobuf.gradle.id
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "com.suteny0r.mangledbabyducks"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.suteny0r.mangledbabyducks"
        minSdk = 26
        targetSdk = 36
        versionCode = 14
        versionName = "0.2.12"
    }

    // Release signing comes from local.properties (untracked): release.store.file,
    // release.store.password, release.key.alias, release.key.password. Without them the
    // release build is unsigned, which is fine for CI compile checks.
    val localProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val releaseStore = localProps.getProperty("release.store.file")?.let { file(it) }
    if (releaseStore != null && releaseStore.exists()) {
        signingConfigs {
            create("release") {
                storeFile = releaseStore
                storePassword = localProps.getProperty("release.store.password")
                keyAlias = localProps.getProperty("release.key.alias")
                keyPassword = localProps.getProperty("release.key.password")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
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
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.28.3"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                id("java") { option("lite") }
                id("kotlin") { option("lite") }
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.protobuf.kotlin.lite)
    implementation(libs.maplibre.sdk)
    implementation(libs.androidx.car.app)
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    implementation(libs.zxing.core)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
