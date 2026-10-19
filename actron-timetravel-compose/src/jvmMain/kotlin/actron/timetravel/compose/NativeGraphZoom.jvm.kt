package actron.timetravel.compose

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import java.awt.event.MouseEvent
import java.beans.EventHandler
import javax.swing.JComponent
import javax.swing.SwingUtilities

@Composable
internal actual fun nativeGraphZoom(onZoom: (Offset, Float) -> Unit): Modifier {
    val current by rememberUpdatedState(onZoom)
    val receiver = remember { MacGraphMagnification { anchor, zoom -> current(anchor, zoom) } }
    DisposableEffect(receiver) { onDispose { receiver.close() } }
    if (!System.getProperty("os.name", "").startsWith("Mac")) return Modifier
    return Modifier.pointerInput(receiver) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                receiver.inside = event.type != PointerEventType.Exit
                event.changes.forEach { receiver.anchor = it.position }
                event.nativeEvent?.let { native: Any ->
                    if (native is MouseEvent) SwingUtilities.getRootPane(native.component)?.let { root: JComponent -> receiver.attach(root) }
                }
            }
        }
    }
}

/** Uses the JDK's macOS gesture bridge; class lookup is deferred so Linux/Windows need no Apple classes. */
internal class MacGraphMagnification(
    private val onZoom: (Offset, Float) -> Unit,
) {
    var inside = false
    var anchor = Offset.Zero
    private val registrations = mutableListOf<Pair<JComponent, Any>>()

    fun attach(component: JComponent): Boolean {
        if (registrations.any { it.first === component }) return true
        val utility: Class<out Any> = Class.forName("com.apple.eawt.event.GestureUtilities").asSubclass(Any::class.java)
        if (!utility.module.isExported("com.apple.eawt.event", javaClass.module)) return false
        val listenerClass: Class<out Any> = Class.forName("com.apple.eawt.event.MagnificationListener").asSubclass(Any::class.java)
        val listener: Any = EventHandler.create(listenerClass, this, "magnify", "")
        utility.getMethod("addGestureListenerTo", JComponent::class.java, Class.forName("com.apple.eawt.event.GestureListener")).invoke(this, component, listener)
        registrations.add(component to listener)
        return true
    }

    // Public for java.beans.EventHandler; an empty property path forwards the native event itself.
    fun magnify(event: Any) {
        if (!inside) return
        val delta = event.javaClass.getMethod("getMagnification").invoke(event) as Number
        val factor = 1f + delta.toFloat()
        if (!factor.isFinite() || factor <= 0f) return
        onZoom(anchor, factor)
        event.javaClass.getMethod("consume").invoke(event)
    }

    fun close() {
        registrations.forEach { (component, listener) ->
            Class
                .forName("com.apple.eawt.event.GestureUtilities")
                .getMethod("removeGestureListenerFrom", JComponent::class.java, Class.forName("com.apple.eawt.event.GestureListener"))
                .invoke(this, component, listener)
        }
        registrations.clear()
        inside = false
    }
}
