import java.nio.file.Files

plugins {
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    android {
        namespace = "actron.example.weather.shared"
        compileSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11 }
    }
    jvm()
    js(IR) {
        browser()
        binaries.executable()
    }
    sourceSets {
        commonMain.dependencies {
            implementation(project(":actron-statechart"))
            implementation(project(":actron-compose"))
            implementation(project(":actron-timetravel-compose"))
            implementation("com.arkivanov.decompose:decompose:3.5.0")
            implementation("com.arkivanov.decompose:extensions-compose:3.5.0")
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
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:${libs.versions.coroutines.get()}")
            implementation("io.ktor:ktor-client-cio:3.5.0")
        }
        androidMain.dependencies {
            implementation("io.ktor:ktor-client-okhttp:3.5.0")
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

// An open desktop app must not read JARs that another Gradle build can replace.
tasks.withType<JavaExec>().configureEach {
    if (name != "run") return@configureEach
    val isolatedClasspath = objects.fileCollection()
    doFirst {
        val snapshot = Files.createTempDirectory("actron-weather-run-").toFile()
        val copies =
            classpath.files.mapIndexed { index, source ->
                val target = snapshot.resolve("$index/${source.name}")
                if (source.isDirectory) {
                    source.copyRecursively(target, overwrite = false)
                } else {
                    target.parentFile.mkdirs()
                    source.copyTo(target, overwrite = false)
                }
                target
            }
        isolatedClasspath.setFrom(copies)
        classpath = isolatedClasspath
        logger.lifecycle("Weather runs from an isolated classpath snapshot: $snapshot")
    }
    doLast {
        // Every classpath entry is copied under its own index directory in doFirst.
        classpath.files
            .first()
            .parentFile.parentFile
            .deleteRecursively()
    }
}
