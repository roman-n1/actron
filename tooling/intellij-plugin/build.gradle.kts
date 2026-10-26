import org.jetbrains.intellij.platform.gradle.TestFrameworkType
plugins {
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}
group = "io.github.roman-n1"
version = "0.1.0"
repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}
dependencies {
    intellijPlatform {
        val localPath = providers.gradleProperty("actron.ide.path")
        if (localPath.isPresent) local(localPath.get()) else intellijIdea("2025.3.3")
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("com.intellij.modules.json")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}
kotlin { jvmToolchain(21) }
intellijPlatform {
    pluginConfiguration {
        id = "io.github.roman-n1.actron"
        name = "Actron Behavioural Model"
        version = "0.1.0"
        ideaVersion {
            sinceBuild = "253"
            untilBuild.unset()
        }
    }
}
tasks.test { systemProperty("java.awt.headless", "true") }

// Audit this independent build before generated Gradle/IDE ABI is introduced.
val actronRepository = rootProject.projectDir.parentFile.parentFile
val nullabilityPolicyJar = project(":nullability-policy").layout.buildDirectory.file("libs/nullability-policy.jar")
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    dependsOn(":nullability-policy:jar")
    inputs.files(nullabilityPolicyJar, actronRepository.resolve(".actron/nullability/strict"))
    compilerOptions.freeCompilerArgs.addAll(
        "-Xplugin=${nullabilityPolicyJar.get().asFile.absolutePath}",
        "-P",
        "plugin:actron-nullability:root=${actronRepository.absolutePath}",
    )
}
