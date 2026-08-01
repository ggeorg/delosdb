/*
   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.
*/
package org.apache.derbyBuild;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.SynchronizedTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Stream;

import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Inventory-only human maintainability audit.
 *
 * <p>The task reports review signals. It deliberately does not convert human
 * thresholds into automatic build failures.</p>
 */
public final class DelosHumanMaintainabilityAudit {
    private final Path root;
    private final Path reportDirectory;
    private final Set<String> authoredPaths;
    private final Set<String> modifiedInheritedPaths;

    private final List<SourceRecord> sources = new ArrayList<>();
    private final List<ClassRecord> classes = new ArrayList<>();
    private final List<MethodRecord> methods = new ArrayList<>();
    private final List<String> parseErrors = new ArrayList<>();

    private DelosHumanMaintainabilityAudit(Options options) throws IOException {
        root = options.root.toAbsolutePath().normalize();
        reportDirectory = options.reportDirectory.toAbsolutePath().normalize();
        authoredPaths = loadManifest(options.authoredManifest);
        modifiedInheritedPaths = loadManifest(options.modifiedInheritedManifest);
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        new DelosHumanMaintainabilityAudit(options).run();
    }

    private void run() throws Exception {
        collectSources();
        parseSources();
        resolveCallersAndForwardingChains();
        Files.createDirectories(reportDirectory);
        writeSummary();
        writeMethodInventory();
        writeFragmentationCandidates();
        writeMethodReviewOutliers();
        writeClassReviewOutliers();
        writeForwardingChains();
        writeSingleImplementationInterfaces();
        writeHumanReport();
        writeParseErrors();
        if (!parseErrors.isEmpty()) {
            throw new IllegalStateException(
                    "Human-maintainability inventory found Java parse errors; see "
                            + reportDirectory.resolve("parse-errors.txt"));
        }
    }

