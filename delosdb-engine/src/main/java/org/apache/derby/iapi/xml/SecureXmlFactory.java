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
 * Centralizes the XML factory hardening used by inherited Derby XML paths.
 *
 * <p>The goal is deliberately narrow: keep Derby's existing DOM/XPath based
 * XML implementation, but make the parser and transformer factory setup
 * explicit on Java 21.  Callers still decide validation and namespace policy;
 * this helper blocks external XML resources and enables secure processing.</p>
 */
public final class SecureXmlFactory
{
    private static final String EXTERNAL_GENERAL_ENTITIES =
        "http://xml.org/sax/features/external-general-entities";

    private static final String EXTERNAL_PARAMETER_ENTITIES =
        "http://xml.org/sax/features/external-parameter-entities";

    private static final String LOAD_EXTERNAL_DTD =
        "http://apache.org/xml/features/nonvalidating/load-external-dtd";

    private SecureXmlFactory()
    {
    }

    /**
     * Create a hardened DOM parser factory while preserving the caller's
     * validation and namespace policy.
     */
    public static DocumentBuilderFactory newDocumentBuilderFactory(
            boolean validating,
            boolean namespaceAware)
        throws ParserConfigurationException
    {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

        factory.setValidating(validating);
        factory.setNamespaceAware(namespaceAware);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);

        setRequiredFeature(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
        setRequiredFeature(factory, EXTERNAL_GENERAL_ENTITIES, false);
        setRequiredFeature(factory, EXTERNAL_PARAMETER_ENTITIES, false);
        setRequiredFeature(factory, LOAD_EXTERNAL_DTD, false);

        setRequiredAttribute(factory, XMLConstants.ACCESS_EXTERNAL_DTD, "");
        setRequiredAttribute(factory, XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

        return factory;
    }

    /**
     * Create a hardened transformer factory for XML serialization/export paths.
     */
    public static TransformerFactory newTransformerFactory()
        throws TransformerConfigurationException
    {
        TransformerFactory factory = TransformerFactory.newInstance();

        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        setRequiredAttribute(factory, XMLConstants.ACCESS_EXTERNAL_DTD, "");
        setRequiredAttribute(factory, XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");

        return factory;
    }

    private static void setRequiredFeature(
            DocumentBuilderFactory factory,
            String feature,
            boolean value)
        throws ParserConfigurationException
    {
        factory.setFeature(feature, value);
    }

    private static void setRequiredAttribute(
            DocumentBuilderFactory factory,
            String name,
            String value)
        throws ParserConfigurationException
    {
        try {
            factory.setAttribute(name, value);
        } catch (IllegalArgumentException iae) {
            ParserConfigurationException pce = new ParserConfigurationException(
                "XML parser does not support required secure attribute: " + name);
            pce.initCause(iae);
            throw pce;
        }
    }

    private static void setRequiredAttribute(
            TransformerFactory factory,
            String name,
            String value)
        throws TransformerConfigurationException
    {
        try {
            factory.setAttribute(name, value);
        } catch (IllegalArgumentException iae) {
            TransformerConfigurationException tce = new TransformerConfigurationException(
                "XML transformer does not support required secure attribute: " + name);
            tce.initCause(iae);
            throw tce;
        }
    }
}
