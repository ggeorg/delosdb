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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

final class DelosRepositoryIntegrityModel {
    private DelosRepositoryIntegrityModel() {
    }

    interface LocatedRecord {
        SourceFile source();
    }

    enum SourceKind {
        PRODUCTION,
        TEST,
        BENCHMARK,
        OTHER;

        static SourceKind of(String relative) {
            if (relative.contains("/src/main/java/")) {
                return PRODUCTION;
            }
            if (relative.contains("/src/test/java/")
                    || relative.contains("/src/test/")) {
                return TEST;
            }
            return relative.startsWith("benchmarks/") ? BENCHMARK : OTHER;
        }
    }

    static final class SourceFile {
        final Path path;
        final String relative;
        final String text;
        final SourceKind kind;
        final boolean generated;
        final int suppressions;
        final int qualityMarkers;

        SourceFile(Path path, String relative, String text, SourceKind kind,
                boolean generated, int suppressions, int qualityMarkers) {
            this.path = path;
            this.relative = relative;
            this.text = text;
            this.kind = kind;
            this.generated = generated;
            this.suppressions = suppressions;
            this.qualityMarkers = qualityMarkers;
        }
    }

    static final class MethodRecord implements LocatedRecord {
        static final Comparator<MethodRecord> ORDER = Comparator
                .comparing((MethodRecord record) -> record.source.relative)
                .thenComparingLong(record -> record.startLine)
                .thenComparing(record -> record.owner)
                .thenComparing(record -> record.name);

        final SourceFile source;
        final String owner;
        final String name;
        final boolean constructor;
        final boolean isPrivate;
        final boolean hasAnnotation;
        final long startLine;
        final long endLine;
        String normalizedBody;
        String bodyHash;
        int branches;
        int loops;
        int catches;
        int statements;
        int maxNesting;

        MethodRecord(SourceFile source, String owner, String name,
                boolean constructor, boolean isPrivate, boolean hasAnnotation,
                long startLine, long endLine) {
            this.source = source;
            this.owner = owner;
            this.name = name;
            this.constructor = constructor;
            this.isPrivate = isPrivate;
            this.hasAnnotation = hasAnnotation;
            this.startLine = startLine;
            this.endLine = endLine;
        }

        @Override
        public SourceFile source() {
            return source;
        }

        int lines() {
            return endLine < startLine ? 0 : (int) (endLine - startLine + 1L);
        }

        int complexity() {
            return branches + loops + catches;
        }
    }

    static final class FieldRecord implements LocatedRecord {
        static final Comparator<FieldRecord> ORDER = Comparator
                .comparing((FieldRecord record) -> record.source.relative)
                .thenComparingLong(record -> record.line)
                .thenComparing(record -> record.owner)
                .thenComparing(record -> record.name);

        final SourceFile source;
        final String owner;
        final String name;
        final long line;
        final boolean isPrivate;
        final boolean hasAnnotation;

        FieldRecord(SourceFile source, String owner, String name, long line,
                boolean isPrivate, boolean hasAnnotation) {
            this.source = source;
            this.owner = owner;
            this.name = name;
            this.line = line;
            this.isPrivate = isPrivate;
            this.hasAnnotation = hasAnnotation;
        }

        @Override
        public SourceFile source() {
            return source;
        }
    }

    static final class ClassRecord implements LocatedRecord {
        static final Comparator<ClassRecord> ORDER = Comparator
                .comparing((ClassRecord record) -> record.source.relative)
                .thenComparingLong(record -> record.startLine)
                .thenComparing(record -> record.name);

        final SourceFile source;
        final String name;
        final long startLine;
        final long endLine;

        ClassRecord(SourceFile source, String name, long startLine, long endLine) {
            this.source = source;
            this.name = name;
            this.startLine = startLine;
            this.endLine = endLine;
        }

        @Override
        public SourceFile source() {
            return source;
        }

        int lines() {
            return endLine < startLine ? 0 : (int) (endLine - startLine + 1L);
        }
    }

    static final class CatchRecord implements LocatedRecord {
        static final Comparator<CatchRecord> ORDER = Comparator
                .comparing((CatchRecord record) -> record.source.relative)
                .thenComparingLong(record -> record.line)
                .thenComparing(record -> record.owner)
                .thenComparing(record -> record.method);

        final SourceFile source;
        final String owner;
        final String method;
        final long line;
        final String type;
        final boolean empty;
        final boolean documented;
        final boolean generic;

        CatchRecord(SourceFile source, String owner, String method, long line,
                String type, boolean empty, boolean documented,
                boolean generic) {
            this.source = source;
            this.owner = owner;
            this.method = method;
            this.line = line;
            this.type = type;
            this.empty = empty;
            this.documented = documented;
            this.generic = generic;
        }

