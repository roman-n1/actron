package actron.core

/**
 * Annotation to mark internal APIs in the Actron framework.
 * APIs marked with this annotation are not intended for public use
 * and may be changed or removed without notice.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This is an internal API for Actron Framework. It is not intended for public use and may be changed or removed without notice.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
)
annotation class InternalActronApi

@DslMarker
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
annotation class ActronStoreDsl
