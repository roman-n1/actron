plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    js(IR) {
        browser {
            commonWebpackConfig { outputFileName = "weather.js" }
        }
        binaries.executable()
    }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig { outputFileName = "weather.js" }
        }
        binaries.executable()
    }
    sourceSets {
        commonMain.dependencies {
            implementation(project(":weather:debugTools"))
            implementation(compose.ui)
            implementation(libs.decompose.core)
            implementation("io.ktor:ktor-client-core:3.5.0")
        }
    }
}
