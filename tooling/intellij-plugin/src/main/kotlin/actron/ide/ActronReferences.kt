package actron.ide

import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.json.psi.*
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usageView.UsageInfo
import com.intellij.util.ProcessingContext
import com.intellij.util.Processor

/** References are scoped to one exported definition; unrelated labels/guard strings are ignored. */
class ActronReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            psiElement(JsonStringLiteral::class.java),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(
                    element: PsiElement,
                    context: ProcessingContext,
                ): Array<PsiReference> {
                    val literal = element as JsonStringLiteral
                    val file = literal.containingFile as? JsonFile ?: return emptyArray()
                    if (!ActronModel.withRoot(file) {} || ActronModel.declarationKey(literal) {}) return emptyArray()
                    val parent = literal.parent ?: return emptyArray()
                    val arrayKind = (((parent as? JsonArray)?.parent as? JsonProperty)?.name).orEmpty()
                    if (parent is JsonProperty && (parent.value != literal || parent.name !in setOf("source", "target", "parent", "initial", "default"))) return emptyArray()
                    if (parent !is JsonProperty && arrayKind !in setOf("active", "coveredStates", "selectedTransitions")) return emptyArray()
                    val key = if (arrayKind == "selectedTransitions") literal.value else "state:${literal.value}"
                    return arrayOf(
                        object : PsiPolyVariantReferenceBase<JsonStringLiteral>(literal) {
                            override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> = ActronModel.declarations(file, key).map { PsiElementResolveResult(it) }.toTypedArray()

                            override fun getVariants(): Array<Any> = emptyArray()
                        },
                    )
                }
            },
        )
    }
}

class ActronFindUsagesHandlerFactory : FindUsagesHandlerFactory() {
    override fun canFindUsages(element: PsiElement): Boolean = ActronModel.declarationKey(element) {}

    override fun createFindUsagesHandler(
        element: PsiElement,
        forHighlightUsages: Boolean,
    ): FindUsagesHandler =
        object : FindUsagesHandler(element) {
            override fun processElementUsages(
                element: PsiElement,
                processor: Processor<in UsageInfo>,
                options: FindUsagesOptions,
            ): Boolean {
                // A definition is local to one versioned export; identical ids in other machines are unrelated.
                for (literal in checkNotNull(PsiTreeUtil.findChildrenOfType(element.containingFile, JsonStringLiteral::class.java)).filterNotNull()) {
                    for (reference in checkNotNull(PsiReferenceService.getService().getReferences(literal, PsiReferenceService.Hints.NO_HINTS)).filterNotNull()) if (reference.isReferenceTo(element) && !processor.process(UsageInfo(reference))) return false
                }
                return true
            }
        }
}
