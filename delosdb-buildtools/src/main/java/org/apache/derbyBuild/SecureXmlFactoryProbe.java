/*

   Derby - Class org.apache.derbyBuild.SecureXmlFactoryProbe

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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.transform.Transformer;
import org.apache.derby.iapi.xml.SecureXmlFactory;
import org.w3c.dom.Document;

/**
 * Build-time probe for the DelosDB XML factory hardening helper.
 */
public final class SecureXmlFactoryProbe
{
    private SecureXmlFactoryProbe()
    {
    }

    public static void main(String[] args) throws Exception
    {
        Path dir = Files.createTempDirectory("delosdb-secure-xml-probe-");
        Path secret = dir.resolve("secret.txt");
        Files.writeString(secret, "DELOSDB_XML_SECRET", StandardCharsets.UTF_8);

        String xml = "<!DOCTYPE root [ <!ENTITY xxe SYSTEM \"" +
            secret.toUri() + "\"> ]><root>&xxe;</root>";

        DocumentBuilder builder =
            SecureXmlFactory.newDocumentBuilderFactory(false, false).newDocumentBuilder();
        Document document = builder.parse(
            new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        String text = document.getDocumentElement().getTextContent();

        Transformer transformer = SecureXmlFactory.newTransformerFactory().newTransformer();

        System.out.println("# Secure XML factory probe");
        System.out.println();
        System.out.println("documentBuilder=" + builder.getClass().getName());
        System.out.println("transformer=" + transformer.getClass().getName());
        System.out.println("externalEntityExpanded=" + text.contains("DELOSDB_XML_SECRET"));
        System.out.println("textLength=" + text.length());

        if (text.contains("DELOSDB_XML_SECRET")) {
            throw new IllegalStateException("External XML entity was expanded");
        }
    }
}
