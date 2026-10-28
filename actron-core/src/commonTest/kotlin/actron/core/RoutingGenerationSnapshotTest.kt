package actron.core

import kotlinx.coroutines.Dispatchers
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** Seeded fingerprints guard the current registration sequence, IDs and dispatchers. */
class RoutingGenerationSnapshotTest {
    @Test
    fun seededDefinitionsKeepTheirExactRegistrationSequence() {
        val hashes =
            listOf(false, true).map { legacy ->
                (0 until 256)
                    .joinToString("|") { seed ->
                        RoutingProgram.random(Random(seed), legacy = legacy).blocks.joinToString(";") { block -> block.fingerprint() }
                    }.hashCode()
            }
        assertEquals(listOf(-1386353220, 1706387900), hashes)
    }

    private fun Block.fingerprint(): String =
        when (this) {
            is Block.LegacyTop -> {
                "L:$kind:$id:${predicate.name}"
            }

            is Block.StateBlock -> {
                "S:${stateKinds.indexOf(stateKind)}:" +
                    inner.joinToString(",") { handler ->
                        "${handler.id}:${handler.declaration.fingerprint()}:${handler.dispatcher == Dispatchers.Unconfined}"
                    }
            }
        }

    private fun HandlerDeclaration.fingerprint(): String =
        when (this) {
            is HandlerDeclaration.Lifecycle -> "C:$kind"
            is HandlerDeclaration.Action -> "A:${actionKinds.indexOf(action)}"
            is HandlerDeclaration.Recovery -> "R:${recoverKinds.indexOf(recovery)}"
            is HandlerDeclaration.Predicate -> "P:$kind:${predicate.name}"
        }
}
