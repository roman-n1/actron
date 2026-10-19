package actron.timetravel.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import java.beans.EventHandler
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.*

class MacGraphMagnificationTest {
    @Test
    fun nativeMagnificationUsesIncrementalScaleAndOnlyConsumesInsideGraph() {
        val viewport = GraphViewport()
        viewport.fit(Size(800f, 600f), Size(1000f, 800f))
        val receiver = MacGraphMagnification { anchor, factor -> viewport.transform(anchor, Offset.Zero, factor) }
        val initial = viewport.scale
        val outside = MagnifyEvent(0.2)
        receiver.magnify(outside)
        assertEquals(initial, viewport.scale)
        assertFalse(outside.consumed)
        receiver.inside = true
        receiver.anchor = Offset(50f, 40f)
        val point = (receiver.anchor - viewport.offset) / viewport.scale
        val grow = MagnifyEvent(0.2)
        val listener: TestMagnificationListener = EventHandler.create(TestMagnificationListener::class.java, receiver, "magnify", "")
        listener.magnify(grow)
        assertEquals(initial * 1.2f, viewport.scale)
        assertEquals(receiver.anchor, point * viewport.scale + viewport.offset)
        assertTrue(grow.consumed)
        val shrink = MagnifyEvent(-0.2)
        receiver.magnify(shrink)
        assertEquals(initial * 1.2f * 0.8f, viewport.scale)
        receiver.close()
        val afterClose = viewport.scale
        receiver.magnify(MagnifyEvent(0.5))
        assertEquals(afterClose, viewport.scale)
    }

    @Test
    fun appleListenerCanBeRegisteredAndDisposedOnRealJdkWithoutExtraDependencies() {
        if (!System.getProperty("os.name", "").startsWith("Mac")) return
        SwingUtilities.invokeAndWait {
            val receiver = MacGraphMagnification { _, _ -> }
            val panel = JPanel()
            assertTrue(receiver.attach(panel))
            assertTrue(receiver.attach(panel))
            receiver.close()
            receiver.close()
        }
    }

    interface TestMagnificationListener {
        fun magnify(event: MagnifyEvent)
    }

    class MagnifyEvent(
        private val amount: Double,
    ) {
        var consumed = false

        fun getMagnification(): Double = amount

        fun consume() {
            consumed = true
        }
    }
}
