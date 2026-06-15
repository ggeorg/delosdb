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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.transform.Transformer;
import org.apache.derby.iapi.xml.SecureXmlFactory;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

/**
 * Build-time probe for the DelosDB XML factory helper.
 */
public final class SecureXmlFactoryProbe
{
    private SecureXmlFactoryProbe()
    {
    }

    public static void main(String[] args) throws Exception
    {
        Path dir = Files.createTempDirectory("delosdb-secure-xml-probe-");

        boolean externalEntityExpanded = externalEntityExpanded(dir);
        boolean externalDtdDefaultPreserved = externalDtdDefaultPreserved(dir);
        boolean internalEntityExpansionLimitRaised = internalEntityExpansionLimitRaised();

        Transformer transformer = SecureXmlFactory.newTransformerFactory().newTransformer();

        System.out.println("# Secure XML factory probe");
        System.out.println();
        System.out.println("transformer=" + transformer.getClass().getName());
        System.out.println("externalEntityExpanded=" + externalEntityExpanded);
        System.out.println("externalDtdDefaultPreserved=" + externalDtdDefaultPreserved);
        System.out.println("internalEntityExpansionLimitRaised=" + internalEntityExpansionLimitRaised);

        if (externalEntityExpanded) {
            throw new IllegalStateException("External XML entity was expanded");
        }

        if (!externalDtdDefaultPreserved) {
            throw new IllegalStateException(
                "DTD default attributes were not preserved; this breaks Derby SQL/XML compatibility");
        }

        if (!internalEntityExpansionLimitRaised) {
            throw new IllegalStateException(
                "Internal entity expansion limit was not raised for billion-laughs XML");
        }
    }

    private static boolean externalEntityExpanded(Path dir) throws Exception
    {
        Path secret = dir.resolve("secret.txt");
        Files.writeString(secret, "DELOSDB_XML_SECRET", StandardCharsets.UTF_8);

        String xml = "<!DOCTYPE root [ <!ENTITY xxe SYSTEM \"" +
            secret.toUri() + "\"> ]><root>&xxe;</root>";

        Document document = parse(xml);
        String text = document.getDocumentElement().getTextContent();

        return text.contains("DELOSDB_XML_SECRET");
    }

    private static boolean externalDtdDefaultPreserved(Path dir) throws Exception
    {
        Path dtd = dir.resolve("defaults.dtd");
        Files.writeString(
            dtd,
            "<!ELEMENT person EMPTY>\n" +
            "<!ATTLIST person noteTwo CDATA \"from-dtd\">\n",
            StandardCharsets.UTF_8);

        String oldAccessExternalDtd = System.getProperty("javax.xml.accessExternalDTD");
        try {
            System.setProperty("javax.xml.accessExternalDTD", "file");
            String xml = "<!DOCTYPE person SYSTEM \"" + dtd.toUri() +
                "\"><person/>";
            Document document = parse(xml);
            return "from-dtd".equals(
                document.getDocumentElement().getAttribute("noteTwo"));
        } finally {
            if (oldAccessExternalDtd == null) {
                System.clearProperty("javax.xml.accessExternalDTD");
            } else {
                System.setProperty("javax.xml.accessExternalDTD", oldAccessExternalDtd);
            }
        }
    }

    private static boolean internalEntityExpansionLimitRaised() throws Exception
    {
        String xml = "<!DOCTYPE lolz [" +
            " <!ENTITY lol \"lol\">" +
            " <!ELEMENT lolz (#PCDATA)>" +
            " <!ENTITY lol1 \"&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;\">" +
            " <!ENTITY lol2 \"&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;\">" +
            " <!ENTITY lol3 \"&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;\">" +
            " <!ENTITY lol4 \"&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;\">" +
            " <!ENTITY lol5 \"&lol4;&lol4;&lol4;&lol4;&lol4;&lol4;&lol4;&lol4;&lol4;&lol4;\">" +
            " <!ENTITY lol6 \"&lol5;&lol5;&lol5;&lol5;&lol5;&lol5;&lol5;&lol5;&lol5;&lol5;\">" +
            "]><lolz>&lol6;</lolz>";

        try {
            parse(xml);
            return false;
        } catch (SAXException expected) {
            return true;
        }
    }

    private static Document parse(String xml) throws Exception
    {
        DocumentBuilder builder =
            SecureXmlFactory.newDocumentBuilderFactory(false, false).newDocumentBuilder();
        return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
