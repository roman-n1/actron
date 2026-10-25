import java.nio.file.Files

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.compose.compiler)
}

dependencies {
    implementation(project(":weather:debugTools"))
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:${libs.versions.coroutines.get()}")
    implementation(libs.decompose.core)
    implementation("io.ktor:ktor-client-core:3.5.0")
}

compose.desktop.application {
    mainClass = "actron.example.weather.MainKt"
    if (System.getProperty("os.name", "").startsWith("Mac")) {
        jvmArgs += "--add-exports=java.desktop/com.apple.eawt.event=ALL-UNNAMED"
    }
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
