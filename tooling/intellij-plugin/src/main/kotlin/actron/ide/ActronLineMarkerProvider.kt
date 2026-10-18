package actron.ide

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.*

/** Kotlin resolves references normally; names alone never mark another library's on()/after(). */
class ActronLineMarkerProvider : RelatedItemLineMarkerProvider() {
    public override fun collectNavigationMarkers(
        element: PsiElement,
        result: MutableCollection<in RelatedItemLineMarkerInfo<out PsiElement>>,
    ) {
        if (element.firstChild is PsiElement) return
        val reference = element.parent as? KtNameReferenceExpression ?: return
        val call = reference.parent as? KtCallExpression ?: return
        if (call.calleeExpression != reference || reference.getReferencedName() !in setOf("Transition", "on", "always", "after", "onDone", "internal")) return
        val resolved =
            buildList<PsiElement> {
                for (ref in checkNotNull(reference.references).filterNotNull()) ref.resolve()?.let { add(it) }
            }.firstOrNull() ?: return
        val namespace = (resolved.containingFile as? KtFile)?.packageFqName?.asString() ?: return
        if (namespace != "actron.statechart") return
        val targets =
            buildList<PsiElement> {
                for (argument in call.valueArguments) {
                    val expression = argument.getArgumentExpression() ?: continue
                    for (ref in checkNotNull(expression.references).filterNotNull()) ref.resolve()?.let { add(it) }
                }
            }.filterIsInstance<KtProperty>()
                .filter { property ->
                    (property.initializer as? KtCallExpression)?.calleeExpression?.text == "StateId"
                }.distinct()
        if (targets.isEmpty()) return
        result.add(
            NavigationGutterIconBuilder
                .create(AllIcons.Nodes.Method)
                .setTargets(targets)
                .setTooltipText("Navigate Actron transition states")
                .createLineMarkerInfo(element),
        )
    }
}
