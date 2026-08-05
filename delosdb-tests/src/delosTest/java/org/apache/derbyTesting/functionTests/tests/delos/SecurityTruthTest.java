/*

   DelosDB - Class org.apache.derbyTesting.functionTests.tests.delos.SecurityTruthTest

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
package org.apache.derbyTesting.functionTests.tests.delos;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Properties;

import javax.net.SocketFactory;

import org.apache.derby.impl.tools.planexporter.CreateHTMLFile;
import org.apache.derby.shared.common.drda.NaiveTrustManager;

import junit.framework.TestCase;

/** Focused Phase 8.5 security-default and truthful-boundary tests. */
public final class SecurityTruthTest extends TestCase {
    public void testPasswordlessTlsKeyStoreConfigurationIsNullSafeAndClosesInput() throws Exception {
        Path directory = Files.createTempDirectory("delos-tls-keystore-");
        Path keyStoreFile = directory.resolve("empty.jks");
        try {
            KeyStore keyStore = KeyStore.getInstance("JKS");
            keyStore.load(null, null);
            try (OutputStream output = Files.newOutputStream(keyStoreFile)) {
                keyStore.store(output, new char[0]);
            }

            Properties properties = new Properties();
            NaiveTrustManager.copyKeyStoreProperties(
                    properties, keyStoreFile.toString(), null);
            assertEquals(keyStoreFile.toString(),
                    properties.getProperty(NaiveTrustManager.SSL_KEYSTORE));
            assertFalse(properties.containsKey(NaiveTrustManager.SSL_KEYSTORE_PASSWORD));

            SocketFactory socketFactory = NaiveTrustManager.getSocketFactory(properties);
            assertNotNull(socketFactory);

            // This is a functional stream-lifecycle check on platforms which
            // reject deletion while a FileInputStream remains open.
            Files.delete(keyStoreFile);
            assertFalse(Files.exists(keyStoreFile));
        } finally {
            Files.deleteIfExists(keyStoreFile);
            Files.deleteIfExists(directory);
        }
    }

    public void testPlanExporterTransformsNormalXmlAndRejectsExternalDtd() throws Exception {
        Path directory = Files.createTempDirectory("delos-plan-export-");
        Path styleSheet = directory.resolve("plan.xsl");
        Path normalXml = directory.resolve("normal.xml");
        Path normalHtml = directory.resolve("normal-output");
        Path secret = directory.resolve("secret.txt");
        Path maliciousXml = directory.resolve("external-dtd.xml");
        Path maliciousHtml = directory.resolve("external-output");
        try {
            Files.writeString(styleSheet, """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <xsl:stylesheet version="1.0"
                        xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
                      <xsl:output method="html"/>
                      <xsl:template match="/">
                        <html><body><xsl:value-of select="plan/value"/></body></html>
                      </xsl:template>
                    </xsl:stylesheet>
                    """, StandardCharsets.UTF_8);
            Files.writeString(normalXml,
                    "<plan><value>safe-plan</value></plan>",
                    StandardCharsets.UTF_8);

            CreateHTMLFile exporter = new CreateHTMLFile();
            exporter.getHTML(
                    normalXml.toString(),
                    styleSheet.toString(),
                    normalHtml.toString(),
                    false);
            String normalOutput = Files.readString(
                    Path.of(normalHtml + ".html"), StandardCharsets.UTF_8);
            assertTrue(normalOutput.contains("safe-plan"));

            Files.writeString(secret, "external-secret", StandardCharsets.UTF_8);
            Files.writeString(maliciousXml, """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <!DOCTYPE plan [<!ENTITY external SYSTEM "%s">]>
                    <plan><value>&external;</value></plan>
                    """.formatted(secret.toUri()), StandardCharsets.UTF_8);

            try {
                exporter.getHTML(
                        maliciousXml.toString(),
                        styleSheet.toString(),
                        maliciousHtml.toString(),
                        false);
                fail("Expected secure PlanExporter XML processing to reject an external DTD");
            } catch (Exception expected) {
                assertTrue("expected secure XML external-access rejection, got: " + expected,
                        containsMessage(expected, "accessExternalDTD")
                                || containsMessage(expected, "external")
                                || containsMessage(expected, "DOCTYPE")
                                || containsMessage(expected, "not allowed"));
            }

            Path output = Path.of(maliciousHtml + ".html");
            if (Files.exists(output)) {
                assertFalse(Files.readString(output, StandardCharsets.UTF_8)
                        .contains("external-secret"));
            }
        } finally {
            try (var files = Files.list(directory)) {
                files.forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                    }
                });
            }
            Files.deleteIfExists(directory);
        }
    }

    private static boolean containsMessage(Throwable failure, String token) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase().contains(token.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}
