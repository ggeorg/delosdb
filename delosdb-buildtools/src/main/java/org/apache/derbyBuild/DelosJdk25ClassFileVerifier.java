/*

   Derby - Class org.apache.derbyBuild.DelosJdk25ClassFileVerifier

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
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

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * JDK-owned class-file verifier for DelosDB runtime outputs.
 *
 * <p>This tool verifies compiled class files through
 * {@link java.lang.classfile.ClassFile}, the same JDK-owned API family used by
 * DelosDB's sole generated-class implementation. It remains an independent
 * artifact verifier rather than a second generation path.</p>
 */
public final class DelosJdk25ClassFileVerifier {

    private DelosJdk25ClassFileVerifier() {
    }

    public static void main(String[] args) throws IOException {
        Options options = Options.parse(args);
        VerificationReport report = verify(options);
        report.write(options.reportFile);
        if (!report.failures.isEmpty()) {
            throw new IllegalStateException("DelosDB JDK 25 class-file verifier failed: " + options.reportFile);
        }
        System.out.println("DelosDB JDK 25 class-file verifier passed. Report: " + options.reportFile);
    }

    private static VerificationReport verify(Options options) throws IOException {
        ClassFile classFile = ClassFile.of();
        VerificationReport report = new VerificationReport(options.expectedMajor, options.roots);

        for (Path root : options.roots) {
            if (!Files.isDirectory(root)) {
                report.failures.add("Class-file root does not exist or is not a directory: " + root);
                continue;
            }
            try (Stream<Path> stream = Files.walk(root)) {
                List<Path> classFiles = stream
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".class"))
                        .sorted(Comparator.comparing(Path::toString))
                        .toList();
                for (Path classFilePath : classFiles) {
                    verifyClassFile(classFile, root, classFilePath, options.expectedMajor, report);
                }
            }
        }

        if (report.totalClasses == 0) {
            report.failures.add("No class files were found in verifier roots");
        }
        return report;
    }

    private static void verifyClassFile(ClassFile classFile,
                                        Path root,
                                        Path classFilePath,
                                        int expectedMajor,
                                        VerificationReport report) {
        report.totalClasses++;
        String relativePath = root.relativize(classFilePath).toString().replace('\\', '/');
        try {
            ClassModel model = classFile.parse(readAllBytes(classFilePath));
            int major = model.majorVersion();
            int minor = model.minorVersion();
            report.parsedClasses++;
            report.rows.add(relativePath + " major=" + major + " minor=" + minor);
            if (major != expectedMajor) {
                report.failures.add(relativePath + " has class-file major " + major
                        + " but expected " + expectedMajor);
            }
        } catch (RuntimeException | IOException ex) {
            report.failures.add(relativePath + " could not be parsed with java.lang.classfile: " + ex);
        }
    }

    private static byte[] readAllBytes(Path path) throws IOException {
        return Files.readAllBytes(path);
    }

    private static final class Options {
        private final int expectedMajor;
        private final Path reportFile;
        private final List<Path> roots;

        private Options(int expectedMajor, Path reportFile, List<Path> roots) {
            this.expectedMajor = expectedMajor;
            this.reportFile = reportFile;
            this.roots = roots;
        }

        private static Options parse(String[] args) {
            int expectedMajor = -1;
            Path reportFile = null;
            List<Path> roots = new ArrayList<>();

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--expected-major" -> expectedMajor = Integer.parseInt(requireValue(args, ++i, arg));
                    case "--report" -> reportFile = Path.of(requireValue(args, ++i, arg));
                    case "--root" -> roots.add(Path.of(requireValue(args, ++i, arg)));
                    default -> throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }

            if (expectedMajor <= 0) {
                throw new IllegalArgumentException("--expected-major is required");
            }
            if (reportFile == null) {
                throw new IllegalArgumentException("--report is required");
            }
            if (roots.isEmpty()) {
                throw new IllegalArgumentException("At least one --root is required");
            }
            return new Options(expectedMajor, reportFile, List.copyOf(roots));
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException(option + " requires a value");
            }
            return args[index];
        }
    }

    private static final class VerificationReport {
        private final int expectedMajor;
        private final List<Path> roots;
        private final List<String> rows = new ArrayList<>();
        private final List<String> failures = new ArrayList<>();
        private int totalClasses;
        private int parsedClasses;

        private VerificationReport(int expectedMajor, List<Path> roots) {
            this.expectedMajor = expectedMajor;
            this.roots = roots;
        }

        private void write(Path reportFile) throws IOException {
            Path parent = reportFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(reportFile))) {
                writer.println("DelosDB JDK 25 Class-File API bytecode verifier");
                writer.println("============================================================");
                writer.println();
                writer.println("Verifier API: java.lang.classfile.ClassFile");
                writer.println("Expected class-file major: " + expectedMajor);
                writer.println("Roots:");
                for (Path root : roots) {
                    writer.println("- " + root);
                }
                writer.println();
                writer.println("Total class files: " + totalClasses);
                writer.println("Parsed class files: " + parsedClasses);
                writer.println();
                writer.println("Class-file rows:");
                for (String row : rows) {
                    writer.println("- " + row);
                }
                writer.println();
                if (failures.isEmpty()) {
                    writer.println("Status: PASS");
                } else {
                    writer.println("Status: FAIL");
                    for (String failure : failures) {
                        writer.println("- " + failure);
                    }
                }
            }
        }
    }
}
