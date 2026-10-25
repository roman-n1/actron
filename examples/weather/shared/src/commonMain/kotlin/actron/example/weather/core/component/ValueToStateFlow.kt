package actron.example.weather.core.component

import com.arkivanov.decompose.value.Value
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** KMP navigation adapter: each collector owns and cancels its Decompose subscription. */
fun <T : Any> Value<T>.toStateFlow(): StateFlow<T> = ValueStateFlow(this)

@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class ValueStateFlow<T : Any>(
    private val source: Value<T>,
) : StateFlow<T> {
    override val value: T get() = source.value

    override val replayCache: List<T> get() = listOf(source.value)

    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        val state = MutableStateFlow(source.value)
        // subscribe синхронно отдаёт актуальное значение — «пропущенного между чтением и подпиской»
        // обновления быть не может.
        val cancellation = source.subscribe { newValue -> state.value = newValue }
        try {
            state.collect(collector)
        } finally {
            cancellation.cancel()
        }
    }
}
