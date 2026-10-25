package actron.timetravel

internal typealias CheckpointFrameUpdate<F> = (F) -> F

/** Decoding prepares an update; it must not read, construct or write a host frame. */
internal class CheckpointBinding<F : Any>(
    val id: String,
    private val encode: (F) -> List<String>,
    private val decode: (List<String>) -> CheckpointFrameUpdate<F>,
) {
    fun save(frame: F): List<String> = encode(frame)

    fun prepareRestore(values: List<String>): CheckpointFrameUpdate<F> = decode(values)

    companion object {
        fun <F : Any> single(
            id: String,
            description: String,
            encode: (F) -> String,
            decode: (String) -> CheckpointFrameUpdate<F>,
        ): CheckpointBinding<F> =
            CheckpointBinding(
                id = id,
                encode = { frame -> listOf(encode(frame)) },
                decode = { values ->
                    require(values.size == 1) { "Expected one $description for $id" }
                    decode(values.single())
                },
            )
    }
}
