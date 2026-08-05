/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.isolation.DelosIsolationSpecificationLoader

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

package org.apache.derbyTesting.functionTests.tests.delos.isolation;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

/** Loads and validates the DelosDB-owned JSON isolation-specification format. */
public final class DelosIsolationSpecificationLoader {
    private DelosIsolationSpecificationLoader() {
    }

    public static DelosIsolationSpecification load(String resource) throws IOException {
        try (InputStream stream = DelosIsolationSpecificationLoader.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("Isolation specification resource is missing: " + resource);
            }
            Object parsed = new JSONParser().parse(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            if (!(parsed instanceof JSONObject root)) {
                throw new IOException("Isolation specification root must be a JSON object: " + resource);
            }
            return parseSpecification(resource, root);
        } catch (ParseException e) {
            throw new IOException("Invalid isolation specification JSON: " + resource, e);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid isolation specification value in " + resource + ": "
                    + e.getMessage(), e);
        }
    }

    private static DelosIsolationSpecification parseSpecification(
            String resource,
            JSONObject root) throws IOException {
        String id = requiredString(root, "id", resource);
        String description = requiredString(root, "description", resource);
        String category = requiredString(root, "category", resource);
        Set<DelosIsolationSpecification.Provider> providers = parseProviders(
                requiredArray(root, "providers", resource), resource);
        Set<DelosIsolationSpecification.Storage> storages = parseStorages(
                requiredArray(root, "storages", resource), resource);
        Set<DelosIsolationSpecification.ConnectionMode> connections = parseConnections(
                requiredArray(root, "connections", resource), resource);

        Map<String, DelosIsolationSpecification.Session> sessions = new LinkedHashMap<>();
        JSONObject sessionsJson = requiredObject(root, "sessions", resource);
        for (Object key : sessionsJson.keySet()) {
            String name = stringValue(key, "session name", resource);
            DelosIsolationSpecification.Session previous = sessions.put(
                    name,
                    parseSession(name, objectValue(sessionsJson.get(key), "session " + name, resource), resource));
            if (previous != null) {
                throw new IOException("Duplicate session " + name + " in " + resource);
            }
        }
        if (sessions.isEmpty()) {
            throw new IOException("Isolation specification has no sessions: " + resource);
        }

        List<DelosIsolationSpecification.Permutation> permutations = new ArrayList<>();
        for (Object value : requiredArray(root, "permutations", resource)) {
            permutations.add(parsePermutation(objectValue(value, "permutation", resource), resource));
        }
        if (permutations.isEmpty()) {
            throw new IOException("Isolation specification has no permutations: " + resource);
        }

        List<DelosIsolationSpecification.QueryAssertion> finalAssertions = new ArrayList<>();
        if (root.containsKey("finalAssertions")) {
            for (Object value : arrayValue(root.get("finalAssertions"), "finalAssertions", resource)) {
                JSONObject assertion = objectValue(value, "final assertion", resource);
                Set<DelosIsolationSpecification.Provider> assertionProviders =
                        assertion.containsKey("providers")
                                ? parseProviders(arrayValue(assertion.get("providers"),
                                        "final assertion providers", resource), resource)
                                : Set.of();
                Set<DelosIsolationSpecification.Storage> assertionStorages =
                        assertion.containsKey("storages")
                                ? parseStorages(arrayValue(assertion.get("storages"),
                                        "final assertion storages", resource), resource)
                                : Set.of();
                finalAssertions.add(new DelosIsolationSpecification.QueryAssertion(
                        requiredString(assertion, "sql", resource),
                        stringList(assertion.get("rows"), "final assertion rows", resource),
                        immutableSet(assertionProviders),
                        immutableSet(assertionStorages)));
            }
        }

        return new DelosIsolationSpecification(
                id,
                description,
                category,
                immutableSet(providers),
                immutableSet(storages),
                immutableSet(connections),
                List.copyOf(stringList(root.get("setup"), "setup", resource)),
                Collections.unmodifiableMap(sessions),
                List.copyOf(permutations),
                List.copyOf(finalAssertions),
                List.copyOf(stringList(root.get("teardown"), "teardown", resource)));
    }

    private static DelosIsolationSpecification.Session parseSession(
            String name,
            JSONObject json,
            String resource) throws IOException {
        int defaultIsolation = isolationLevel(optionalString(json, "isolation", "READ_COMMITTED"));
        Map<DelosIsolationSpecification.Provider, Integer> isolationByProvider =
                new EnumMap<>(DelosIsolationSpecification.Provider.class);
        if (json.containsKey("isolationByProvider")) {
            JSONObject overrides = objectValue(
                    json.get("isolationByProvider"), "isolationByProvider", resource);
            for (Object key : overrides.keySet()) {
                DelosIsolationSpecification.Provider provider =
                        DelosIsolationSpecification.Provider.parse(
                                stringValue(key, "isolation provider", resource));
                isolationByProvider.put(provider, isolationLevel(stringValue(
                        overrides.get(key), "isolation value", resource)));
            }
        }

        Map<String, DelosIsolationSpecification.Step> steps = new LinkedHashMap<>();
        JSONObject stepsJson = requiredObject(json, "steps", resource);
        for (Object key : stepsJson.keySet()) {
            String stepName = stringValue(key, "step name", resource);
            DelosIsolationSpecification.Step previous = steps.put(
                    stepName,
                    parseStep(stepName,
                            objectValue(stepsJson.get(key), "step " + stepName, resource),
                            resource));
            if (previous != null) {
                throw new IOException("Duplicate step " + name + '.' + stepName + " in " + resource);
            }
        }
        if (steps.isEmpty()) {
            throw new IOException("Session " + name + " has no steps in " + resource);
        }
        return new DelosIsolationSpecification.Session(
                name,
                Collections.unmodifiableMap(isolationByProvider),
                defaultIsolation,
                Collections.unmodifiableMap(steps));
    }

    private static DelosIsolationSpecification.Step parseStep(
            String name,
            JSONObject json,
            String resource) throws IOException {
        DelosIsolationSpecification.Action action = DelosIsolationSpecification.Action.parse(
                optionalString(json, "action", "SQL"));
        String sql = optionalString(json, "sql", null);
        String savepoint = optionalString(json, "savepoint", null);
        boolean rowsDeclared = json.containsKey("rows");
        List<String> expectedRows = stringList(json.get("rows"), "rows", resource);
        Integer updateCount = json.containsKey("updateCount")
                ? integerValue(json.get("updateCount"), "updateCount", resource)
                : null;
        Set<String> sqlStates = immutableSet(new LinkedHashSet<>(
                stringList(json.get("sqlStates"), "sqlStates", resource)));
        boolean successAllowed = booleanValue(
                json.get("successAllowed"), sqlStates.isEmpty(), "successAllowed", resource);

        if (action == DelosIsolationSpecification.Action.SQL && (sql == null || sql.isBlank())) {
            throw new IOException("SQL step " + name + " has no SQL text in " + resource);
        }
        if ((action == DelosIsolationSpecification.Action.SAVEPOINT
                || action == DelosIsolationSpecification.Action.ROLLBACK_TO_SAVEPOINT
                || action == DelosIsolationSpecification.Action.RELEASE_SAVEPOINT)
                && (savepoint == null || savepoint.isBlank())) {
            throw new IOException("Savepoint action " + name + " has no savepoint name in " + resource);
        }
        if (!successAllowed && sqlStates.isEmpty()) {
            throw new IOException("Step " + name + " rejects success but declares no SQLState in " + resource);
        }
        return new DelosIsolationSpecification.Step(
                name,
                action,
                sql,
                savepoint,
                List.copyOf(expectedRows),
                rowsDeclared,
                updateCount,
                sqlStates,
                successAllowed);
    }

    private static DelosIsolationSpecification.Permutation parsePermutation(
            JSONObject json,
            String resource) throws IOException {
        String name = requiredString(json, "name", resource);
        Set<DelosIsolationSpecification.Provider> providers = json.containsKey("providers")
                ? parseProviders(arrayValue(json.get("providers"), "permutation providers", resource), resource)
                : Set.of();
        Set<DelosIsolationSpecification.Storage> storages = json.containsKey("storages")
                ? parseStorages(arrayValue(json.get("storages"), "permutation storages", resource), resource)
                : Set.of();

        List<DelosIsolationSpecification.Operation> operations = new ArrayList<>();
        for (Object value : requiredArray(json, "operations", resource)) {
            JSONObject operation = objectValue(value, "operation", resource);
            DelosIsolationSpecification.OperationType type =
                    DelosIsolationSpecification.OperationType.parse(
                            requiredString(operation, "type", resource));
            String step = optionalString(operation, "step", null);
            String token = optionalString(operation, "token", null);
            long timeoutMillis = longValue(
                    operation.get("timeoutMillis"), 5000L, "timeoutMillis", resource);
            if ((type == DelosIsolationSpecification.OperationType.RUN
                    || type == DelosIsolationSpecification.OperationType.START)
                    && (step == null || step.isBlank())) {
                throw new IOException(type + " operation has no step in permutation " + name);
            }
            if ((type == DelosIsolationSpecification.OperationType.START
                    || type == DelosIsolationSpecification.OperationType.ASSERT_BLOCKED
                    || type == DelosIsolationSpecification.OperationType.AWAIT)
                    && (token == null || token.isBlank())) {
                throw new IOException(type + " operation has no token in permutation " + name);
            }
            operations.add(new DelosIsolationSpecification.Operation(type, step, token, timeoutMillis));
        }
        if (operations.isEmpty()) {
            throw new IOException("Permutation " + name + " has no operations in " + resource);
        }

        List<DelosIsolationSpecification.SqlStateAssertion> sqlStateAssertions = new ArrayList<>();
        if (json.containsKey("sqlStateAssertions")) {
            for (Object value : arrayValue(
                    json.get("sqlStateAssertions"), "sqlStateAssertions", resource)) {
                JSONObject assertion = objectValue(value, "SQLState assertion", resource);
                String sqlState = requiredString(assertion, "sqlState", resource);
                int minimum = integerValue(assertion.get("minimum"), "minimum", resource);
                int maximum = assertion.containsKey("maximum")
                        ? integerValue(assertion.get("maximum"), "maximum", resource)
                        : minimum;
                if (minimum < 0 || maximum < minimum) {
                    throw new IOException("Invalid SQLState count range for " + sqlState
                            + " in permutation " + name);
                }
                sqlStateAssertions.add(new DelosIsolationSpecification.SqlStateAssertion(
                        sqlState, minimum, maximum));
            }
        }
        return new DelosIsolationSpecification.Permutation(
                name,
                immutableSet(providers),
                immutableSet(storages),
                List.copyOf(operations),
                List.copyOf(sqlStateAssertions));
    }

    private static Set<DelosIsolationSpecification.Provider> parseProviders(
            JSONArray values,
            String resource) throws IOException {
        Set<DelosIsolationSpecification.Provider> result = new LinkedHashSet<>();
        for (Object value : values) {
            result.add(DelosIsolationSpecification.Provider.parse(
                    stringValue(value, "provider", resource)));
        }
        if (result.isEmpty()) {
            throw new IOException("Provider set must not be empty in " + resource);
        }
        return result;
    }

    private static Set<DelosIsolationSpecification.Storage> parseStorages(
            JSONArray values,
            String resource) throws IOException {
        Set<DelosIsolationSpecification.Storage> result = new LinkedHashSet<>();
        for (Object value : values) {
            result.add(DelosIsolationSpecification.Storage.parse(
                    stringValue(value, "storage", resource)));
        }
        if (result.isEmpty()) {
            throw new IOException("Storage set must not be empty in " + resource);
        }
        return result;
    }

    private static Set<DelosIsolationSpecification.ConnectionMode> parseConnections(
            JSONArray values,
            String resource) throws IOException {
        Set<DelosIsolationSpecification.ConnectionMode> result = new LinkedHashSet<>();
        for (Object value : values) {
            result.add(DelosIsolationSpecification.ConnectionMode.parse(
                    stringValue(value, "connection mode", resource)));
        }
        if (result.isEmpty()) {
            throw new IOException("Connection-mode set must not be empty in " + resource);
        }
        return result;
    }

    private static int isolationLevel(String value) throws IOException {
        return switch (value.trim().toUpperCase()) {
            case "READ_UNCOMMITTED" -> Connection.TRANSACTION_READ_UNCOMMITTED;
            case "READ_COMMITTED" -> Connection.TRANSACTION_READ_COMMITTED;
            case "REPEATABLE_READ" -> Connection.TRANSACTION_REPEATABLE_READ;
            case "SERIALIZABLE" -> Connection.TRANSACTION_SERIALIZABLE;
            default -> throw new IOException("Unsupported JDBC isolation level: " + value);
        };
    }

    private static String requiredString(JSONObject object, String key, String resource)
            throws IOException {
        String value = optionalString(object, key, null);
        if (value == null || value.isBlank()) {
            throw new IOException("Missing required string " + key + " in " + resource);
        }
        return value;
    }

    private static String optionalString(JSONObject object, String key, String defaultValue)
            throws IOException {
        Object value = object.get(key);
        return value == null ? defaultValue : stringValue(value, key, "JSON object");
    }

    private static String stringValue(Object value, String field, String resource)
            throws IOException {
        if (!(value instanceof String string)) {
            throw new IOException(field + " must be a string in " + resource);
        }
        return string;
    }

    private static JSONObject requiredObject(JSONObject object, String key, String resource)
            throws IOException {
        Object value = object.get(key);
        if (value == null) {
            throw new IOException("Missing required object " + key + " in " + resource);
        }
        return objectValue(value, key, resource);
    }

    private static JSONObject objectValue(Object value, String field, String resource)
            throws IOException {
        if (!(value instanceof JSONObject object)) {
            throw new IOException(field + " must be a JSON object in " + resource);
        }
        return object;
    }

    private static JSONArray requiredArray(JSONObject object, String key, String resource)
            throws IOException {
        Object value = object.get(key);
        if (value == null) {
            throw new IOException("Missing required array " + key + " in " + resource);
        }
        return arrayValue(value, key, resource);
    }

    private static JSONArray arrayValue(Object value, String field, String resource)
            throws IOException {
        if (!(value instanceof JSONArray array)) {
            throw new IOException(field + " must be a JSON array in " + resource);
        }
        return array;
    }

    private static List<String> stringList(Object value, String field, String resource)
            throws IOException {
        if (value == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object entry : arrayValue(value, field, resource)) {
            result.add(stringValue(entry, field, resource));
        }
        return result;
    }

    private static boolean booleanValue(
            Object value,
            boolean defaultValue,
            String field,
            String resource) throws IOException {
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Boolean booleanValue)) {
            throw new IOException(field + " must be Boolean in " + resource);
        }
        return booleanValue;
    }

    private static int integerValue(Object value, String field, String resource)
            throws IOException {
        if (!(value instanceof Number number)) {
            throw new IOException(field + " must be numeric in " + resource);
        }
        return number.intValue();
    }

    private static long longValue(
            Object value,
            long defaultValue,
            String field,
            String resource) throws IOException {
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Number number)) {
            throw new IOException(field + " must be numeric in " + resource);
        }
        return number.longValue();
    }

    private static <T> Set<T> immutableSet(Set<T> values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }
}
