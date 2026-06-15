/*

   Derby - Class org.apache.derby.iapi.xml.SecureXmlFactory

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

package org.apache.derby.iapi.xml;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerFactory;

/**
 * Centralizes the XML factory setup used by inherited Derby XML paths.
 *
 * <p>This helper is intentionally compatibility-preserving.  Apache Derby
 * 10.17 already enables secure processing and disables external general
 * entities on the SQL/XML and XML VTI parser paths.  DelosDB keeps those
 * semantics in one place so future hardening can be tested without silently
 * changing SQL/XML behavior.</p>
 *
 * <p>In particular, do not disable DTD loading or entity-reference expansion
 * here.  Existing Derby SQL/XML behavior relies on non-validating DTD
 * processing for default attributes, and Derby's billion-laughs protection
 * relies on the parser expanding internal entities until the secure-processing
 * entity-expansion limit raises the expected SQL/XML parse error.</p>
 */
public final class SecureXmlFactory
{
    private static final String EXTERNAL_GENERAL_ENTITIES =
        "http://xml.org/sax/features/external-general-entities";

    private SecureXmlFactory()
    {
    }

    /**
     * Create a DOM parser factory while preserving Derby's historical XML
     * semantics: non-validating callers may still process DTD defaults, but
     * external general entity expansion is disabled.
     */
    public static DocumentBuilderFactory newDocumentBuilderFactory(
            boolean validating,
            boolean namespaceAware)
        throws ParserConfigurationException
    {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

        factory.setValidating(validating);
        factory.setNamespaceAware(namespaceAware);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature(EXTERNAL_GENERAL_ENTITIES, false);

        return factory;
    }

    /**
     * Create a transformer factory for XML serialization/export paths.
     */
    public static TransformerFactory newTransformerFactory()
        throws TransformerConfigurationException
    {
        TransformerFactory factory = TransformerFactory.newInstance();

        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        setOptionalAttribute(factory, XMLConstants.ACCESS_EXTERNAL_DTD, "");
        setOptionalAttribute(factory, XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");

        return factory;
    }

    private static void setOptionalAttribute(
            TransformerFactory factory,
            String name,
            String value)
        throws TransformerConfigurationException
    {
        try {
            factory.setAttribute(name, value);
        } catch (IllegalArgumentException iae) {
            // Some JAXP implementations do not support these Java 7+ access
            // controls.  Parser hardening above is required for SQL/XML input;
            // transformer output paths should remain compatible with older
            // transformer implementations if the attribute is unavailable.
        }
    }
}
