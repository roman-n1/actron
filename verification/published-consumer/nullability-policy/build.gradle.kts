plugins { java }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
val compilerVersion = providers.gradleProperty("consumer.kotlin").get()
repositories { mavenCentral() }
dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:$compilerVersion")
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:$compilerVersion")
}
sourceSets.main {
    java.setSrcDirs(listOf("../../../verification/nullability-guard/src/main/java"))
    resources.setSrcDirs(listOf("../../../verification/nullability-guard/src/main/resources"))
}
