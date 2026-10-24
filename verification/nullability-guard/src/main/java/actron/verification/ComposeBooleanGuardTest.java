package actron.verification;

import java.util.List;
import org.jetbrains.kotlin.psi.KtPsiFactory;
import static actron.verification.ComposeBooleanSyntax.scan;
import static actron.verification.ComposeBooleanGuard.requireSubset;

/** Positive/negative controls run with the real parser before every policy check. */
final class ComposeBooleanGuardTest {
    static void run(KtPsiFactory parser) {
        String prefix = "import androidx.compose.runtime.Composable\n";
        for (String expression : List.of("a && b", "a || b", "!a", "a !in xs", "a !is String", "a.not()", "a and b", "a.or(b)", "a xor b", "Boolean::not", "with(a) { not() }", "a.`and`(b)")) {
            if (scan(parser, "check.kt", prefix + "@Composable fun Screen() { val x = " + expression + " }").size() != 1) throw new AssertionError("Must reject " + expression);
        }
        for (String body : List.of("val x = a != b", "val x = a!!", "Text(\"! && ||\") /* !a */", "if (visible) Text(\"yes\")", "val x = \"plain\"")) {
            if (!scan(parser, "check.kt", prefix + "@Composable fun Screen() { " + body + " }").isEmpty()) throw new AssertionError("Must allow " + body);
        }
        if (!scan(parser, "check.kt", "fun map() = a && !b").isEmpty()) throw new AssertionError("Mapper is outside rendering");
        if (scan(parser, "check.kt", "package app.uimodel\nval x = a || b").size() != 1) throw new AssertionError("UiModel package");
        if (scan(parser, "check.kt", "import androidx.compose.runtime.Composable as Ui\n@Ui fun Screen() = !a").size() != 1) throw new AssertionError("Alias");
        if (scan(parser, "check.kt", prefix + "val screen: @Composable () -> Unit = { val x = !a }").size() != 1) throw new AssertionError("Typed lambda");
        if (scan(parser, "check.kt", prefix + "@get:Composable val enabled get() = !a").size() != 1) throw new AssertionError("Getter");
        if (scan(parser, "check.kt", prefix + "@Composable fun Screen() = Text(\"${!a}\")").size() != 1) throw new AssertionError("String interpolation");
        if (scan(parser, "check.kt", prefix + "fun host(content: @Composable () -> Unit) = a && b").size() != 0) throw new AssertionError("Type annotation must not taint host");
        if (scan(parser, "check.kt", "import androidx.compose.ui.window.singleWindowApplication\nfun main() = singleWindowApplication { val x = !a }").size() != 1) throw new AssertionError("Desktop entry");
        if (scan(parser, "check.kt", prefix + "fun host(content: @Composable () -> Unit = { val x = !a }) = Unit").size() != 1) throw new AssertionError("Default composable lambda");
        if (scan(parser, "check.kt", prefix + "@Composable fun Screen() { when(x) { !in xs -> Unit } }").size() != 1) throw new AssertionError("Negated when range");
        try { requireSubset(List.of("a", "a"), List.of("a")); throw new AssertionError("Duplicate debt"); } catch (IllegalStateException expected) { }
        try { requireSubset(List.of("new"), List.of("old")); throw new AssertionError("Replacement debt"); } catch (IllegalStateException expected) { }
        requireSubset(List.of("a"), List.of("a", "b"));
        System.out.println("Compose boolean guard positive/negative and baseline tests passed");
    }
}
