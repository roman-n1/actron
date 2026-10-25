package actron.diagnostics.sdk

import actron.diagnostics.FailureSource
import actron.diagnostics.TraceFault
import actron.observability.FailureDescriptor
import com.google.firebase.crashlytics.CustomKeysAndValues
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.crashlytics.reportKeys
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class CrashlyticsConcurrentReportsTest {
    @Test
    fun concurrentStoresKeepIndependentKeysAndFailureTypesPerReport() {
        val sdk = checkNotNull(mock(FirebaseCrashlytics::class.java))
        val sink = CrashlyticsDiagnosticSink(sdk)
        val executor: ExecutorService = checkNotNull(Executors.newFixedThreadPool(2))
        val start = CyclicBarrier(2)
        val perStore = 100
        try {
            val tasks: List<Future<Unit>> =
                List(2) { worker ->
                    val task: Future<Unit> =
                        checkNotNull(
                            executor.submit<Unit> {
                                start.await(10, TimeUnit.SECONDS)
                                repeat(perStore) { index ->
                                    sink.emit(
                                        crashlyticsEvent(
                                            listOf(TraceFault.of(FailureSource.Decision, FailureDescriptor("Type$worker"))),
                                            store = "store-$worker",
                                            sequence = (index + 1).toLong(),
                                        ),
                                    )
                                }
                                Unit
                            },
                        )
                    task
                }
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
        val failure: ArgumentCaptor<Throwable> = checkNotNull(ArgumentCaptor.forClass(Throwable::class.java))
        val keys: ArgumentCaptor<CustomKeysAndValues> = checkNotNull(ArgumentCaptor.forClass(CustomKeysAndValues::class.java))
        val count = perStore * 2
        verify(sdk, times(count)).log(anyString())
        verify(sdk, times(count)).recordException(failure.capture(), keys.capture())
        val reports: List<CustomKeysAndValues> = checkNotNull(keys.allValues)
        val failures: List<Throwable> = checkNotNull(failure.allValues)
        assertEquals(count, reports.toSet().size, "Each report must own its SDK keys object")
        val seen = mutableSetOf<Pair<String, String>>()
        for ((report, error) in reports.zip(failures)) {
            val attributes = report.reportKeys()
            val store = attributes.getValue("actron.store")
            val type = "Type${store.removePrefix("store-")}"
            assertEquals(type, attributes.getValue("actron.failure.type"))
            assertEquals("Actron workflow failure: $type", error.message)
            seen += store to attributes.getValue("actron.sequence")
        }
        assertEquals(count, seen.size)
        assertEquals(setOf("store-0", "store-1"), seen.map { it.first }.toSet())
        assertEquals((1..perStore).map { it.toString() }.toSet(), seen.filter { it.first == "store-0" }.map { it.second }.toSet())
        verifyNoMoreInteractions(sdk)
    }
}
