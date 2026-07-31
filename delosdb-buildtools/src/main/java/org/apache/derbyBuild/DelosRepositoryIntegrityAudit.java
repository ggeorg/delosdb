/*

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */
package org.apache.derbyBuild;

import org.apache.derbyBuild.DelosRepositoryIntegrityModel.*;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Repository-wide, dependency-free Java source inventory used by the DelosDB
 * cleanup campaign. The audit parses source with the public javac tree API and
 * reports candidates; it does not delete code or claim that a candidate is
 * unreachable without a later classification review.
 */
public final class DelosRepositoryIntegrityAudit {

    private static final Pattern SUPPRESS_WARNINGS = Pattern.compile(
            "@SuppressWarnings\\s*\\(");
    private static final Pattern QUALITY_MARKER = Pattern.compile(
            "(?i)\\b(TODO|FIXME|HACK|XXX|RESOLVE)\\b");
    private static final Pattern ASM_IMPORT = Pattern.compile(
            "(?m)^\\s*import\\s+(?:jdk\\.internal\\.)?org\\.objectweb\\.asm(?:\\.|;)");
    private static final Pattern ASM_BUILD_DECLARATION = Pattern.compile(
            "org\\.ow2\\.asm|delosdbAsm|asmVersion|requires(?:\\s+static)?\\s+org\\.objectweb\\.asm");
    private static final Pattern CLASSFILE_IMPORT = Pattern.compile(
            "(?m)^\\s*import\\s+java\\.lang\\.classfile(?:\\.|;)" );

    private static final int DUPLICATE_MIN_LINES = 6;
    private static final int DUPLICATE_MIN_STATEMENTS = 4;
    private static final int COMPLEXITY_THRESHOLD = 20;
    private static final int METHOD_LINE_THRESHOLD = 100;
    private static final int CLASS_LINE_THRESHOLD = 1000;

    private final Path root;
    private final Path reportDirectory;
    private final List<SourceFile> sourceFiles = new ArrayList<>();
    private final List<MethodRecord> methods = new ArrayList<>();
    private final List<FieldRecord> fields = new ArrayList<>();
    private final List<ClassRecord> classes = new ArrayList<>();
    private final List<CatchRecord> catches = new ArrayList<>();
    private final List<String> parseErrors = new ArrayList<>();
    private final Map<String, Integer> symbolUses = new TreeMap<>();
    private final List<String> authorityViolations = new ArrayList<>();
    private final List<String> authorityCandidates = new ArrayList<>();

    private DelosRepositoryIntegrityAudit(Path root, Path reportDirectory) {
        this.root = root;
        this.reportDirectory = reportDirectory;
    }

    public static void main(String[] args) throws Exception {
        AuditOptions options = AuditOptions.parse(args);
        DelosRepositoryIntegrityAudit audit = new DelosRepositoryIntegrityAudit(
                options.root, options.reportDirectory);
        audit.run();
    }

    private void run() throws Exception {
        collectSources();
        parseSources();
        auditCompilerAuthority();
        Files.createDirectories(reportDirectory);

        Summary summary = buildSummary();
        writeSummary(summary);
        writeDeadPrivateMethodCandidates();
        writeDeadPrivateFieldCandidates();
        writeDuplicateGroups();
        writeMethodOutliers();
        writeClassOutliers();
        writeCatchInventory();
        writeQualityMarkerInventory();
        writeCompilerAuthorityReport(summary);
        writeHumanReport(summary);

        if (!parseErrors.isEmpty()) {
            throw new IllegalStateException(
                    "Repository Java audit found parse errors; see "
                            + reportDirectory.resolve("parse-errors.txt"));
        }
        if (!authorityViolations.isEmpty()) {
            throw new IllegalStateException(
                    "Repository Java audit found compiler-authority violations; see "
                            + reportDirectory.resolve("compiler-authority-integrity.txt"));
        }
    }

