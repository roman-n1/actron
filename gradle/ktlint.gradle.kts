// One invocation covers Kotlin sources and scripts in every build, including included builds.
val ktlint by configurations.creating {
    isCanBeConsumed = false
}
val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
dependencies {
    ktlint("com.pinterest.ktlint:ktlint-cli:${catalog.findVersion("ktlint").get().requiredVersion}:all") {
        isTransitive = false
    }
}
val excludedDirectories = listOf("build", ".gradle", ".git", ".idea", ".kotlin", "node_modules", ".intellijPlatform", ".cocoindex_code", ".codegraph")
val exclusions = excludedDirectories.map { "**/$it/**" }
val kotlinSources =
    fileTree(rootDir) {
        include("**/*.kt", "**/*.kts")
        exclude(exclusions)
    }
val sourcePatterns = listOf("**/*.kt", "**/*.kts") + exclusions.map { "!$it" }

val ktlintCheck by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Checks the style of all authored Kotlin sources and Gradle Kotlin scripts."
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    workingDir(rootDir)
    inputs.files(kotlinSources)
    inputs.file(rootProject.file(".editorconfig"))
    args("--relative")
    args(sourcePatterns)
}

tasks.register<JavaExec>("ktlintFormat") {
    group = "formatting"
    description = "Formats all authored Kotlin sources and scripts using the shared .editorconfig."
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    workingDir(rootDir)
    args("--format", "--relative")
    args(sourcePatterns)
}

tasks.matching { it.name == "check" }.configureEach { dependsOn(ktlintCheck) }
subprojects {
    tasks.matching { it.name == "check" }.configureEach { dependsOn(rootProject.tasks.named("ktlintCheck")) }
}