    private void collectSources() throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(Predicate.not(this::excluded))
                    .filter(path -> relative(path).contains("/src/main/java/"))
                    .sorted(Comparator.comparing(this::relative))
                    .forEach(path -> sources.add(readSource(path)));
        }
    }

    private boolean excluded(Path path) {
        Path relative = root.relativize(path.toAbsolutePath().normalize());
        for (Path part : relative) {
            String value = part.toString();
            if (value.equals("build") || value.equals(".gradle")
                    || value.equals(".git") || value.equals("out")) {
                return true;
            }
        }
        return false;
    }

    private SourceRecord readSource(Path path) {
        try {
            String relative = relative(path);
            String text = Files.readString(path, StandardCharsets.UTF_8);
            SourceCategory category = authoredPaths.contains(relative)
                    ? SourceCategory.AUTHORED
                    : modifiedInheritedPaths.contains(relative)
                            ? SourceCategory.MODIFIED_INHERITED
                            : SourceCategory.UNMODIFIED_INHERITED;
            return new SourceRecord(path, relative, text, category);
        } catch (IOException ioe) {
            throw new IllegalStateException("Could not read " + path, ioe);
        }
    }

    private void parseSources() throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("A full JDK is required");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(
                diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fileManager
                    .getJavaFileObjectsFromFiles(sources.stream()
                            .map(source -> source.path.toFile()).toList());
            JavacTask task = (JavacTask) compiler.getTask(
                    null, fileManager, diagnostics,
                    List.of("-proc:none", "-Xlint:none"), null, units);
            Iterable<? extends CompilationUnitTree> parsed = task.parse();
            Trees trees = Trees.instance(task);
            SourcePositions positions = trees.getSourcePositions();
            Map<Path, SourceRecord> byPath = new HashMap<>();
            for (SourceRecord source : sources) {
                byPath.put(source.path.toAbsolutePath().normalize(), source);
            }
            for (CompilationUnitTree unit : parsed) {
                Path path = Paths.get(unit.getSourceFile().toUri())
                        .toAbsolutePath().normalize();
                SourceRecord source = byPath.get(path);
                if (source == null) {
                    continue;
                }
                source.packageName = unit.getPackageName() == null
                        ? "" : unit.getPackageName().toString();
                for (ImportTree importTree : unit.getImports()) {
                    String imported = importTree.getQualifiedIdentifier().toString();
                    if (!imported.startsWith("java.lang.")
                            && !imported.startsWith(source.packageName + ".")) {
                        source.externalImports.add(imported);
                    }
                }
                new InventoryScanner(source, unit, positions).scan(unit, null);
            }
        }
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                String source = diagnostic.getSource() == null
                        ? "<none>" : diagnostic.getSource().getName();
                parseErrors.add(source + ":" + diagnostic.getLineNumber() + ":"
                        + diagnostic.getMessage(Locale.ROOT));
            }
        }
    }

    private final class InventoryScanner extends TreePathScanner<Void, Void> {
        private final SourceRecord source;
        private final CompilationUnitTree unit;
        private final SourcePositions positions;
        private final Deque<ClassRecord> owners = new ArrayDeque<>();

        InventoryScanner(SourceRecord source, CompilationUnitTree unit,
                SourcePositions positions) {
            this.source = source;
            this.unit = unit;
            this.positions = positions;
        }

        @Override
        public Void visitClass(ClassTree node, Void unused) {
            String simpleName = node.getSimpleName().toString();
            if (simpleName.isEmpty()) {
                return super.visitClass(node, unused);
            }
            String owner = owners.isEmpty() ? simpleName
                    : owners.peek().owner + "$" + simpleName;
            ClassRecord record = new ClassRecord(
                    source, owner, simpleName, node.getKind().name(),
                    line(positions.getStartPosition(unit, node)),
                    line(positions.getEndPosition(unit, node)),
                    node.getModifiers().getFlags().contains(Modifier.PUBLIC),
                    owners.isEmpty() ? source.externalImports.size() : 0);
            if (node.getExtendsClause() != null) {
                record.superType = simpleType(node.getExtendsClause().toString());
            }
            for (Tree implemented : node.getImplementsClause()) {
                record.interfaces.add(simpleType(implemented.toString()));
            }
            classes.add(record);
            owners.push(record);
            try {
                return super.visitClass(node, unused);
            } finally {
                owners.pop();
            }
        }

        @Override
        public Void visitVariable(VariableTree node, Void unused) {
            if (!owners.isEmpty() && getCurrentPath().getParentPath() != null
                    && getCurrentPath().getParentPath().getLeaf() instanceof ClassTree) {
                ClassRecord owner = owners.peek();
                owner.fields++;
                Set<Modifier> flags = node.getModifiers().getFlags();
                if (!flags.contains(Modifier.FINAL)) {
                    owner.mutableFields++;
                }
            }
            return super.visitVariable(node, unused);
        }

        @Override
        public Void visitMethod(MethodTree node, Void unused) {
            if (owners.isEmpty()) {
                return super.visitMethod(node, unused);
            }
            ClassRecord owner = owners.peek();
            boolean constructor = node.getName().contentEquals("<init>");
            Set<Modifier> flags = node.getModifiers().getFlags();
            MethodRecord record = new MethodRecord(
                    source, owner, constructor ? "<init>" : node.getName().toString(),
                    constructor,
                    flags.contains(Modifier.PRIVATE),
                    flags.contains(Modifier.PUBLIC),
                    hasAnnotation(node.getModifiers(), "Override"),
                    flags.contains(Modifier.ABSTRACT),
                    flags.contains(Modifier.NATIVE),
                    line(positions.getStartPosition(unit, node)),
                    line(positions.getEndPosition(unit, node)),
                    node.getParameters().size(),
                    (int) node.getParameters().stream()
                            .filter(parameter -> isBoolean(parameter.getType().toString()))
                            .count());
            owner.methods++;
            if (record.isPublic) {
                owner.publicMethods++;
            }
            if (node.getBody() != null) {
                record.executableLines = executableLines(node.getBody());
                MethodMetrics metrics = new MethodMetrics(record);
                metrics.scan(node.getBody(), 0);
                record.statements = metrics.statements;
                record.complexity = metrics.branches + metrics.loops + metrics.catches;
                record.maxNesting = metrics.maxNesting;
                record.invocations = List.copyOf(metrics.invocations);
                record.allocations = metrics.allocations;
                if (record.statements > 1) {
                    owner.nonTrivialMethods++;
                }
                record.pureForwarder = !record.constructor
                        && record.statements == 1
                        && record.complexity == 0
                        && record.invocations.size() == 1
                        && record.allocations == 0;
            }
            owner.methodRecords.add(record);
            methods.add(record);
            return super.visitMethod(node, unused);
        }

        private int executableLines(BlockTree body) {
            long start = positions.getStartPosition(unit, body);
            long end = positions.getEndPosition(unit, body);
            if (start < 0 || end < start || end > source.text.length()) {
                return 0;
            }
            String[] lines = source.text.substring((int) start, (int) end)
                    .split("\\R", -1);
            int count = 0;
            boolean blockComment = false;
            for (String raw : lines) {
                String line = raw.trim();
                if (blockComment) {
                    int close = line.indexOf("*/");
                    if (close < 0) {
                        continue;
                    }
                    line = line.substring(close + 2).trim();
                    blockComment = false;
                }
                while (line.startsWith("/*")) {
                    int close = line.indexOf("*/", 2);
                    if (close < 0) {
                        blockComment = true;
                        line = "";
                        break;
                    }
                    line = line.substring(close + 2).trim();
                }
                if (line.isEmpty() || line.equals("{") || line.equals("}")
                        || line.startsWith("//") || line.startsWith("*")
                        || line.startsWith("@")) {
                    continue;
                }
                count++;
            }
            return count;
        }

        private long line(long position) {
            return position < 0 ? 0 : unit.getLineMap().getLineNumber(position);
        }
    }

    private static final class MethodMetrics extends TreeScanner<Void, Integer> {
        final MethodRecord method;
        final List<String> invocations = new ArrayList<>();
        int statements;
        int branches;
        int loops;
        int catches;
        int maxNesting;
        int allocations;

        MethodMetrics(MethodRecord method) {
            this.method = method;
        }

        @Override
        public Void scan(Tree tree, Integer depth) {
            return tree == null ? null : super.scan(tree, depth == null ? 0 : depth);
        }

        @Override
        public Void visitIf(IfTree node, Integer depth) {
            statements++;
            branches++;
            nested(depth);
            return super.visitIf(node, depth + 1);
        }

        @Override
        public Void visitSwitch(SwitchTree node, Integer depth) {
            statements++;
            branches += Math.max(1, node.getCases().size());
            nested(depth);
            return super.visitSwitch(node, depth + 1);
        }

        @Override
        public Void visitSwitchExpression(SwitchExpressionTree node, Integer depth) {
            statements++;
            branches += Math.max(1, node.getCases().size());
            nested(depth);
            return super.visitSwitchExpression(node, depth + 1);
        }

        @Override
        public Void visitForLoop(ForLoopTree node, Integer depth) {
            statements++;
            loops++;
            nested(depth);
            return super.visitForLoop(node, depth + 1);
        }

        @Override
        public Void visitEnhancedForLoop(EnhancedForLoopTree node, Integer depth) {
            statements++;
            loops++;
            nested(depth);
            return super.visitEnhancedForLoop(node, depth + 1);
        }

        @Override
        public Void visitWhileLoop(WhileLoopTree node, Integer depth) {
            statements++;
            loops++;
            nested(depth);
            return super.visitWhileLoop(node, depth + 1);
        }

        @Override
        public Void visitDoWhileLoop(DoWhileLoopTree node, Integer depth) {
            statements++;
            loops++;
            nested(depth);
            return super.visitDoWhileLoop(node, depth + 1);
        }

        @Override
        public Void visitTry(TryTree node, Integer depth) {
            statements++;
            nested(depth);
            return super.visitTry(node, depth + 1);
        }

        @Override
        public Void visitCatch(CatchTree node, Integer depth) {
            catches++;
            nested(depth);
            return super.visitCatch(node, depth + 1);
        }

        @Override
        public Void visitSynchronized(SynchronizedTree node, Integer depth) {
            statements++;
            nested(depth);
            return super.visitSynchronized(node, depth + 1);
        }

        @Override
        public Void visitLambdaExpression(LambdaExpressionTree node, Integer depth) {
            nested(depth);
            return super.visitLambdaExpression(node, depth + 1);
        }

        @Override
        public Void visitExpressionStatement(ExpressionStatementTree node, Integer depth) {
            statements++;
            return super.visitExpressionStatement(node, depth);
        }

        @Override
        public Void visitVariable(VariableTree node, Integer depth) {
            statements++;
            return super.visitVariable(node, depth);
        }

        @Override
        public Void visitReturn(ReturnTree node, Integer depth) {
            statements++;
            return super.visitReturn(node, depth);
        }

        @Override
        public Void visitThrow(ThrowTree node, Integer depth) {
            statements++;
            return super.visitThrow(node, depth);
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Integer depth) {
            invocations.add(invocationName(node));
            return super.visitMethodInvocation(node, depth);
        }

        @Override
        public Void visitNewClass(NewClassTree node, Integer depth) {
            allocations++;
            return super.visitNewClass(node, depth);
        }

        private void nested(int depth) {
            maxNesting = Math.max(maxNesting, depth);
        }
    }

    private void resolveCallersAndForwardingChains() {
        Map<ClassRecord, Map<String, List<MethodRecord>>> methodsByClass = new LinkedHashMap<>();
        for (ClassRecord classRecord : classes) {
            Map<String, List<MethodRecord>> byName = new LinkedHashMap<>();
            for (MethodRecord method : classRecord.methodRecords) {
                byName.computeIfAbsent(method.name, ignored -> new ArrayList<>()).add(method);
            }
            methodsByClass.put(classRecord, byName);
        }
        for (ClassRecord classRecord : classes) {
            Map<String, List<MethodRecord>> byName = methodsByClass.get(classRecord);
            for (MethodRecord caller : classRecord.methodRecords) {
                for (String invocation : caller.invocations) {
                    List<MethodRecord> targets = byName.get(invocation);
                    if (targets == null) {
                        continue;
                    }
                    for (MethodRecord target : targets) {
                        if (target != caller) {
                            target.localCallerNames.add(caller.name);
                        }
                    }
                }
            }
            for (MethodRecord method : classRecord.methodRecords) {
                if (method.pureForwarder && method.invocations.size() == 1) {
                    String targetName = method.invocations.get(0);
                    List<MethodRecord> targets = byName.get(targetName);
                    if (targets != null) {
                        method.forwardTarget = targets.stream()
                                .filter(target -> target != method)
                                .findFirst().orElse(null);
                    }
                }
            }
        }
    }

    private void writeSummary() throws IOException {
        Properties summary = new Properties();
        for (SourceCategory category : SourceCategory.values()) {
            String prefix = category.propertyPrefix;
            List<MethodRecord> categoryMethods = methods.stream()
                    .filter(method -> method.source.category == category).toList();
            List<ClassRecord> categoryClasses = classes.stream()
                    .filter(record -> record.source.category == category).toList();
            summary.setProperty(prefix + "Files", Integer.toString((int) sources.stream()
                    .filter(source -> source.category == category).count()));
            summary.setProperty(prefix + "Methods", Integer.toString(categoryMethods.size()));
            summary.setProperty(prefix + "TinyMethods1To3", Integer.toString(count(categoryMethods,
                    method -> !method.declarationOnly() && method.executableLines >= 1
                            && method.executableLines <= 3)));
            summary.setProperty(prefix + "TinyMethods4To5", Integer.toString(count(categoryMethods,
                    method -> !method.declarationOnly() && method.executableLines >= 4
                            && method.executableLines <= 5)));
            summary.setProperty(prefix + "PrivateTinyMethods", Integer.toString(count(categoryMethods,
                    MethodRecord::privateTiny)));
            summary.setProperty(prefix + "PrivateTinyOneCaller", Integer.toString(count(categoryMethods,
                    method -> method.privateTiny() && method.localCallerNames.size() <= 1)));
            summary.setProperty(prefix + "PureForwarders", Integer.toString(count(categoryMethods,
                    method -> method.pureForwarder)));
            summary.setProperty(prefix + "PrivateForwardersOneCaller", Integer.toString(count(categoryMethods,
                    method -> method.isPrivate && method.pureForwarder
                            && method.localCallerNames.size() <= 1)));
            summary.setProperty(prefix + "MethodsOver80Lines", Integer.toString(count(categoryMethods,
                    method -> method.executableLines > 80)));
            summary.setProperty(prefix + "MethodsOver150Lines", Integer.toString(count(categoryMethods,
                    method -> method.executableLines > 150)));
            summary.setProperty(prefix + "MethodsComplexityOver15", Integer.toString(count(categoryMethods,
                    method -> method.complexity > 15)));
            summary.setProperty(prefix + "MethodsComplexityOver25", Integer.toString(count(categoryMethods,
                    method -> method.complexity > 25)));
            summary.setProperty(prefix + "MethodsNestingOver4", Integer.toString(count(categoryMethods,
                    method -> method.maxNesting > 4)));
            summary.setProperty(prefix + "MethodsNestingOver6", Integer.toString(count(categoryMethods,
                    method -> method.maxNesting > 6)));
            summary.setProperty(prefix + "MethodsParametersOver6", Integer.toString(count(categoryMethods,
                    method -> method.parameters > 6)));
            summary.setProperty(prefix + "MethodsParametersOver9", Integer.toString(count(categoryMethods,
                    method -> method.parameters > 9)));
            summary.setProperty(prefix + "MethodsBooleanParametersOver2", Integer.toString(count(categoryMethods,
                    method -> method.booleanParameters > 2)));
            summary.setProperty(prefix + "MethodsBooleanParametersOver3", Integer.toString(count(categoryMethods,
                    method -> method.booleanParameters > 3)));
            summary.setProperty(prefix + "Classes", Integer.toString(categoryClasses.size()));
            summary.setProperty(prefix + "ClassesOver750Lines", Integer.toString(count(categoryClasses,
                    record -> record.lines() > 750)));
            summary.setProperty(prefix + "ClassesOver1200Lines", Integer.toString(count(categoryClasses,
                    record -> record.lines() > 1200)));
            summary.setProperty(prefix + "ClassesNonTrivialMethodsOver40", Integer.toString(count(categoryClasses,
                    record -> record.nonTrivialMethods > 40)));
            summary.setProperty(prefix + "ClassesNonTrivialMethodsOver70", Integer.toString(count(categoryClasses,
                    record -> record.nonTrivialMethods > 70)));
            summary.setProperty(prefix + "ClassesMutableFieldsOver12", Integer.toString(count(categoryClasses,
                    record -> record.mutableFields > 12)));
            summary.setProperty(prefix + "ClassesMutableFieldsOver20", Integer.toString(count(categoryClasses,
                    record -> record.mutableFields > 20)));
            summary.setProperty(prefix + "ClassesExternalDependenciesOver10", Integer.toString(count(categoryClasses,
                    record -> record.externalDependencies > 10)));
            summary.setProperty(prefix + "ClassesExternalDependenciesOver16", Integer.toString(count(categoryClasses,
                    record -> record.externalDependencies > 16)));
        }
        summary.setProperty("parseErrors", Integer.toString(parseErrors.size()));
        try (PrintWriter writer = writer("summary.properties")) {
            for (String name : new TreeSet<>(summary.stringPropertyNames())) {
                writer.println(name + "=" + summary.getProperty(name));
            }
        }
    }

    private void writeMethodInventory() throws IOException {
        try (PrintWriter writer = writer("method-inventory.tsv")) {
            writer.println("category\tfile\towner\tmethod\tline\texecutableLines\tsourceLines\tstatements\tcomplexity\tnesting\tparameters\tbooleanParameters\tprivate\tpublic\toverride\tcallers\tpureForwarder\tforwardTarget");
            methods.stream().sorted(MethodRecord.ORDER).forEach(method -> writer.println(
                    method.source.category + "\t" + method.source.relative + "\t"
                            + method.owner.owner + "\t" + method.name + "\t"
                            + method.startLine + "\t" + method.executableLines + "\t"
                            + method.sourceLines() + "\t" + method.statements + "\t"
                            + method.complexity + "\t" + method.maxNesting + "\t"
                            + method.parameters + "\t" + method.booleanParameters + "\t"
                            + method.isPrivate + "\t" + method.isPublic + "\t"
                            + method.isOverride + "\t" + method.localCallerNames.size() + "\t"
                            + method.pureForwarder + "\t"
                            + (method.forwardTarget == null ? "" : method.forwardTarget.name)));
        }
    }

    private void writeFragmentationCandidates() throws IOException {
        try (PrintWriter writer = writer("fragmentation-candidates.tsv")) {
            writer.println("status\tcategory\tfile\towner\tmethod\tline\texecutableLines\tstatements\tcallers\tpureForwarder\tforwardTarget\treviewReason");
            methods.stream()
                    .filter(method -> method.source.category != SourceCategory.UNMODIFIED_INHERITED)
                    .filter(MethodRecord::fragmentationCandidate)
                    .sorted(MethodRecord.ORDER)
                    .forEach(method -> writer.println("UNCLASSIFIED\t"
                            + method.source.category + "\t" + method.source.relative + "\t"
                            + method.owner.owner + "\t" + method.name + "\t"
                            + method.startLine + "\t" + method.executableLines + "\t"
                            + method.statements + "\t" + method.localCallerNames.size() + "\t"
                            + method.pureForwarder + "\t"
                            + (method.forwardTarget == null ? "" : method.forwardTarget.name)
                            + "\tprivate tiny method with zero/one local caller"));
        }
    }

    private void writeMethodReviewOutliers() throws IOException {
        try (PrintWriter writer = writer("method-review-outliers.tsv")) {
            writer.println("status\tcategory\tfile\towner\tmethod\tline\texecutableLines\tcomplexity\tnesting\tparameters\tbooleanParameters\treasons");
            methods.stream()
                    .filter(method -> method.source.category != SourceCategory.UNMODIFIED_INHERITED)
                    .filter(MethodRecord::methodReviewOutlier)
                    .sorted(MethodRecord.ORDER)
                    .forEach(method -> writer.println("UNCLASSIFIED\t"
                            + method.source.category + "\t" + method.source.relative + "\t"
                            + method.owner.owner + "\t" + method.name + "\t"
                            + method.startLine + "\t" + method.executableLines + "\t"
                            + method.complexity + "\t" + method.maxNesting + "\t"
                            + method.parameters + "\t" + method.booleanParameters + "\t"
                            + String.join(",", method.reviewReasons())));
        }
    }

    private void writeClassReviewOutliers() throws IOException {
        try (PrintWriter writer = writer("class-review-outliers.tsv")) {
            writer.println("status\tcategory\tfile\tclass\tkind\tline\tlines\tmethods\tnonTrivialMethods\tmutableFields\texternalDependencies\tpublicMethods\treasons");
            classes.stream()
                    .filter(record -> record.source.category != SourceCategory.UNMODIFIED_INHERITED)
                    .filter(ClassRecord::classReviewOutlier)
                    .sorted(ClassRecord.ORDER)
                    .forEach(record -> writer.println("UNCLASSIFIED\t"
                            + record.source.category + "\t" + record.source.relative + "\t"
                            + record.owner + "\t" + record.kind + "\t" + record.startLine + "\t"
                            + record.lines() + "\t" + record.methods + "\t"
                            + record.nonTrivialMethods + "\t" + record.mutableFields + "\t"
                            + record.externalDependencies + "\t" + record.publicMethods + "\t"
                            + String.join(",", record.reviewReasons())));
        }
    }

    private void writeForwardingChains() throws IOException {
        try (PrintWriter writer = writer("forwarding-chains.tsv")) {
            writer.println("status\tcategory\tfile\towner\tstartMethod\tline\tdepth\tchain");
            for (MethodRecord method : methods.stream().sorted(MethodRecord.ORDER).toList()) {
                if (method.source.category == SourceCategory.UNMODIFIED_INHERITED
                        || !method.pureForwarder || method.forwardTarget == null) {
                    continue;
                }
                List<String> chain = forwardingChain(method);
                if (chain.size() - 1 >= 2) {
                    writer.println("UNCLASSIFIED\t" + method.source.category + "\t"
                            + method.source.relative + "\t" + method.owner.owner + "\t"
                            + method.name + "\t" + method.startLine + "\t"
                            + (chain.size() - 1) + "\t" + String.join(" -> ", chain));
                }
            }
        }
    }

    private List<String> forwardingChain(MethodRecord start) {
        List<String> chain = new ArrayList<>();
        Set<MethodRecord> seen = new HashSet<>();
        MethodRecord current = start;
        while (current != null && seen.add(current)) {
            chain.add(current.name);
            current = current.forwardTarget;
        }
        return chain;
    }

    private void writeSingleImplementationInterfaces() throws IOException {
        Map<String, Integer> implementationCounts = new HashMap<>();
        for (ClassRecord record : classes) {
            for (String interfaceName : record.interfaces) {
                implementationCounts.merge(interfaceName, 1, Integer::sum);
            }
        }
        try (PrintWriter writer = writer("single-implementation-interfaces.tsv")) {
            writer.println("status\tcategory\tfile\tinterface\tline\timplementationCount\treviewReason");
            classes.stream()
                    .filter(record -> record.kind.equals("INTERFACE"))
                    .filter(record -> record.source.category != SourceCategory.UNMODIFIED_INHERITED)
                    .filter(record -> implementationCounts.getOrDefault(record.simpleName, 0) <= 1)
                    .sorted(ClassRecord.ORDER)
                    .forEach(record -> writer.println("UNCLASSIFIED\t"
                            + record.source.category + "\t" + record.source.relative + "\t"
                            + record.owner + "\t" + record.startLine + "\t"
                            + implementationCounts.getOrDefault(record.simpleName, 0)
                            + "\tinterface has zero/one syntactic production implementation"));
        }
    }

    private void writeHumanReport() throws IOException {
        Map<SourceCategory, List<MethodRecord>> byCategory = new EnumMap<>(SourceCategory.class);
        for (SourceCategory category : SourceCategory.values()) {
            byCategory.put(category, methods.stream()
                    .filter(method -> method.source.category == category).toList());
        }
        try (PrintWriter writer = writer("human-maintainability-report.txt")) {
            writer.println("DelosDB human-maintainability inventory");
            writer.println("=====================================");
            writer.println();
            writer.println("This report contains review signals, not automatic quality verdicts.");
            writer.println("Human classification is required before refactoring or baselining.");
            writer.println();
            for (SourceCategory category : SourceCategory.values()) {
                List<MethodRecord> categoryMethods = byCategory.get(category);
                writer.println(category + ":");
                writer.println("  files: " + sources.stream()
                        .filter(source -> source.category == category).count());
                writer.println("  methods: " + categoryMethods.size());
                writer.println("  1-3 executable lines: " + count(categoryMethods,
                        method -> !method.declarationOnly()
                                && method.executableLines >= 1
                                && method.executableLines <= 3));
                writer.println("  4-5 executable lines: " + count(categoryMethods,
                        method -> !method.declarationOnly()
                                && method.executableLines >= 4
                                && method.executableLines <= 5));
                writer.println("  private tiny methods: " + count(categoryMethods,
                        MethodRecord::privateTiny));
                writer.println("  private tiny methods with zero/one local caller: "
                        + count(categoryMethods, method -> method.privateTiny()
                                && method.localCallerNames.size() <= 1));
                writer.println("  pure forwarding methods: " + count(categoryMethods,
                        method -> method.pureForwarder));
                writer.println("  method review outliers: " + count(categoryMethods,
                        MethodRecord::methodReviewOutlier));
                writer.println("  class review outliers: " + classes.stream()
                        .filter(record -> record.source.category == category)
                        .filter(ClassRecord::classReviewOutlier).count());
                writer.println();
            }
            writer.println("Not measured automatically in this first inventory:");
            writer.println("- cognitive complexity");
            writer.println("- distinct responsibility phases");
            writer.println("- field-use cohesion clusters");
            writer.println("- package strongly connected components");
            writer.println("- cross-class decisive-flow navigation");
            writer.println("- runtime allocation, latency, throughput, and contention");
            writer.println();
            writer.println("These require later structural implementation or runtime evidence;");
            writer.println("they must not be guessed from method length or call counts.");
        }
    }

    private void writeParseErrors() throws IOException {
        try (PrintWriter writer = writer("parse-errors.txt")) {
            for (String error : parseErrors) {
                writer.println(error);
            }
        }
    }

    private PrintWriter writer(String name) throws IOException {
        return new PrintWriter(Files.newBufferedWriter(
                reportDirectory.resolve(name), StandardCharsets.UTF_8));
    }

    private Set<String> loadManifest(Path manifest) throws IOException {
        Set<String> values = new TreeSet<>();
        for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
            String value = line.trim();
            if (!value.isEmpty() && !value.startsWith("#")) {
                values.add(value.replace(File.separatorChar, '/'));
            }
        }
        return Set.copyOf(values);
    }

    private String relative(Path path) {
        return root.relativize(path.toAbsolutePath().normalize())
                .toString().replace(File.separatorChar, '/');
    }

    private static boolean hasAnnotation(ModifiersTree modifiers, String simpleName) {
        for (AnnotationTree annotation : modifiers.getAnnotations()) {
            String name = annotation.getAnnotationType().toString();
            if (name.equals(simpleName) || name.endsWith("." + simpleName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBoolean(String type) {
        return type.equals("boolean") || type.equals("Boolean")
                || type.endsWith(".Boolean");
    }

    private static String invocationName(MethodInvocationTree invocation) {
        Tree select = invocation.getMethodSelect();
        if (select instanceof IdentifierTree identifier) {
            return identifier.getName().toString();
        }
        if (select instanceof MemberSelectTree member) {
            return member.getIdentifier().toString();
        }
        return select.toString();
    }

    private static String simpleType(String type) {
        String value = type;
        int generic = value.indexOf('<');
        if (generic >= 0) {
            value = value.substring(0, generic);
        }
        int dot = value.lastIndexOf('.');
        return dot >= 0 ? value.substring(dot + 1) : value;
    }

    private static <T> int count(Collection<T> values, Predicate<T> predicate) {
        return (int) values.stream().filter(predicate).count();
    }

    private enum SourceCategory {
        AUTHORED("authored"),
        MODIFIED_INHERITED("modifiedInherited"),
        UNMODIFIED_INHERITED("unmodifiedInherited");

        final String propertyPrefix;

        SourceCategory(String propertyPrefix) {
            this.propertyPrefix = propertyPrefix;
        }
    }

    private static final class SourceRecord {
        final Path path;
        final String relative;
        final String text;
        final SourceCategory category;
        final Set<String> externalImports = new TreeSet<>();
        String packageName = "";

        SourceRecord(Path path, String relative, String text,
                SourceCategory category) {
            this.path = path;
            this.relative = relative;
            this.text = text;
            this.category = category;
        }
    }

    private static final class ClassRecord {
        static final Comparator<ClassRecord> ORDER = Comparator
                .comparing((ClassRecord record) -> record.source.relative)
                .thenComparingLong(record -> record.startLine)
                .thenComparing(record -> record.owner);

        final SourceRecord source;
        final String owner;
        final String simpleName;
        final String kind;
        final long startLine;
        final long endLine;
        final boolean isPublic;
        final int externalDependencies;
        final List<String> interfaces = new ArrayList<>();
        final List<MethodRecord> methodRecords = new ArrayList<>();
        String superType = "";
        int methods;
        int nonTrivialMethods;
        int fields;
        int mutableFields;
        int publicMethods;

        ClassRecord(SourceRecord source, String owner, String simpleName,
                String kind, long startLine, long endLine, boolean isPublic,
                int externalDependencies) {
            this.source = source;
            this.owner = owner;
            this.simpleName = simpleName;
            this.kind = kind;
            this.startLine = startLine;
            this.endLine = endLine;
            this.isPublic = isPublic;
            this.externalDependencies = externalDependencies;
        }

        int lines() {
            return endLine < startLine ? 0 : (int) (endLine - startLine + 1);
        }

        boolean classReviewOutlier() {
            return lines() > 750 || nonTrivialMethods > 40 || mutableFields > 12
                    || externalDependencies > 10;
        }

        List<String> reviewReasons() {
            List<String> reasons = new ArrayList<>();
            if (lines() > 750) reasons.add("lines>750");
            if (lines() > 1200) reasons.add("lines>1200");
            if (nonTrivialMethods > 40) reasons.add("nonTrivialMethods>40");
            if (nonTrivialMethods > 70) reasons.add("nonTrivialMethods>70");
            if (mutableFields > 12) reasons.add("mutableFields>12");
            if (mutableFields > 20) reasons.add("mutableFields>20");
            if (externalDependencies > 10) reasons.add("externalDependencies>10");
            if (externalDependencies > 16) reasons.add("externalDependencies>16");
            return reasons;
        }
    }

    private static final class MethodRecord {
        static final Comparator<MethodRecord> ORDER = Comparator
                .comparing((MethodRecord record) -> record.source.relative)
                .thenComparingLong(record -> record.startLine)
                .thenComparing(record -> record.owner.owner)
                .thenComparing(record -> record.name);

        final SourceRecord source;
        final ClassRecord owner;
        final String name;
        final boolean constructor;
        final boolean isPrivate;
        final boolean isPublic;
        final boolean isOverride;
        final boolean isAbstract;
        final boolean isNative;
        final long startLine;
        final long endLine;
        final int parameters;
        final int booleanParameters;
        final Set<String> localCallerNames = new TreeSet<>();
        List<String> invocations = List.of();
        int executableLines;
        int statements;
        int complexity;
        int maxNesting;
        int allocations;
        boolean pureForwarder;
        MethodRecord forwardTarget;

        MethodRecord(SourceRecord source, ClassRecord owner, String name,
                boolean constructor, boolean isPrivate, boolean isPublic,
                boolean isOverride, boolean isAbstract, boolean isNative,
                long startLine, long endLine, int parameters,
                int booleanParameters) {
            this.source = source;
            this.owner = owner;
            this.name = name;
            this.constructor = constructor;
            this.isPrivate = isPrivate;
            this.isPublic = isPublic;
            this.isOverride = isOverride;
            this.isAbstract = isAbstract;
            this.isNative = isNative;
            this.startLine = startLine;
            this.endLine = endLine;
            this.parameters = parameters;
            this.booleanParameters = booleanParameters;
        }

        int sourceLines() {
            return endLine < startLine ? 0 : (int) (endLine - startLine + 1);
        }

        boolean declarationOnly() {
            return isAbstract || isNative || executableLines == 0;
        }

        boolean privateTiny() {
            return isPrivate && !constructor && !isOverride && !declarationOnly()
                    && executableLines <= 5;
        }

        boolean fragmentationCandidate() {
            return privateTiny() && localCallerNames.size() <= 1
                    && (pureForwarder || statements <= 1);
        }

        boolean methodReviewOutlier() {
            return !declarationOnly() && (executableLines > 80 || complexity > 15
                    || maxNesting > 4 || parameters > 6 || booleanParameters > 2);
        }

        List<String> reviewReasons() {
            List<String> reasons = new ArrayList<>();
            if (executableLines > 80) reasons.add("lines>80");
            if (executableLines > 150) reasons.add("lines>150");
            if (complexity > 15) reasons.add("complexity>15");
            if (complexity > 25) reasons.add("complexity>25");
            if (maxNesting > 4) reasons.add("nesting>4");
            if (maxNesting > 6) reasons.add("nesting>6");
            if (parameters > 6) reasons.add("parameters>6");
            if (parameters > 9) reasons.add("parameters>9");
            if (booleanParameters > 2) reasons.add("booleanParameters>2");
            if (booleanParameters > 3) reasons.add("booleanParameters>3");
            return reasons;
        }
    }

    private static final class Options {
        final Path root;
        final Path reportDirectory;
        final Path authoredManifest;
        final Path modifiedInheritedManifest;

        Options(Path root, Path reportDirectory, Path authoredManifest,
                Path modifiedInheritedManifest) {
            this.root = root;
            this.reportDirectory = reportDirectory;
            this.authoredManifest = authoredManifest;
            this.modifiedInheritedManifest = modifiedInheritedManifest;
        }

        static Options parse(String[] args) {
            Path root = null;
            Path reportDirectory = null;
            Path authoredManifest = null;
            Path modifiedInheritedManifest = null;
            for (int i = 0; i < args.length; i++) {
                String argument = args[i];
                switch (argument) {
                    case "--root" -> root = Path.of(requireValue(args, ++i, argument));
                    case "--report-dir" -> reportDirectory = Path.of(requireValue(args, ++i, argument));
                    case "--authored-manifest" -> authoredManifest = Path.of(requireValue(args, ++i, argument));
                    case "--modified-inherited-manifest" -> modifiedInheritedManifest = Path.of(requireValue(args, ++i, argument));
                    default -> throw new IllegalArgumentException("Unknown argument: " + argument);
                }
            }
            return new Options(
                    Objects.requireNonNull(root, "--root is required"),
                    Objects.requireNonNull(reportDirectory, "--report-dir is required"),
                    Objects.requireNonNull(authoredManifest, "--authored-manifest is required"),
                    Objects.requireNonNull(modifiedInheritedManifest,
                            "--modified-inherited-manifest is required"));
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException(option + " requires a value");
            }
            return args[index];
        }
    }
}
