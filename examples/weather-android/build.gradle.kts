plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.composeMultiplatform)
}

android {
    namespace = "actron.example.weather.android"
    compileSdk =
        libs.versions.android.compileSdk
            .get()
            .toInt()
    defaultConfig {
        applicationId = "actron.example.weather"
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 1
        versionName = "1.0"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11 } }

dependencies {
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.11.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation(project(":weather-example"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("com.arkivanov.decompose:decompose:3.5.0")
    implementation("io.ktor:ktor-client-core:3.5.0")
    implementation(compose.material3)
}
