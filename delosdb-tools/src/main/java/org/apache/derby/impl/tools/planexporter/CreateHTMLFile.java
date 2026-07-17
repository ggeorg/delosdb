/*

   Derby - Class org.apache.derby.impl.tools.planexporter.CreateHTMLFile

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

package org.apache.derby.impl.tools.planexporter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;

import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.apache.derby.iapi.xml.SecureXmlFactory;

/**
 * Creates HTML query-plan output from the PlanExporter XML representation.
 */
public class CreateHTMLFile {

    private static final String DEFAULT_XSL_STYLE_SHEET = "resources/vanilla_html.xsl";

    /**
     * @param XMLFileName name of the XML file
     * @param XSLSheetName name of the XSL file
     * @param HTMLFile name of the HTML file
     * @param def whether to use the default XSL or not
     * @throws Exception on transformation or I/O failure
     */
    public void getHTML(String XMLFileName, String XSLSheetName,
            String HTMLFile, boolean def) throws Exception {

        if (!HTMLFile.toUpperCase().endsWith(".HTML")) {
            HTMLFile += ".html";
        }

        TransformerFactory transformerFactory = SecureXmlFactory.newTransformerFactory();
        Transformer transformer = createTransformer(transformerFactory, XSLSheetName, def);

        try (FileOutputStream output = new FileOutputStream(HTMLFile)) {
            transformer.transform(
                    new StreamSource(new File(XMLFileName)),
                    new StreamResult(output));
        }
    }

    private Transformer createTransformer(
            TransformerFactory transformerFactory,
            String requestedStyleSheet,
            boolean useDefault) throws Exception {
        if (!useDefault) {
            File style = new File(requestedStyleSheet);
            if (style.exists()) {
                return transformerFactory.newTransformer(new StreamSource(style));
            }
        }

        String resourceName = useDefault ? requestedStyleSheet : DEFAULT_XSL_STYLE_SHEET;
        URL resource = getClass().getResource(resourceName);
        if (resource == null) {
            throw new IllegalArgumentException("PlanExporter stylesheet not found: " + resourceName);
        }
        try (InputStream styleSheet = resource.openStream()) {
            StreamSource source = new StreamSource(styleSheet, resource.toExternalForm());
            return transformerFactory.newTransformer(source);
        }
    }
}
