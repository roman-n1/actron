package actron.example.weather.core.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.stack.animation.slide
import com.arkivanov.decompose.extensions.compose.stack.animation.stackAnimation
import com.arkivanov.decompose.router.stack.ChildStack
import kotlinx.coroutines.flow.StateFlow

interface StackComponent : BaseComponent {
    fun getChildStack(): StateFlow<ChildStack<*, BaseComponent>>

    @Composable
    override fun Render() {
        val childStack by rememberChildStackFlow(this).collectAsState()
        Children(
            stack = childStack,
            animation = stackAnimation(slide()),
        ) {
            it.instance.Render()
        }
    }
}

/** Keep one adapter per component so recomposition does not resubscribe to Decompose. */
@Composable
internal fun rememberChildStackFlow(
    component: StackComponent,
): StateFlow<ChildStack<*, BaseComponent>> = remember(component) { component.getChildStack() }
