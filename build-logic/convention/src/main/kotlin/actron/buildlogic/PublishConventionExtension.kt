package actron.buildlogic

import actron.buildlogic.dsl.mavenPublishing
import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import javax.inject.Inject

open class PublishConventionExtension
    @Inject
    constructor(
        objects: ObjectFactory,
    ) {
        @Suppress("MemberVisibilityCanBePrivate")
        val artifactId: org.gradle.api.provider.Property<String> = objects.property(String::class.java)

        internal fun applyToProject(project: Project) {
            // Keep project coordinates aligned with publications for composite build substitution.
            val groupId = project.findProperty("actron.fork.group")?.toString() ?: project.group.toString()
            val version = project.findProperty("actron.fork.version")?.toString() ?: project.version.toString()
            project.group = groupId
            project.version = version
            project.mavenPublishing {
                coordinates(
                    groupId = groupId,
                    artifactId = artifactId.get(),
                    version = version,
                )
            }
        }
    }
