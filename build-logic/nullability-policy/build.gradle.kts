plugins { java }

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
val compilerVersion = org.gradle.kotlin.dsl.embeddedKotlinVersion
repositories { mavenCentral() }
dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:$compilerVersion")
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:$compilerVersion")
}
sourceSets.main {
    java.setSrcDirs(listOf("../../verification/nullability-guard/src/main/java"))
    resources.setSrcDirs(listOf("../../verification/nullability-guard/src/main/resources"))
}

val compilerTest by tasks.registering(JavaExec::class) {
    dependsOn(tasks.jar)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("actron.verification.CompilerGuardTest")
    args(tasks.jar.flatMap { it.archiveFile }.get().asFile.absolutePath)
}
