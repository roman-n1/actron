import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.actron.publish)
}

group = "io.github.roman-n1"
version = libs.versions.actron.get()

kotlin {
    android {
        // Compose Resources must be packaged as Android assets for this KMP target.
        androidResources.enable = true
        namespace = "actron.timetravel.compose"
        compileSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()

        withHostTestBuilder {}.configure {}
        // Common Compose tests run on the host; device lifecycle tests live in actron-compose.

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }
    iosArm64()
    iosSimulatorArm64()
    jvm {
        attributes.attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 11)
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
            // Kotlin 2.4 includes the group in its default module name. Preserve the
            // existing JVM names of Compose-generated singleton lambda accessors.
            moduleName.set(project.name)
        }
    }
    // Web targets are browser-only, as in actron-compose: Skiko's web runtime has no Node loader.
    js(IR) {
        binaries.executable()
        browser()
    }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        binaries.executable()
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.components.resources)
            api(project(":actron-timetravel"))
            api(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
    }
}

publishConvention {
    artifactId.set("actron-timetravel-compose")
}

// The JDK macOS gesture API is platform-specific and not exported by default on modern JDKs.
tasks.withType<Test>().configureEach {
    if (System.getProperty("os.name", "").startsWith("Mac")) {
        jvmArgs("--add-exports=java.desktop/com.apple.eawt.event=ALL-UNNAMED")
    }
}

compose.resources {
    packageOfResClass = "actron.timetravel.compose.resources"
}

// Assertions use a reproducible baseline; locale integration tests override and restore it.
tasks.withType<Test>().configureEach {
    systemProperty("user.language", "en")
    systemProperty("user.country", "US")
}