    private void collectSources() throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(Predicate.not(this::isExcludedPath))
                    .sorted(Comparator.comparing(this::relative))
                    .forEach(path -> sourceFiles.add(readSource(path)));
        }
    }

    private boolean isExcludedPath(Path path) {
        Path relative = root.relativize(path);
        for (Path part : relative) {
            String value = part.toString();
            if (value.equals("build") || value.equals(".gradle")
                    || value.equals(".git") || value.equals("out")) {
                return true;
            }
        }
        return false;
    }

    private SourceFile readSource(Path path) {
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            String relative = relative(path);
            SourceKind kind = SourceKind.of(relative);
            boolean generated = isGeneratedSource(relative, text);
            return new SourceFile(path, relative, text, kind, generated,
                    count(SUPPRESS_WARNINGS, text),
                    count(QUALITY_MARKER, text));
        } catch (IOException ioe) {
            throw new IllegalStateException("Could not read " + path, ioe);
        }
    }

    private static boolean isGeneratedSource(String relative, String text) {
        String lower = text.substring(0, Math.min(text.length(), 4096))
                .toLowerCase(Locale.ROOT);
        return relative.contains("/generated/")
                || lower.contains("generated by javacc")
                || lower.contains("this file was generated")
                || lower.contains("do not edit this file")
                || lower.contains("automatically generated");
    }

    private void parseSources() throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException(
                    "A full JDK is required for the repository Java audit");
        }

        DiagnosticCollector<JavaFileObject> diagnostics =
                new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(
                diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units =
                    fileManager.getJavaFileObjectsFromFiles(sourceFiles.stream()
                            .map(source -> source.path.toFile()).toList());
            JavacTask task = (JavacTask) compiler.getTask(
                    null, fileManager, diagnostics,
                    List.of("-proc:none", "-Xlint:none"), null, units);
            Iterable<? extends CompilationUnitTree> parsed = task.parse();
            Trees trees = Trees.instance(task);
            SourcePositions positions = trees.getSourcePositions();
            Map<Path, SourceFile> byPath = new TreeMap<>(
                    Comparator.comparing(Path::toString));
            for (SourceFile source : sourceFiles) {
                byPath.put(source.path.toAbsolutePath().normalize(), source);
            }

            for (CompilationUnitTree unit : parsed) {
                Path unitPath = Paths.get(unit.getSourceFile().toUri())
                        .toAbsolutePath().normalize();
                SourceFile source = byPath.get(unitPath);
                if (source == null) {
                    throw new IllegalStateException(
                            "Parsed source is outside the inventory: " + unitPath);
                }
                scanCompilationUnit(source, unit, positions);
            }
        }

        diagnostics.getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind()
                        == Diagnostic.Kind.ERROR)
                .map(this::formatDiagnostic)
                .sorted()
                .forEach(parseErrors::add);
    }

    private void scanCompilationUnit(SourceFile source,
            CompilationUnitTree unit, SourcePositions positions) {
        new TreePathScanner<Void, Integer>() {
            private final Deque<String> owners = new ArrayDeque<>();

            @Override
            public Void visitClass(ClassTree node, Integer nesting) {
                String simpleName = node.getSimpleName().toString();
                if (simpleName.isEmpty()) {
                    simpleName = "<anonymous>";
                }
                String owner = owners.isEmpty()
                        ? simpleName : owners.peek() + "$" + simpleName;
                owners.push(owner);
                classes.add(new ClassRecord(
                        source, owner,
                        line(unit, positions.getStartPosition(unit, node)),
                        line(unit, positions.getEndPosition(unit, node))));
                node.getMembers().stream().filter(BlockTree.class::isInstance)
                        .map(BlockTree.class::cast).forEach(block ->
                                scanInitializer(source, unit, positions, owner, block));
                super.visitClass(node, increment(nesting));
                owners.pop();
                return null;
            }

            @Override
            public Void visitMethod(MethodTree node, Integer nesting) {
                String name = node.getName().toString();
                Set<Modifier> modifiers = node.getModifiers().getFlags();
                MethodRecord record = new MethodRecord(
                        source,
                        owners.isEmpty() ? "<top>" : owners.peek(),
                        name,
                        name.equals("<init>"),
                        modifiers.contains(Modifier.PRIVATE),
                        !node.getModifiers().getAnnotations().isEmpty(),
                        line(unit, positions.getStartPosition(unit, node)),
                        line(unit, positions.getEndPosition(unit, node)));
                if (node.getBody() != null) {
                    record.normalizedBody = normalize(node.getBody().toString());
                    record.bodyHash = sha256(record.normalizedBody);
                    new DelosRepositoryIntegrityMetrics(
                            source, unit, positions, record, catches)
                            .scan(node.getBody(), 0);
                }
                methods.add(record);
                return super.visitMethod(node, increment(nesting));
            }

            @Override
            public Void visitVariable(VariableTree node, Integer nesting) {
                TreePath path = getCurrentPath();
                if (path.getParentPath() != null
                        && path.getParentPath().getLeaf()
                                instanceof ClassTree ownerTree) {
                    Set<Modifier> modifiers = node.getModifiers().getFlags();
                    // A record component has a compiler-owned private
                    // backing field plus a public accessor. It is part of the
                    // record contract, not an ordinary removable private
                    // field. Records cannot declare other instance fields.
                    boolean recordComponent = ownerTree.getKind()
                            == Tree.Kind.RECORD
                            && !modifiers.contains(Modifier.STATIC);
                    if (!recordComponent) {
                        fields.add(new FieldRecord(
                                source,
                                owners.isEmpty() ? "<top>" : owners.peek(),
                                node.getName().toString(),
                                line(unit,
                                        positions.getStartPosition(unit, node)),
                                modifiers.contains(Modifier.PRIVATE),
                                !node.getModifiers().getAnnotations().isEmpty()));
                    }
                }
                return super.visitVariable(node, nesting);
            }

            @Override
            public Void visitIdentifier(IdentifierTree node, Integer nesting) {
                addUse(node.getName().toString());
                return super.visitIdentifier(node, nesting);
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree node,
                    Integer nesting) {
                addUse(node.getIdentifier().toString());
                return super.visitMemberSelect(node, nesting);
            }

            @Override
            public Void visitMemberReference(MemberReferenceTree node,
                    Integer nesting) {
                addUse(node.getName().toString());
                return super.visitMemberReference(node, nesting);
            }
        }.scan(unit, 0);
    }

    private void scanInitializer(SourceFile source,
            CompilationUnitTree unit, SourcePositions positions,
            String owner, BlockTree block) {
        MethodRecord initializer = new MethodRecord(
                source,
                owner,
                block.isStatic()
                        ? "<static-initializer>"
                        : "<instance-initializer>",
                false,
                false,
                false,
                line(unit, positions.getStartPosition(unit, block)),
                line(unit, positions.getEndPosition(unit, block)));
        new DelosRepositoryIntegrityMetrics(
                source, unit, positions, initializer, catches)
                .scan(block, 0);
    }

    private void addUse(String name) {
        symbolUses.merge(name, 1, Integer::sum);
    }

    private void auditCompilerAuthority() throws IOException {
        List<SourceFile> production = sourceFiles.stream()
                .filter(source -> source.kind == SourceKind.PRODUCTION)
                .toList();
        Set<String> allowedClassFileSources = Set.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/services/bytecode/classfile/ClassFileJava.java",
                "delosdb-buildtools/src/main/java/org/apache/derbyBuild/DelosJdk25ClassFileVerifier.java");

        for (SourceFile source : production) {
            if (ASM_IMPORT.matcher(source.text).find()) {
                authorityViolations.add(
                        "External ASM import in production source: "
                                + source.relative);
            }
            if (CLASSFILE_IMPORT.matcher(source.text).find()
                    && !allowedClassFileSources.contains(source.relative)) {
                authorityViolations.add(
                        "Direct Class-File API import outside the backend/verifier: "
                                + source.relative);
            }
            if (source.relative.startsWith(
                    "delosdb-engine/src/main/java/org/apache/derby/impl/sql/compile/")
                    && source.text.contains("java.lang.classfile")) {
                authorityViolations.add(
                        "SQL compiler node depends directly on java.lang.classfile: "
                                + source.relative);
            }
        }

        for (SourceFile source : sourceFiles) {
            if (source.kind != SourceKind.PRODUCTION
                    && ASM_IMPORT.matcher(source.text).find()) {
                authorityViolations.add(
                        "External ASM import in non-production source: "
                                + source.relative);
            }
        }

        for (Path path : activeBuildAndModuleFiles()) {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            if (ASM_BUILD_DECLARATION.matcher(text).find()) {
                authorityViolations.add(
                        "External ASM dependency or module declaration remains: "
                                + relative(path));
            }
        }

        Path modules = root.resolve(
                "delosdb-engine/src/main/java/org/apache/derby/modules.properties");
        List<String> registrations = Files.readAllLines(
                        modules, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> line.startsWith(
                        "derby.module.javaCompiler="))
                .toList();
        String expected = "derby.module.javaCompiler="
                + "org.apache.derby.impl.services.bytecode.classfile.ClassFileJava";
        if (!registrations.equals(List.of(expected))) {
            authorityViolations.add(
                    "Generated-class registration must be exactly: " + expected
                            + "; found " + registrations);
        }

        Path monitor = root.resolve(
                "delosdb-engine/src/main/java/org/apache/derby/impl/services/monitor/BaseMonitor.java");
        String monitorText = Files.readString(monitor, StandardCharsets.UTF_8);
        List<String> authorityPinMarkers = List.of(
                "!actualModuleList",
                "JavaFactory.class.isAssignableFrom(possibleModule)",
                "Ignored external JavaFactory module",
                "packaged ClassFileJava backend");
        for (String marker : authorityPinMarkers) {
            if (!monitorText.contains(marker)) {
                authorityViolations.add(
                        "BaseMonitor is missing generated-class authority pin: "
                                + marker);
            }
        }
    }

    private List<Path> activeBuildAndModuleFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(Predicate.not(this::isExcludedPath))
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.equals("build.gradle")
                                || name.equals("settings.gradle")
                                || name.equals("module-info.java");
                    })
                    .sorted(Comparator.comparing(this::relative))
                    .forEach(files::add);
        }
        return files;
    }

    private Summary buildSummary() {
        List<MethodRecord> deadMethods = deadPrivateMethods();
        List<FieldRecord> deadFields = deadPrivateFields();
        List<DuplicateGroup> duplicates = duplicateGroups();

        int duplicateMethods = duplicates.stream()
                .mapToInt(group -> group.methods.size()).sum();
        int duplicateLines = duplicates.stream()
                .mapToInt(group -> group.estimatedDuplicateLines).sum();

        return new Summary(
                sourceFiles.size(), countSources(SourceKind.PRODUCTION),
                countSources(SourceKind.TEST), countSources(SourceKind.BENCHMARK),
                classes.size(), countProduction(classes),
                methods.size(), countProduction(methods),
                fields.size(), countProduction(fields),
                parseErrors.size(),
                deadMethods.size(), countProduction(deadMethods),
                deadFields.size(), countProduction(deadFields),
                duplicates.size(), duplicateMethods, duplicateLines,
                countProductionMethods(record -> record.lines()
                        >= METHOD_LINE_THRESHOLD),
                countProductionMethods(record -> record.complexity()
                        >= COMPLEXITY_THRESHOLD),
                countProductionClasses(record -> record.lines()
                        >= CLASS_LINE_THRESHOLD),
                catches.size(), countProduction(catches),
                countProduction(catches.stream()
                        .filter(record -> record.empty && !record.documented)
                        .toList()),
                countProduction(catches.stream()
                        .filter(record -> record.generic).toList()),
                sourceFiles.stream().mapToInt(source -> source.suppressions).sum(),
                sourceFiles.stream().filter(source -> source.kind
                        == SourceKind.PRODUCTION)
                        .mapToInt(source -> source.suppressions).sum(),
                sourceFiles.stream().mapToInt(source -> source.qualityMarkers).sum(),
                sourceFiles.stream().filter(source -> source.kind
                        == SourceKind.PRODUCTION)
                        .mapToInt(source -> source.qualityMarkers).sum(),
                authorityViolations.size(), authorityCandidates.size());
    }

    private List<MethodRecord> deadPrivateMethods() {
        return methods.stream()
                .filter(record -> record.isPrivate)
                .filter(record -> !record.constructor)
                .filter(record -> record.normalizedBody != null)
                .filter(record -> !record.hasAnnotation)
                .filter(record -> !record.source.generated)
                .filter(record -> !isSerializationHook(record.name))
                .filter(record -> symbolUses.getOrDefault(record.name, 0) == 0)
                .sorted(MethodRecord.ORDER)
                .toList();
    }

    private List<FieldRecord> deadPrivateFields() {
        return fields.stream()
                .filter(record -> record.isPrivate)
                .filter(record -> !record.hasAnnotation)
                .filter(record -> !record.source.generated)
                .filter(record -> symbolUses.getOrDefault(record.name, 0) == 0)
                .sorted(FieldRecord.ORDER)
                .toList();
    }

    private static boolean isSerializationHook(String name) {
        return Set.of("readObject", "writeObject", "readResolve",
                "writeReplace", "readObjectNoData", "finalize")
                .contains(name);
    }

    private List<DuplicateGroup> duplicateGroups() {
        Map<String, List<MethodRecord>> byHash = new TreeMap<>();
        methods.stream()
                .filter(record -> record.source.kind == SourceKind.PRODUCTION)
                .filter(record -> !record.source.generated)
                .filter(record -> !record.constructor)
                .filter(record -> record.bodyHash != null)
                .filter(record -> record.lines() >= DUPLICATE_MIN_LINES)
                .filter(record -> record.statements >= DUPLICATE_MIN_STATEMENTS)
                .filter(record -> record.normalizedBody.length() >= 80)
                .filter(record -> !isTrivialAccessorName(record.name))
                .forEach(record -> byHash.computeIfAbsent(
                        record.bodyHash, ignored -> new ArrayList<>()).add(record));

        List<DuplicateGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<MethodRecord>> entry : byHash.entrySet()) {
            List<MethodRecord> groupMethods = entry.getValue().stream()
                    .sorted(MethodRecord.ORDER).toList();
            if (groupMethods.size() < 2) {
                continue;
            }
            long distinctOwners = groupMethods.stream()
                    .map(record -> record.source.relative + "#" + record.owner)
                    .distinct().count();
            if (distinctOwners < 2) {
                continue;
            }
            int representativeLines = groupMethods.stream()
                    .mapToInt(MethodRecord::lines).max().orElse(0);
            groups.add(new DuplicateGroup(entry.getKey(), groupMethods,
                    representativeLines * (groupMethods.size() - 1)));
        }
        groups.sort(Comparator
                .comparingInt((DuplicateGroup group) ->
                        group.estimatedDuplicateLines).reversed()
                .thenComparing(group -> group.hash));
        return groups;
    }

    private static boolean isTrivialAccessorName(String name) {
        return name.startsWith("get") || name.startsWith("set")
                || name.startsWith("is") || name.startsWith("with");
    }

    private void writeSummary(Summary summary) throws IOException {
        Properties properties = summary.toProperties();
        try (PrintWriter writer = writer("summary.properties")) {
            properties.stringPropertyNames().stream().sorted()
                    .forEach(name -> writer.println(
                            name + "=" + properties.getProperty(name)));
        }
        try (PrintWriter writer = writer("parse-errors.txt")) {
            parseErrors.forEach(writer::println);
        }
    }

    private void writeDeadPrivateMethodCandidates() throws IOException {
        try (PrintWriter writer = writer(
                "dead-private-method-candidates.tsv")) {
            writer.println("confidence\tsourceKind\tfile\towner\tmethod\tline\tlines\treason");
            for (MethodRecord record : deadPrivateMethods()) {
                writer.printf(Locale.ROOT,
                        "HIGH\t%s\t%s\t%s\t%s\t%d\t%d\t%s%n",
                        record.source.kind, record.source.relative,
                        record.owner, record.name, record.startLine,
                        record.lines(),
                        "private method has no source-level identifier or method-reference use");
            }
        }
    }

    private void writeDeadPrivateFieldCandidates() throws IOException {
        try (PrintWriter writer = writer(
                "dead-private-field-candidates.tsv")) {
            writer.println("confidence\tsourceKind\tfile\towner\tfield\tline\treason");
            for (FieldRecord record : deadPrivateFields()) {
                writer.printf(Locale.ROOT,
                        "HIGH\t%s\t%s\t%s\t%s\t%d\t%s%n",
                        record.source.kind, record.source.relative,
                        record.owner, record.name, record.line,
                        "private field has no source-level identifier or member-selection use");
            }
        }
    }

    private void writeDuplicateGroups() throws IOException {
        try (PrintWriter writer = writer(
                "duplicate-production-method-groups.tsv")) {
            writer.println("hash\tmethodCount\testimatedDuplicateLines\tlocations");
            for (DuplicateGroup group : duplicateGroups()) {
                String locations = group.methods.stream()
                        .map(record -> record.source.relative + ":"
                                + record.startLine + "#" + record.owner
                                + "." + record.name)
                        .reduce((left, right) -> left + ";" + right)
                        .orElse("");
                writer.printf(Locale.ROOT, "%s\t%d\t%d\t%s%n",
                        group.hash, group.methods.size(),
                        group.estimatedDuplicateLines, locations);
            }
        }
    }

    private void writeMethodOutliers() throws IOException {
        try (PrintWriter writer = writer(
                "production-method-outliers.tsv")) {
            writer.println("file\towner\tmethod\tline\tlines\tcomplexity\tstatements\tnesting");
            methods.stream()
                    .filter(record -> record.source.kind
                            == SourceKind.PRODUCTION)
                    .filter(record -> !record.source.generated)
                    .filter(record -> record.lines() >= METHOD_LINE_THRESHOLD
                            || record.complexity() >= COMPLEXITY_THRESHOLD)
                    .sorted(Comparator
                            .comparingInt(MethodRecord::complexity).reversed()
                            .thenComparing(Comparator.comparingInt(
                                    MethodRecord::lines).reversed())
                            .thenComparing(MethodRecord.ORDER))
                    .forEach(record -> writer.printf(Locale.ROOT,
                            "%s\t%s\t%s\t%d\t%d\t%d\t%d\t%d%n",
                            record.source.relative, record.owner, record.name,
                            record.startLine, record.lines(),
                            record.complexity(), record.statements,
                            record.maxNesting));
        }
    }

    private void writeClassOutliers() throws IOException {
        try (PrintWriter writer = writer(
                "production-class-size-outliers.tsv")) {
            writer.println("file\tclass\tline\tlines");
            classes.stream()
                    .filter(record -> record.source.kind
                            == SourceKind.PRODUCTION)
                    .filter(record -> !record.source.generated)
                    .filter(record -> record.lines() >= CLASS_LINE_THRESHOLD)
                    .sorted(Comparator.comparingInt(ClassRecord::lines)
                            .reversed().thenComparing(ClassRecord.ORDER))
                    .forEach(record -> writer.printf(Locale.ROOT,
                            "%s\t%s\t%d\t%d%n",
                            record.source.relative, record.name,
                            record.startLine, record.lines()));
        }
    }

    private void writeCatchInventory() throws IOException {
        try (PrintWriter writer = writer("catch-inventory.tsv")) {
            writer.println("sourceKind\tfile\towner\tmethod\tline\ttype\tempty\tdocumented\tgeneric");
            catches.stream().sorted(CatchRecord.ORDER)
                    .forEach(record -> writer.printf(Locale.ROOT,
                            "%s\t%s\t%s\t%s\t%d\t%s\t%b\t%b\t%b%n",
                            record.source.kind, record.source.relative,
                            record.owner, record.method, record.line,
                            record.type, record.empty, record.documented,
                            record.generic));
        }
    }

    private void writeQualityMarkerInventory() throws IOException {
        try (PrintWriter writer = writer(
                "quality-marker-inventory.tsv")) {
            writer.println("sourceKind\tfile\tsuppressWarnings\tqualityMarkers\tgenerated");
            sourceFiles.stream()
                    .filter(source -> source.suppressions > 0
                            || source.qualityMarkers > 0)
                    .sorted(Comparator.comparing(source -> source.relative))
                    .forEach(source -> writer.printf(Locale.ROOT,
                            "%s\t%s\t%d\t%d\t%b%n",
                            source.kind, source.relative,
                            source.suppressions, source.qualityMarkers,
                            source.generated));
        }
    }

    private void writeCompilerAuthorityReport(Summary summary)
            throws IOException {
        try (PrintWriter writer = writer(
                "compiler-authority-integrity.txt")) {
            writer.println("DelosDB generated-class authority integrity");
            writer.println("=========================================");
            writer.println("Fixed registration: ClassFileJava");
            writer.println("External ASM source/build/module references: none");
            writer.println("Direct java.lang.classfile production users: 2");
            writer.println("  - ClassFileJava");
            writer.println("  - DelosJdk25ClassFileVerifier");
            writer.println("External JavaFactory override filtering: enforced");
            writer.println("Authority violations: "
                    + summary.compilerAuthorityViolations());
            authorityViolations.forEach(value -> writer.println("VIOLATION|" + value));
            writer.println("Compromise candidates: "
                    + summary.compilerAuthorityCompromiseCandidates());
            authorityCandidates.forEach(writer::println);
            writer.println(summary.compilerAuthorityViolations() == 0
                    && summary.compilerAuthorityCompromiseCandidates() == 0
                    ? "Status: PASS" : "Status: FAIL");
        }
    }

    private void writeHumanReport(Summary summary) throws IOException {
        try (PrintWriter writer = writer("repository-integrity-report.txt")) {
            writer.println("DelosDB repository integrity inventory");
            writer.println("=====================================");
            writer.println("Java files: " + summary.javaFiles());
            writer.println("Production Java files: "
                    + summary.productionJavaFiles());
            writer.println("Types: " + summary.types());
            writer.println("Methods: " + summary.methods());
            writer.println("Fields: " + summary.fields());
            writer.println("Parse errors: " + summary.parseErrors());
            writer.println();
            writer.println("Candidate debt (classification required)");
            writer.println("----------------------------------------");
            writer.println("Dead private production methods: "
                    + summary.deadPrivateProductionMethods());
            writer.println("Dead private production fields: "
                    + summary.deadPrivateProductionFields());
            writer.println("Exact production duplicate groups: "
                    + summary.duplicateProductionMethodGroups());
            writer.println("Estimated duplicated production lines: "
                    + summary.estimatedDuplicateProductionLines());
            writer.println("Production methods >= " + METHOD_LINE_THRESHOLD
                    + " lines: " + summary.productionMethods100Plus());
            writer.println("Production methods complexity >= "
                    + COMPLEXITY_THRESHOLD + ": "
                    + summary.productionMethodsComplexity20Plus());
            writer.println("Production classes >= " + CLASS_LINE_THRESHOLD
                    + " lines: " + summary.productionClasses1000Plus());
            writer.println("Production silent empty catches: "
                    + summary.productionEmptyCatches());
            writer.println("Production generic catches: "
                    + summary.productionGenericCatches());
            writer.println("Production @SuppressWarnings occurrences: "
                    + summary.productionSuppressWarnings());
            writer.println("Production TODO/FIXME/HACK/XXX/RESOLVE markers: "
                    + summary.productionQualityMarkers());
            writer.println();
            writer.println("Compiler integrity");
            writer.println("------------------");
            writer.println("Authority violations: "
                    + summary.compilerAuthorityViolations());
            writer.println("Compromise candidates: "
                    + summary.compilerAuthorityCompromiseCandidates());
            writer.println();
            writer.println("Interpretation: candidate counts are an evidence baseline, not proof that every candidate is dead, duplicated without reason, or unsafe. Later cleanup stages must classify each finding before changing code.");
        }
    }

    private PrintWriter writer(String name) throws IOException {
        return new PrintWriter(Files.newBufferedWriter(
                reportDirectory.resolve(name), StandardCharsets.UTF_8));
    }

    private int countSources(SourceKind kind) {
        return (int) sourceFiles.stream()
                .filter(source -> source.kind == kind).count();
    }

    private static int countProduction(Collection<? extends LocatedRecord> records) {
        return (int) records.stream()
                .filter(record -> record.source().kind
                        == SourceKind.PRODUCTION).count();
    }

    private int countProductionMethods(Predicate<MethodRecord> predicate) {
        return (int) methods.stream()
                .filter(record -> record.source.kind
                        == SourceKind.PRODUCTION)
                .filter(record -> !record.source.generated)
                .filter(predicate).count();
    }

    private int countProductionClasses(Predicate<ClassRecord> predicate) {
        return (int) classes.stream()
                .filter(record -> record.source.kind
                        == SourceKind.PRODUCTION)
                .filter(record -> !record.source.generated)
                .filter(predicate).count();
    }

    private String formatDiagnostic(Diagnostic<? extends JavaFileObject> diagnostic) {
        String source = diagnostic.getSource() == null
                ? "<none>" : diagnostic.getSource().getName();
        return source + ":" + diagnostic.getLineNumber() + ":"
                + diagnostic.getMessage(Locale.ROOT);
    }

    private String relative(Path path) {
        return root.relativize(path.toAbsolutePath().normalize())
                .toString().replace(File.separatorChar, '/');
    }

    private static long line(CompilationUnitTree unit, long position) {
        return position < 0L ? 0L : unit.getLineMap().getLineNumber(position);
    }

    private static int increment(Integer value) {
        return value == null ? 1 : value + 1;
    }

    private static boolean hasAnnotation(
            List<? extends AnnotationTree> annotations, String simpleName) {
        return annotations.stream().map(annotation ->
                        annotation.getAnnotationType().toString())
                .anyMatch(name -> name.equals(simpleName)
                        || name.endsWith("." + simpleName));
    }

    private static String normalize(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static int count(Pattern pattern, String text) {
        int count = 0;
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

}
