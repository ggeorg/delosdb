/*

   Derby - Class org.apache.derby.impl.tools.dblook.DB_Jar

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

package org.apache.derby.impl.tools.dblook;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.apache.derby.tools.dblook;

public class DB_Jar {

	/* ************************************************
	 * Generate the DDL for all jars in a given
	 * database.
	 * @param dbName Name of the database (for locating the jar).
	 * @param conn Connection to the source database.
     * @param at10_9 Dictionary is at 10.9 or higher
	 * @return The DDL for the jars has been written
	 *  to output via Logs.java.
	 ****/

	public static void doJars(
        String dbName, Connection conn, boolean at10_9)
		throws SQLException
	{

		String separator = System.getProperty("file.separator");
		Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(
            "SELECT FILENAME, SCHEMAID, " +
            "GENERATIONID, FILEID FROM SYS.SYSFILES");

		boolean firstTime = true;
		while (rs.next()) {
            String jarName = rs.getString(1);
            String schemaId = rs.getString(2);
            String generationId = rs.getString(3);
            String fileId = rs.getString(4);
            String schemaNameSQL = dblook.lookupSchemaId(schemaId);

            if (dblook.isIgnorableSchema(schemaNameSQL)) {
                continue;
            }

            doHeader(firstTime);
            String installJar = buildInstallJar(
                    dbName,
                    separator,
                    at10_9,
                    jarName,
                    schemaNameSQL,
                    generationId,
                    fileId);
            if (installJar == null) {
                firstTime = false;
                continue;
            }

            Logs.writeToNewDDL(installJar);
            Logs.writeStmtEndToNewDDL();
            Logs.writeNewlineToNewDDL();
            firstTime = false;
		}

		stmt.close();
		rs.close();
	}

    private static String buildInstallJar(
            String dbName,
            String separator,
            boolean at10_9,
            String jarName,
            String schemaNameSQL,
            String generationId,
            String fileId) {

        String jarFileName;
        String jarDirectory;
        String installName;
        String sourcePath;

        if (at10_9) {
            String schemaNameCNF = dblook.unExpandDoubleQuotes(
                    dblook.stripQuotes(schemaNameSQL));
            jarFileName = fileId + ".jar.G" + generationId;
            jarDirectory = "DBJARS";
            installName = dblook.addQuotes(
                    dblook.expandDoubleQuotes(schemaNameCNF)) + "." +
                    dblook.addQuotes(dblook.expandDoubleQuotes(jarName));
            sourcePath = dbName + separator + "jar" + separator + jarFileName;
        } else {
            String quotedJarName = dblook.addQuotes(
                    dblook.expandDoubleQuotes(jarName));
            String schemaWithoutQuotes = dblook.stripQuotes(schemaNameSQL);
            jarFileName = dblook.stripQuotes(quotedJarName) +
                    ".jar.G" + generationId;
            jarDirectory = "DBJARS" + separator + schemaWithoutQuotes;
            installName = schemaNameSQL + "." + quotedJarName;
            sourcePath = dbName + separator + "jar" + separator +
                    schemaWithoutQuotes + separator + jarFileName;
        }

        String destinationSuffix = separator + jarFileName;
        String absoluteJarDirectory = copyJarToDirectory(
                sourcePath, separator, jarDirectory, destinationSuffix);
        if (absoluteJarDirectory == null) {
            return null;
        }

        return "CALL SQLJ.INSTALL_JAR('file:" + absoluteJarDirectory +
                destinationSuffix + "', '" + installName + "', 0)";
    }

    private static String copyJarToDirectory(
            String sourcePath,
            String separator,
            String jarDirectory,
            String destinationSuffix) {

        String absoluteJarDirectory = null;
        try {
            File directory = new File(
                    System.getProperty("user.dir") + separator + jarDirectory);
            absoluteJarDirectory = directory.getAbsolutePath();
            directory.mkdirs();
            doCopy(sourcePath, absoluteJarDirectory + destinationSuffix);
            return absoluteJarDirectory;
        } catch (IOException | SecurityException failure) {
            Logs.debug(
                    "DBLOOK_FailedToLoadJar",
                    absoluteJarDirectory + destinationSuffix);
            Logs.debug(failure);
            return null;
        }
    }

    private static void  doHeader(boolean firstTime) {
        if (firstTime) {
            Logs.reportString(
                "----------------------------------------------");
            Logs.reportMessage("DBLOOK_JarsHeader");
            Logs.reportMessage("DBLOOK_Jar_Note");
            Logs.reportString(
                "----------------------------------------------\n");
        }
    }

    private static void doCopy(
        String oldJarFileName,
        String newJarFileName) throws IOException {

        FileInputStream oldJarFile = new FileInputStream(oldJarFileName);
        FileOutputStream newJarFile = new FileOutputStream(newJarFileName);
        while (true) {
            if (oldJarFile.available() == 0)
                break;
            byte[] bAr = new byte[oldJarFile.available()];
            oldJarFile.read(bAr);
            newJarFile.write(bAr);
        }

        oldJarFile.close();
        newJarFile.close();
    }
}