        @Override
        public SourceFile source() {
            return source;
        }
    }

    static final class DuplicateGroup {
        final String hash;
        final List<MethodRecord> methods;
        final int estimatedDuplicateLines;

        DuplicateGroup(String hash, List<MethodRecord> methods,
                int estimatedDuplicateLines) {
            this.hash = hash;
            this.methods = methods;
            this.estimatedDuplicateLines = estimatedDuplicateLines;
        }
    }

    record Summary(
            int javaFiles,
            int productionJavaFiles,
            int testJavaFiles,
            int benchmarkJavaFiles,
            int types,
            int productionTypes,
            int methods,
            int productionMethods,
            int fields,
            int productionFields,
            int parseErrors,
            int deadPrivateMethods,
            int deadPrivateProductionMethods,
            int deadPrivateFields,
            int deadPrivateProductionFields,
            int duplicateProductionMethodGroups,
            int duplicateProductionMethods,
            int estimatedDuplicateProductionLines,
            int productionMethods100Plus,
            int productionMethodsComplexity20Plus,
            int productionClasses1000Plus,
            int catchBlocks,
            int productionCatchBlocks,
            int productionEmptyCatches,
            int productionGenericCatches,
            int suppressWarnings,
            int productionSuppressWarnings,
            int qualityMarkers,
            int productionQualityMarkers,
            int compilerAuthorityViolations,
            int compilerAuthorityCompromiseCandidates) {

        Properties toProperties() {
            Properties p = new Properties();
            put(p, "javaFiles", javaFiles); put(p, "productionJavaFiles", productionJavaFiles);
            put(p, "testJavaFiles", testJavaFiles); put(p, "benchmarkJavaFiles", benchmarkJavaFiles);
            put(p, "types", types); put(p, "productionTypes", productionTypes);
            put(p, "methods", methods); put(p, "productionMethods", productionMethods);
            put(p, "fields", fields); put(p, "productionFields", productionFields);
            put(p, "parseErrors", parseErrors); put(p, "deadPrivateMethods", deadPrivateMethods);
            put(p, "deadPrivateProductionMethods", deadPrivateProductionMethods);
            put(p, "deadPrivateFields", deadPrivateFields);
            put(p, "deadPrivateProductionFields", deadPrivateProductionFields);
            put(p, "duplicateProductionMethodGroups", duplicateProductionMethodGroups);
            put(p, "duplicateProductionMethods", duplicateProductionMethods);
            put(p, "estimatedDuplicateProductionLines", estimatedDuplicateProductionLines);
            put(p, "productionMethods100Plus", productionMethods100Plus);
            put(p, "productionMethodsComplexity20Plus", productionMethodsComplexity20Plus);
            put(p, "productionClasses1000Plus", productionClasses1000Plus);
            put(p, "catchBlocks", catchBlocks); put(p, "productionCatchBlocks", productionCatchBlocks);
            put(p, "productionEmptyCatches", productionEmptyCatches);
            put(p, "productionGenericCatches", productionGenericCatches);
            put(p, "suppressWarnings", suppressWarnings);
            put(p, "productionSuppressWarnings", productionSuppressWarnings);
            put(p, "qualityMarkers", qualityMarkers);
            put(p, "productionQualityMarkers", productionQualityMarkers);
            put(p, "compilerAuthorityViolations", compilerAuthorityViolations);
            put(p, "compilerAuthorityCompromiseCandidates", compilerAuthorityCompromiseCandidates);
            return p;
        }

        private static void put(Properties properties, String key, int value) {
            properties.setProperty(key, Integer.toString(value));
        }
    }

    static final class AuditOptions {
        final Path root;
        final Path reportDirectory;

        private AuditOptions(Path root, Path reportDirectory) {
            this.root = root;
            this.reportDirectory = reportDirectory;
        }

        static AuditOptions parse(String[] args) {
            Path root = null;
            Path report = null;
            for (int i = 0; i < args.length; i++) {
                if (args[i].equals("--root") && i + 1 < args.length) {
                    root = Paths.get(args[++i]);
                } else if (args[i].equals("--report-dir") && i + 1 < args.length) {
                    report = Paths.get(args[++i]);
                } else {
                    throw new IllegalArgumentException(
                            "Unknown or incomplete argument: " + args[i]);
                }
            }
            if (root == null || report == null) {
                throw new IllegalArgumentException(
                        "Usage: DelosRepositoryIntegrityAudit --root <repo> --report-dir <dir>");
            }
            return new AuditOptions(root.toAbsolutePath().normalize(),
                    report.toAbsolutePath().normalize());
        }
    }
}
