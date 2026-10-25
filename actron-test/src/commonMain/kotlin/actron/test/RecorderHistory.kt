package actron.test

import actron.core.Event
import actron.core.State

/** Owns the live histories and their consumption cursor; callers serialize reads with Store work. */
internal class RecorderHistory<S : State, E : Event> {
    private val recordedStates = mutableListOf<S>()
    private val recordedEvents = mutableListOf<E>()
    private var cursor = 0
    private var cursorRevision = 0L

    val states: List<S> = recordedStates
    val events: List<E> = recordedEvents
    val unconsumedEvents: List<E> get() = recordedEvents.subList(cursor, recordedEvents.size).toList()

    fun recordState(state: S) {
        recordedStates.add(state)
    }

    fun recordEvent(event: E) {
        recordedEvents.add(event)
    }

    fun clear() {
        recordedStates.clear()
        recordedEvents.clear()
        cursor = 0
        cursorRevision++
    }

    fun peekUnconsumed(expected: String): E {
        if (cursor >= recordedEvents.size) throw AssertionError("[Actron] Expected $expected, but every recorded event was received already (${recordedEvents.size} recorded)")
        return recordedEvents[cursor]
    }

    fun consumeNext() {
        cursor++
        cursorRevision++
    }

    fun receiveEvent(predicate: (E) -> Boolean): E {
        val next = peekUnconsumed("an event")
        val revisionBeforePredicate = cursorRevision
        if (!predicate(next)) throw AssertionError("[Actron] The next unconsumed event $next does not satisfy the predicate; unconsumed: $unconsumedEvents")
        check(cursorRevision == revisionBeforePredicate) { "[Actron] The event cursor changed while receiveEvent's predicate was running" }
        consumeNext()
        return next
    }

    fun assertNoUnconsumedEvents() {
        val left = unconsumedEvents
        if (left.isNotEmpty()) throw AssertionError("[Actron] ${left.size} recorded event(s) were not received: $left")
    }
}
