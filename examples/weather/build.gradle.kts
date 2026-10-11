plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvm()
    js(IR) {
        browser()
        binaries.executable()
    }
    sourceSets {
        commonMain.dependencies {
            implementation(project(":actron-timetravel-compose"))
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
            implementation("io.ktor:ktor-client-core:3.5.0")
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
            implementation("io.ktor:ktor-client-mock:3.5.0")
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation("io.ktor:ktor-client-cio:3.5.0")
        }
        jsMain.dependencies {
            implementation("io.ktor:ktor-client-js:3.5.0")
        }
        jvmTest.dependencies {
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
    }
}

compose.desktop.application {
    mainClass = "actron.example.weather.MainKt"
}
