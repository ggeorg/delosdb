/*

   Derby - Class org.apache.derby.iapi.store.types.DelosDatabaseCommitDecision

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
package org.apache.derby.iapi.store.types;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Durable raw-store decision marker shared by heap and external storage.
 *
 * <p>The marker is created and undone by Derby raw-store log operations. Its
 * presence after raw-store recovery is therefore authoritative evidence that
 * the enclosing Derby transaction committed. External participants may safely
 * repeat publication from that decision.</p>
 */
public record DelosDatabaseCommitDecision(long transactionId, long commitSequence) {
    public static final String DIRECTORY = "delos_mvcc/inherited-store/database-decisions";
    private static final String PREFIX = "commit-";
    private static final String SUFFIX = ".decision";
    private static final String VERSION = "1";

    public DelosDatabaseCommitDecision {
        if (transactionId <= 0L) {
            throw new IllegalArgumentException("transactionId must be positive");
        }
        if (commitSequence <= 0L) {
            throw new IllegalArgumentException("commitSequence must be positive");
        }
    }

    public String relativePath() {
        return DIRECTORY + "/" + fileName(transactionId, commitSequence);
    }

    public byte[] encoded() {
        return (VERSION + "\t" + transactionId + "\t" + commitSequence + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    public static Path directory(Path databaseDirectory) {
        return Objects.requireNonNull(databaseDirectory, "databaseDirectory")
                .toAbsolutePath()
                .normalize()
                .resolve(DIRECTORY);
    }

    public static Path markerFile(Path databaseDirectory, long transactionId, long commitSequence) {
        return directory(databaseDirectory).resolve(fileName(transactionId, commitSequence));
    }

    public static Map<Long, DelosDatabaseCommitDecision> recoverCommitted(Path databaseDirectory) {
        if (databaseDirectory == null) {
            return Map.of();
        }
        Path directory = directory(databaseDirectory);
        if (!Files.isDirectory(directory)) {
            return Map.of();
        }

        Map<Long, DelosDatabaseCommitDecision> decisions = new LinkedHashMap<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, PREFIX + "*" + SUFFIX)) {
            for (Path file : files) {
                DelosDatabaseCommitDecision decision = decode(Files.readAllBytes(file), file);
                DelosDatabaseCommitDecision existing = decisions.putIfAbsent(
                        decision.transactionId(), decision);
                if (existing != null && !existing.equals(decision)) {
                    throw new IllegalStateException(
                            "Conflicting database commit decisions for transaction "
                                    + decision.transactionId());
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Unable to recover DelosDB database commit decisions from " + directory,
                    failure);
        }
        return Map.copyOf(decisions);
    }

    public static DelosDatabaseCommitDecision decode(byte[] bytes, Path source) {
        String line = new String(Objects.requireNonNull(bytes, "bytes"), StandardCharsets.UTF_8);
        String[] fields = line.strip().split("\\t", -1);
        if (fields.length != 3 || !VERSION.equals(fields[0])) {
            throw corrupt(source, "invalid decision record");
        }
        try {
            return new DelosDatabaseCommitDecision(
                    Long.parseLong(fields[1]),
                    Long.parseLong(fields[2]));
        } catch (NumberFormatException failure) {
            throw corrupt(source, "invalid numeric decision field", failure);
        }
    }

    private static String fileName(long transactionId, long commitSequence) {
        return PREFIX + transactionId + "-" + commitSequence + SUFFIX;
    }

    private static IllegalStateException corrupt(Path source, String message) {
        return new IllegalStateException("Corrupt DelosDB database decision " + source + ": " + message);
    }

    private static IllegalStateException corrupt(Path source, String message, Throwable cause) {
        return new IllegalStateException(
                "Corrupt DelosDB database decision " + source + ": " + message,
                cause);
    }
}
