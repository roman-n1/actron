package actron.verification;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.kotlin.cli.CompilerConfigurationCreationKt;
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles;
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.psi.KtPsiFactory;

/** Repository admission and a shrink-only exact inventory; syntax belongs to ComposeBooleanSyntax. */
public final class ComposeBooleanGuard {
    private static final String BASELINE = "config/compose/boolean-logic-baseline.tsv";
    private static final Set<String> SKIP_DIRECTORIES = Set.of(
        "build", "generated", "node_modules", ".git", ".gradle", ".kotlin");

    record Finding(String path, String owner, int line, String expression) {
        String key() {
            return path + "\t" + owner + "\t" + expression.replaceAll("\\s+", " ").strip();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("Expected --check, --prune, --report or --self-test");
        String mode = args[0];
        if (!Set.of("--check", "--prune", "--report", "--self-test").contains(mode)) {
            throw new IllegalArgumentException("Unknown mode " + mode);
        }
        var disposable = Disposer.newDisposable();
        try {
            var environment = KotlinCoreEnvironment.createForProduction(
                disposable, CompilerConfigurationCreationKt.create(CompilerConfiguration.Companion), EnvironmentConfigFiles.JVM_CONFIG_FILES);
            var parser = new KtPsiFactory(environment.getProject(), false);
            if (mode.equals("--self-test")) {
                ComposeBooleanGuardTest.run(parser);
                return;
            }
            Path root = Path.of("").toAbsolutePath();
            var findings = scanRepository(root, parser);
            var current = findings.stream().map(Finding::key).sorted().toList();
            if (mode.equals("--report")) {
                current.forEach(System.out::println);
                return;
            }
            Path baselinePath = root.resolve(BASELINE);
            var baseline = baselineLines(Files.readString(baselinePath));
            requireSubset(current, baseline);
            checkBase(root, args.length > 1 ? args[1] : "HEAD", baseline);
            if (mode.equals("--prune")) {
                Files.write(baselinePath, current);
            } else if (!current.equals(baseline)) {
                throw new IllegalStateException("Resolved Compose debt must be pruned");
            }
            System.out.println("Compose boolean policy passed; exact remaining debt: " + current.size());
        } finally {
            Disposer.dispose(disposable);
        }
    }

    private static List<Finding> scanRepository(Path root, KtPsiFactory parser) throws IOException {
        if (!Files.isDirectory(root.resolve("actron-core/src/commonMain"))) {
            throw new IllegalStateException("Run the Compose guard at the Actron root");
        }
        List<Finding> findings = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) {
                if (!path.equals(root) && SKIP_DIRECTORIES.contains(path.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (relative.endsWith(".kt") && !relative.matches(".*/src/(?:test|[^/]*Test)/.*")) {
                    findings.addAll(ComposeBooleanSyntax.scan(parser, relative, Files.readString(path)));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return findings;
    }

    private static List<String> baselineLines(String text) {
        return text.lines().filter(s -> !s.isBlank() && !s.startsWith("#")).sorted().toList();
    }

    private static void checkBase(Path root, String base, List<String> baseline) throws Exception {
        var verify = new ProcessBuilder("git", "rev-parse", "--verify", base + "^{commit}")
            .directory(root.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (verify.waitFor() != 0) throw new IllegalStateException("Invalid policy base " + base);
        var previous = new ProcessBuilder("git", "show", base + ":" + BASELINE)
            .directory(root.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        String old = new String(previous.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        // A missing inventory is accepted only during this policy's initial onboarding.
        if (previous.waitFor() == 0) requireSubset(baseline, baselineLines(old));
    }

    static void requireSubset(List<String> current, List<String> baseline) {
        Map<String, Integer> remaining = new HashMap<>();
        baseline.forEach(s -> remaining.merge(s, 1, Integer::sum));
        for (String entry : current) {
            int count = remaining.getOrDefault(entry, 0);
            if (count == 0) throw new IllegalStateException("New Compose boolean debt is forbidden: " + entry);
            remaining.put(entry, count - 1);
        }
    }
}
