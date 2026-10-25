// Plain analysis of every source set: no Android/Native compilation needed for quality checks.
val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val detektVersion = catalog.findVersion("detekt").get().requiredVersion
val detektConfig = rootProject.file("config/detekt/detekt.yml")
val checkDetektBaselines by tasks.registering(Exec::class) {
    group = "verification"
    description = "Rejects additions to the exact Detekt debt inventory against the review base."
    workingDir(rootProject.projectDir)
    val base = providers.gradleProperty("actron.quality.base").getOrElse("HEAD")
    commandLine("python3", "scripts/check-detekt-baselines.py", "--base", base)
}
val detektAll by tasks.registering {
    group = "verification"
    description = "Checks Kotlin smells and complexity in every module's source sets."
    dependsOn(checkDetektBaselines)
}
subprojects {
    val detektCli = configurations.create("detektCli") { isCanBeConsumed = false }
    dependencies.add(detektCli.name, "dev.detekt:detekt-cli:$detektVersion")
    val module = this
    val sources =
        fileTree("src") {
            include("**/*.kt")
            exclude("**/build/**")
        }
    val baselineName =
        when (project.path) {
            ":weather:debugTools" -> "weather-example"
            ":weather:shared" -> "weather-app"
            ":weather:androidApp" -> "weather-android"
            else -> project.name
        }
    val baseline = rootProject.file("config/detekt/baselines/$baselineName.xml")
    val report = layout.buildDirectory.file("reports/detekt/detekt.sarif")
    val analyze by tasks.registering(JavaExec::class) {
        group = "verification"
        description = "Checks all Kotlin source sets with the shared Detekt rules and exact module baseline."
        classpath = detektCli
        mainClass.set("dev.detekt.cli.Main")
        inputs.files(sources)
        inputs.file(detektConfig)
        if (baseline.exists()) inputs.file(baseline)
        outputs.file(report)
        onlyIf { !sources.isEmpty }
        args("--input", module.file("src").absolutePath, "--config", detektConfig.absolutePath)
        args("--build-upon-default-config", "--base-path", rootProject.projectDir.absolutePath)
        args("--report", "sarif:${report.get().asFile.absolutePath}")
        if (baseline.exists()) args("--baseline", baseline.absolutePath)
    }
    // Explicit creation is for initial onboarding only. Baselines are reviewed and shrink-only.
    tasks.register<JavaExec>("detektCreateBaseline") {
        group = "verification"
        description = "Creates a Detekt debt inventory; never use to approve new findings."
        classpath = detektCli
        mainClass.set("dev.detekt.cli.Main")
        onlyIf { !sources.isEmpty }
        doFirst { baseline.parentFile.mkdirs() }
        args("--input", module.file("src").absolutePath, "--config", detektConfig.absolutePath)
        args("--build-upon-default-config", "--base-path", rootProject.projectDir.absolutePath)
        args("--baseline", baseline.absolutePath, "--create-baseline")
    }
    tasks.register("detekt") {
        group = "verification"
        dependsOn(analyze)
    }
    detektAll.configure { dependsOn(analyze) }
    tasks.matching { it.name == "check" }.configureEach { dependsOn(analyze) }
}
tasks.register("detekt") {
    group = "verification"
    dependsOn(detektAll)
}
tasks.named("check") { dependsOn(detektAll) }

// The same blocking path runs locally and in the existing CI quality job.
detektAll.configure { dependsOn("checkComposeNoBooleanLogic") }
