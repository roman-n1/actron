package actron.decompose.session

/**
 * The application's navigation boundary. All methods run on the controller's owning dispatcher.
 * Pause must settle navigation and prevent route/tree changes until resume. It must not wait
 * for new machine inputs. Replacement creates a fresh component tree and fresh InstanceKeepers,
 * connects its machine factories to [MachineSession], and discards old component subscriptions.
 * Buffering old callbacks for delivery into the replacement is not allowed.
 */
internal interface SessionNavigation<R : Any> {
    suspend fun pause()

    fun snapshot(): R

    suspend fun replace(
        routes: R,
        session: MachineSession<R>,
    )

    fun resume()
}
