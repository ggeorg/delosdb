/*

   Derby - Class org.apache.derbyBuild.EnglishMessagePropertiesGenerator

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

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Generates the English Derby message bundle from {@code messages.xml}.
 *
 * <p>The original Derby Ant build used {@link MessageBuilder} to produce both
 * {@code messages_en.properties} and the reference-guide SQLState table. The
 * DelosDB Gradle build only needs the runtime properties file here. Keeping this
 * tiny command-line generator avoids depending on Ant task wiring while
 * preserving the message text escaping used by the inherited Derby build.</p>
 */
public final class EnglishMessagePropertiesGenerator {

    private static final String PROPERTIES_BOILERPLATE =
        "# Licensed to the Apache Software Foundation (ASF) under one or more\n" +
        "# contributor license agreements.  See the NOTICE file distributed with\n" +
        "# this work for additional information regarding copyright ownership.\n" +
        "# The ASF licenses this file to You under the Apache License, Version 2.0\n" +
        "# (the \\\"License\\\"); you may not use this file except in compliance with\n" +
        "# the License.  You may obtain a copy of the License at\n" +
        "#\n" +
        "#     http://www.apache.org/licenses/LICENSE-2.0\n" +
        "#\n" +
        "# Unless required by applicable law or agreed to in writing, software\n" +
        "# distributed under the License is distributed on an \\\"AS IS\\\" BASIS,\n" +
        "# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.\n" +
        "# See the License for the specific language governing permissions and\n" +
        "# limitations under the License.\n" +
        "#\n" +
        "# This file was generated from messages.xml by the Gradle build.\n" +
        "# Edit messages.xml instead of editing this file.";

    private EnglishMessagePropertiesGenerator() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                "Usage: EnglishMessagePropertiesGenerator <messages.xml> <messages_en.properties>");
        }

        File source = new File(args[0]);
        File target = new File(args[1]);
        File parent = target.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setValidating(false);
        factory.setNamespaceAware(false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document document = builder.parse(source);

        try (FileWriter fw = new FileWriter(target); PrintWriter pw = new PrintWriter(fw)) {
            pw.println(PROPERTIES_BOILERPLATE);
            writeMessages(document, pw);
        }
    }

    private static void writeMessages(Document document, PrintWriter pw) throws IOException {
        NodeList messages = document.getDocumentElement().getElementsByTagName("msg");
        for (int i = 0; i < messages.getLength(); i++) {
            Element message = (Element) messages.item(i);
            String name = squeezeText(firstChild(message, "name"));
            String text = squeezeText(firstChild(message, "text"));
            String[] comments = optionalSubElements(message, "comment");
            String[] args = optionalSubElements(message, "arg");

            pw.println();
            for (String comment : comments) {
                pw.println("# " + comment);
            }
            if (comments.length != 0) {
                pw.println("#");
            }
            if (args.length != 0) {
                pw.println("# Arguments:");
                pw.println("#");
                for (int argIndex = 0; argIndex < args.length; argIndex++) {
                    pw.println("#    {" + argIndex + "} = " + args[argIndex]);
                }
                pw.println("#");
            }
            pw.println(name + "=" + escapePropertiesText(text));
        }
    }

    private static Element firstChild(Element node, String childName) {
        return (Element) node.getElementsByTagName(childName).item(0);
    }

    private static String[] optionalSubElements(Element message, String subElementTag) {
        NodeList options = message.getElementsByTagName(subElementTag);
        String[] retval = new String[options.getLength()];
        for (int i = 0; i < options.getLength(); i++) {
            retval[i] = squeezeText((Element) options.item(i));
        }
        return retval;
    }

    private static String squeezeText(Element node) {
        Node textChild = node.getFirstChild();
        return textChild == null ? "" : textChild.getNodeValue();
    }

    /**
     * Match Derby's inherited MessageBuilder properties escaping.
     */
    private static String escapePropertiesText(String input) {
        String output = input.replace("\n", "\\n");
        output = output.replace("'", "''");
        return output;
    }
}
