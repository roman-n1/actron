package actron.buildlogic.publish

import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.publish.maven.MavenPomDeveloperSpec
import org.gradle.api.publish.maven.MavenPomLicenseSpec

/** Repository identity used by the published POM; forks additionally credit their owner. */
internal fun MavenPublishBaseExtension.pom(repoUrl: String, fork: Boolean) {
    val repoPath = repoUrl.removePrefix("https://github.com/").removeSuffix("/")
    pom {
        name.set("Actron")
        description.set("A behavioral engine combining MVI, reducers, and statecharts for Kotlin Multiplatform.")
        inceptionYear.set("2024")
        url.set(repoUrl)
        developers {
            upstreamKoma()
            if (fork) {
                val owner = repoPath.substringBefore("/")
                developer {
                    id.set(owner)
                    name.set(owner)
                    url.set("https://github.com/$owner/")
                }
            }
        }
        licenses {
            mit()
        }
        scm {
            url.set(repoUrl)
            connection.set("scm:git:git://github.com/$repoPath.git")
            developerConnection.set("scm:git:git://github.com/$repoPath.git")
        }
    }
}

private fun MavenPomLicenseSpec.mit() {
    license {
        name.set("MIT")
        url.set("https://opensource.org/licenses/MIT")
        distribution.set("https://opensource.org/licenses/MIT")
    }
}

private fun MavenPomDeveloperSpec.upstreamKoma() {
    developer {
        id.set("koma-kt")
        name.set("koma-kt")
        url.set("https://github.com/koma-kt/")
    }
}
