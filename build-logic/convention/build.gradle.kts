import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}

group = "actron.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.android.tools.common)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.vanniktech.maven.publish.gradlePlugin)
}

tasks {
    validatePlugins {
        enableStricterValidation = true
        failOnWarning = true
    }
}

gradlePlugin {
    plugins {
        register("publish") {
            id = "actron.publish"
            implementationClass = "actron.buildlogic.PublishConventionPlugin"
        }
    }
}

// Audit this independent build before generated Gradle/IDE ABI is introduced.
val actronRepository = rootProject.projectDir.parentFile
val nullabilityPolicyJar = project(":nullability-policy").layout.buildDirectory.file("libs/nullability-policy.jar")
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    dependsOn(":nullability-policy:compilerTest")
    inputs.files(nullabilityPolicyJar, actronRepository.resolve(".actron/nullability/strict"))
    compilerOptions.freeCompilerArgs.addAll(
        "-Xplugin=${nullabilityPolicyJar.get().asFile.absolutePath}",
        "-P",
        "plugin:actron-nullability:root=${actronRepository.absolutePath}",
    )
}
