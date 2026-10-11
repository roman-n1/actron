package actron.ide

import com.intellij.json.psi.*
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.psi.PsiReferenceService
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.usageView.UsageInfo
import java.awt.image.BufferedImage
import java.awt.event.MouseEvent
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

class ActronNavigationTest : BasePlatformTestCase() {
    private fun references(element: PsiElement) = checkNotNull(PsiReferenceService.getService().getReferences(element, PsiReferenceService.Hints.NO_HINTS)).filterNotNull()
    private fun manifest(): JsonFile = myFixture.configureByText("model.actron.json", """
        {"format":1,"definition":"sample","version":"1","sources":{},"model":{
          "initial":"idle","states":[{"id":"idle"},{"id":"done"}],
          "transitions":[{"id":"transition:0","index":0,"source":"idle","target":"done","guard":"done"}]},
          "active":["done"],"selectedTransitions":["transition:0"]}
    """.trimIndent()) as JsonFile

    fun testStateAndTransitionReferencesResolveWithinOneDefinitionAndGuardLabelsDoNot() {
        val file = manifest()
        assertTrue(PluginManagerCore.getPlugin(PluginId.getId("io.github.roman-n1.actron"))?.isEnabled == true)
        val model = buildList<JsonObject> { ActronModel.withModel(file) { add(it) } }.single()
        val edge = ActronModel.objects(model, "transitions").single()
        val target = requireNotNull(edge.findProperty("target")).value as JsonStringLiteral
        assertEquals(ActronModel.declarations(file, "state:done").single(), references(target).single().resolve())
        val guard = requireNotNull(edge.findProperty("guard")).value as JsonStringLiteral
        assertTrue(references(guard).isEmpty())
        val selected = (requireNotNull(buildList<JsonObject> { ActronModel.withRoot(file) { add(it) } }.single().findProperty("selectedTransitions")).value as JsonArray).valueList.filterNotNull().single()
        assertEquals(ActronModel.declarations(file, "transition:0").single(), references(selected).single().resolve())
    }

    fun testFindUsagesIncludesEndpointsAndActiveStateReferences() {
        val file = manifest()
        val definition = ActronModel.declarations(file, "state:done").single()
        val factory = ActronFindUsagesHandlerFactory()
        assertTrue(factory.canFindUsages(definition))
        val usages = mutableListOf<UsageInfo>()
        factory.createFindUsagesHandler(definition, false).processElementUsages(definition, { usage: UsageInfo -> usages += usage; true }, FindUsagesOptions(project))
        assertEquals(2, usages.size)
    }

    fun testUnsupportedExportsDoNotInstallReferences() {
        val file = myFixture.configureByText("future.actron.json", """{"format":2,"model":{"states":[{"id":"done"}],"initial":"done"}}""") as JsonFile
        assertFalse(ActronModel.withRoot(file) { error("Unsupported export accepted") })
        assertTrue(checkNotNull(PsiTreeUtil.findChildrenOfType(file, JsonStringLiteral::class.java)).filterNotNull().all { references(it).isEmpty() })
    }

    fun testGutterResolvesActronDeclarationAndIgnoresUnrelatedSameNamedFunction() {
        myFixture.addFileToProject("actron/statechart/Definition.kt", """
            package actron.statechart
            class StateId(val value: String)
            fun Transition(source: StateId, target: StateId) = Unit
        """.trimIndent())
        myFixture.addFileToProject("other/Definition.kt", """
            package other
            fun Transition(source: actron.statechart.StateId, target: actron.statechart.StateId) = Unit
        """.trimIndent())
        val file = checkNotNull(myFixture.configureByText("Workflow.kt", """
            import actron.statechart.StateId
            import actron.statechart.Transition
            val idle = StateId("idle")
            val done = StateId("done")
            fun workflow() {
                Transition(idle, done)
                other.Transition(idle, done)
            }
        """.trimIndent()))
        val calls = checkNotNull(PsiTreeUtil.findChildrenOfType(file, KtCallExpression::class.java)).filterNotNull()
            .filter { it.calleeExpression?.text == "Transition" }
        assertEquals(2, calls.size)
        val provider = ActronLineMarkerProvider()
        fun markers(call: KtCallExpression) = buildList<com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo<out PsiElement>> {
            provider.collectNavigationMarkers(requireNotNull((call.calleeExpression as KtNameReferenceExpression).firstChild), this)
        }
        assertEquals(1, markers(calls.first()).size)
        assertTrue(markers(calls.last()).isEmpty())
    }

    fun testCanvasNavigatesStateByModelIdentityWithoutExecutingLabels() {
        val canvas = DiagramPanel()
        val selections = mutableListOf<String>()
        canvas.load(listOf("idle", "done"), listOf(DiagramEdge("transition:0", "idle", "done", "go")), setOf("done")) { selections += it }
        canvas.setSize(canvas.preferredSize)
        canvas.paint(BufferedImage(canvas.width, canvas.height, BufferedImage.TYPE_INT_ARGB).graphics)
        canvas.dispatchEvent(MouseEvent(canvas, MouseEvent.MOUSE_CLICKED, 1, 0, 50, 60, 2, false))
        assertEquals(listOf("state:idle"), selections)
    }
}
