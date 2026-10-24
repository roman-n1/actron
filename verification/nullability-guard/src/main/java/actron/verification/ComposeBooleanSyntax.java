package actron.verification;

import actron.verification.ComposeBooleanGuard.Finding;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.lexer.KtTokens;
import org.jetbrains.kotlin.psi.KtAnnotated;
import org.jetbrains.kotlin.psi.KtAnnotatedExpression;
import org.jetbrains.kotlin.psi.KtAnnotationEntry;
import org.jetbrains.kotlin.psi.KtBinaryExpression;
import org.jetbrains.kotlin.psi.KtCallExpression;
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression;
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression;
import org.jetbrains.kotlin.psi.KtElement;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.KtIsExpression;
import org.jetbrains.kotlin.psi.KtNamedDeclaration;
import org.jetbrains.kotlin.psi.KtNamedFunction;
import org.jetbrains.kotlin.psi.KtParameter;
import org.jetbrains.kotlin.psi.KtProperty;
import org.jetbrains.kotlin.psi.KtPropertyAccessor;
import org.jetbrains.kotlin.psi.KtPsiFactory;
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid;
import org.jetbrains.kotlin.psi.KtTypeReference;
import org.jetbrains.kotlin.psi.KtUnaryExpression;
import org.jetbrains.kotlin.psi.KtWhenConditionInRange;

/** Finds forbidden executable expressions using Kotlin PSI, without type-resolution guesses. */
final class ComposeBooleanSyntax {
    static List<Finding> scan(KtPsiFactory parser, String path, String source) {
        KtFile file = parser.createFile(path.substring(path.lastIndexOf('/') + 1), source);
        Set<String> annotations = new java.util.HashSet<>(Set.of("Composable", "androidx.compose.runtime.Composable"));
        Set<String> desktop = new java.util.HashSet<>();
        for (var name : List.of("application", "singleWindowApplication", "Window", "DialogWindow")) desktop.add("androidx.compose.ui.window." + name);
        for (var directive : file.getImportDirectives()) {
            if (directive.getImportedFqName() == null) continue;
            String imported = directive.getImportedFqName().asString();
            String local = directive.getAliasName() == null ? imported.substring(imported.lastIndexOf('.') + 1) : directive.getAliasName();
            if (imported.equals("androidx.compose.runtime.Composable")) annotations.add(local);
            if (desktop.contains(imported)) desktop.add(local);
            if (imported.equals("androidx.compose.ui.window") && directive.isAllUnder()) desktop.addAll(List.of("application", "singleWindowApplication", "Window", "DialogWindow"));
        }
        boolean model = List.of(file.getPackageFqName().asString().split("\\.")).contains("uimodel");
        List<Finding> result = new ArrayList<>();
        file.accept(new KtTreeVisitorVoid() {
            @Override public void visitKtElement(KtElement element) {
                if (forbidden(element) && (model || inCompose(element, annotations, desktop))) {
                    String owner = "<file>";
                    for (PsiElement p = element; p != null; p = p.getParent()) {
                        if (p instanceof KtNamedDeclaration d && d.getName() != null && !d.getName().startsWith("<")) { owner = d.getName(); break; }
                    }
                    int line = 1 + (int) source.substring(0, element.getTextOffset()).chars().filter(c -> c == '\n').count();
                    result.add(new Finding(path, owner, line, element.getText()));
                }
                super.visitKtElement(element);
            }
        });
        return result;
    }

    private static boolean forbidden(KtElement element) {
        Set<String> names = Set.of("not", "and", "or", "xor");
        if (element instanceof KtBinaryExpression e) return e.getOperationToken() == KtTokens.ANDAND || e.getOperationToken() == KtTokens.OROR || e.getOperationToken() == KtTokens.NOT_IN || names.contains(e.getOperationReference().getReferencedName());
        if (element instanceof KtUnaryExpression e) return e.getOperationToken() == KtTokens.EXCL;
        if (element instanceof KtIsExpression e) return e.isNegated();
        if (element instanceof KtWhenConditionInRange e) return e.isNegated();
        if (element instanceof KtCallExpression e && e.getCalleeExpression() != null) return names.contains(e.getCalleeExpression().getText().replace("`", ""));
        if (element instanceof KtCallableReferenceExpression e) return names.contains(e.getCallableReference().getReferencedName());
        return false;
    }

    private static boolean annotated(KtAnnotated element, Set<String> annotations) {
        return element.getAnnotationEntries().stream().anyMatch(a -> annotations.contains(a.getTypeReference() == null ? "" : a.getTypeReference().getText()));
    }

    private static boolean composeType(KtTypeReference type, Set<String> annotations) {
        if (type == null) return false;
        return org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(type, KtAnnotationEntry.class).stream().anyMatch(a -> a.getTypeReference() != null && annotations.contains(a.getTypeReference().getText()));
    }

    private static boolean contains(PsiElement parent, PsiElement child) {
        return parent != null && parent.getTextRange().contains(child.getTextRange());
    }

    private static boolean inCompose(KtElement element, Set<String> annotations, Set<String> desktop) {
        for (PsiElement p = element; p != null; p = p.getParent()) {
            if (p instanceof KtNamedFunction f && annotated(f, annotations) && contains(f.getBodyExpression(), element)) return true;
            if (p instanceof KtPropertyAccessor a && contains(a.getBodyExpression(), element) && (annotated(a, annotations) || annotated(a.getProperty(), annotations))) return true;
            if (p instanceof KtProperty property && composeType(property.getTypeReference(), annotations) && contains(property.getInitializer(), element)) return true;
            if (p instanceof KtParameter parameter && composeType(parameter.getTypeReference(), annotations) && contains(parameter.getDefaultValue(), element)) return true;
            if (p instanceof KtAnnotatedExpression a && annotated(a, annotations) && contains(a.getBaseExpression(), element)) return true;
            if (p instanceof KtCallExpression call && call.getCalleeExpression() != null) {
                String name = call.getCalleeExpression().getText();
                if (call.getParent() instanceof KtDotQualifiedExpression qualified) name = qualified.getReceiverExpression().getText() + "." + name;
                if (desktop.contains(name)) {
                    for (var lambda : call.getLambdaArguments()) if (contains(lambda.getLambdaExpression(), element)) return true;
                    for (var argument : call.getValueArguments()) if (argument.getArgumentName() != null && argument.getArgumentName().getAsName().asString().equals("content") && contains(argument.getArgumentExpression(), element)) return true;
                }
            }
        }
        return false;
    }

}
