pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Actron"
include(":actron-core")
include(":actron-decompose")
include(":actron-compose")
include(":actron-logging")
include(":actron-message")
include(":actron-test")
include(":actron-statechart")
include(":actron-observability")
include(":actron-timetravel")
include(":actron-statechart-test")
include(":actron-timetravel-test")
include(":actron-diagnostics")
include(":actron-diagnostics-sdk")
include(":actron-diagnostics-crashlytics")
include(":actron-timetravel-compose")
include(":actron-statechart-compose")
include(":resource-soak")
project(":resource-soak").projectDir = file("verification/resource-soak")
include(":time-travel-example")
project(":time-travel-example").projectDir = file("examples/time-travel")
include(":weather", ":weather:shared", ":weather:androidApp", ":weather:desktopApp", ":weather:webApp")
project(":weather").projectDir = file("examples/weather")
listOf("shared", "androidApp", "desktopApp", "webApp").forEach { module ->
    project(":weather:$module").projectDir = file("examples/weather/$module")
}
include(":durable-effects-example")
project(":durable-effects-example").projectDir = file("examples/durable-effects")

include(":behaviour-review")
project(":behaviour-review").projectDir = file("verification/behaviour-review")

include(":nullability-guard")
project(":nullability-guard").projectDir = file("verification/nullability-guard")

include(":actron-persistence")
