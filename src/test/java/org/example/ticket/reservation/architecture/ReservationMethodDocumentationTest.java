package org.example.ticket.reservation.architecture;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.doctree.DocCommentTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationMethodDocumentationTest {

    private static final Path RESERVATION_SOURCE = Path.of(
            "src/main/java/org/example/ticket/reservation"
    );

    @Test
    void everyDeclaredMethodAndConstructorHasJavadoc() throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("JDK compiler is required").isNotNull();

        List<Path> sources;
        try (var paths = Files.walk(RESERVATION_SOURCE)) {
            sources = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }

        List<String> undocumented = new ArrayList<>();
        List<String> tooShort = new ArrayList<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            var compilationUnits = fileManager.getJavaFileObjectsFromPaths(sources);
            JavacTask task = (JavacTask) compiler.getTask(
                    null,
                    fileManager,
                    null,
                    List.of("-proc:none"),
                    null,
                    compilationUnits
            );
            DocTrees docTrees = DocTrees.instance(task);

            for (CompilationUnitTree unit : task.parse()) {
                new TreePathScanner<Void, Void>() {
                    @Override
                    public Void visitMethod(MethodTree method, Void unused) {
                        DocCommentTree comment = docTrees.getDocCommentTree(getCurrentPath());
                        if (comment == null) {
                            long position = docTrees.getSourcePositions().getStartPosition(unit, method);
                            long line = unit.getLineMap().getLineNumber(position);
                            undocumented.add(unit.getSourceFile().getName() + ":" + line
                                    + " " + displayName(method));
                        } else if (descriptionLineCount(comment) < 2) {
                            long position = docTrees.getSourcePositions().getStartPosition(unit, method);
                            long line = unit.getLineMap().getLineNumber(position);
                            tooShort.add(unit.getSourceFile().getName() + ":" + line
                                    + " " + displayName(method));
                        }
                        return super.visitMethod(method, unused);
                    }

                    private long descriptionLineCount(DocCommentTree comment) {
                        return comment.getFullBody().stream()
                                .flatMap(part -> part.toString().lines())
                                .map(String::trim)
                                .filter(line -> !line.isEmpty())
                                .count();
                    }

                    private String displayName(MethodTree method) {
                        if (!method.getName().contentEquals("<init>")) {
                            return method.getName().toString();
                        }
                        if (getCurrentPath().getParentPath().getLeaf() instanceof ClassTree owner) {
                            return owner.getSimpleName().toString();
                        }
                        return method.getName().toString();
                    }
                }.scan(unit, null);
            }
        }

        assertThat(undocumented)
                .as("Reservation production methods without Javadoc")
                .isEmpty();
        assertThat(tooShort)
                .as("Reservation production methods with Javadoc shorter than two lines")
                .isEmpty();
    }
}
