/*

   Derby - Class org.apache.derby.impl.services.monitor.FileMonitor

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

package org.apache.derby.impl.services.monitor;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.apache.derby.shared.common.reference.Property;
import org.apache.derby.shared.common.i18n.MessageService;
import org.apache.derby.shared.common.info.ProductGenusNames;
import org.apache.derby.shared.common.info.ProductVersionHolder;
import org.apache.derby.iapi.services.io.FileUtil;
import org.apache.derby.shared.common.reference.MessageId;

/**
	Implementation of the monitor that uses the class loader
	that the its was loaded in for all class loading.

*/

public final class FileMonitor extends BaseMonitor
{

	/* Fields */
	private File home;

	private ProductVersionHolder engineVersion;

	public FileMonitor() {
		initialize(true);
		applicationProperties = readApplicationProperties();
	}

	public FileMonitor(Properties properties, PrintWriter log) {
		runWithState(properties, log);
	}



	private InputStream PBapplicationPropertiesStream()
	  throws IOException {

        File sr = new File(home, Property.PROPERTIES_FILE);

		if (!sr.exists())
			return null;

		return new FileInputStream(sr);
	}

	public Object getEnvironment() {
		return home;
	}

    /**
     * Create a ThreadGroup and set the daemon property to make sure
     * the group is destroyed and garbage collected when all its
     * members have finished (i.e., either when the driver is
     * unloaded, or when the last database is shut down).
     *
     * Warnings are suppressed because ThreadGroup.setDaemon() was slated
     * for removal by Open JDK build 16-ea+26-1764. See https://issues.apache.org/jira/browse/DERBY-7094
     *
     * @return the thread group "derby.daemons"
     */
    @SuppressWarnings("removal")
    private ThreadGroup createDaemonGroup() {
        ThreadGroup group = new ThreadGroup("derby.daemons");
        group.setDaemon(true);
        return group;
    }

    /**
       SECURITY WARNING.

       This method is run in a privileged block in a Java 2 environment.

       Set the system home directory.  Returns false if it couldn't for
       some reason.

    **/
    private boolean PBinitialize(boolean lite)
    {
        if (!lite) {
            daemonGroup = createDaemonGroup();
        }

        InputStream versionStream = getClass().getResourceAsStream("/" + ProductGenusNames.DBMS_INFO);

        engineVersion = ProductVersionHolder.getProductVersionHolderFromMyEnv(versionStream);

        String systemHome;
        // create the system home directory if it doesn't exist
        systemHome = System.getProperty(Property.SYSTEM_HOME_PROPERTY);

        if (systemHome != null) {
            home = new File(systemHome);

            // SECURITY PERMISSION - OP2a
            if (home.exists()) {
                if (!home.isDirectory()) {
                    report(Property.SYSTEM_HOME_PROPERTY + "=" + systemHome
                           + " does not represent a directory");
                    return false;
                }
            } else if (!lite) {

                try {
                    // SECURITY PERMISSION - OP2b
                    // Attempt to create just the folder initially
                    // which does not require read permission on
                    // the parent folder. This is to allow a policy
                    // file to limit file permissions for derby.jar
                    // to be contained under derby.system.home.
                    // If the folder cannot be created that way
                    // due to missing parent folder(s) 
                    // then mkdir() will return false and thus
                    // mkdirs will be called to create the
                    // intermediate folders. This use of mkdir()
                    // and mkdirs() retains existing (pre10.3) behaviour
                    // but avoids requiring read permission on the parent
                    // directory if it exists.
                    boolean created = false;
                    created = home.mkdir() || home.mkdirs();
                    if (created) {
                        FileUtil.limitAccessToOwner(home);
                    }
                } catch (IOException ioe) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
       SECURITY WARNING.

       This method is run in a privileged block in a Java 2 environment.

       Return a property from the JVM's system set.
       In a Java2 environment this will be executed as a privileged block
       if and only if the property starts with 'derby.'.
    */
    private String PBgetJVMProperty(String key) {

        return System.getProperty(key);
    }


    private final static Map<String, Void> securityProperties =
            new HashMap<String, Void>();
    static {
        securityProperties.put("derby.authentication.builtin.algorithm", null);
        securityProperties.put("derby.authentication.provider", null);
        securityProperties.put("derby.database.fullAccessUsers", null);
        securityProperties.put("derby.database.readOnlyAccessUsers", null);
        securityProperties.put("derby.database.sqlAuthorization", null);
        securityProperties.put("derby.connection.requireAuthentication", null);
        securityProperties.put("derby.database.defaultConnectionMode", null);
        securityProperties.put("derby.storage.useDefaultFilePermissions", null);
        securityProperties.put(Property.SYSTEM_HOME_PROPERTY, null);
    };

    /*
    ** Priv block code, moved out of the old Java2 version.
    */

    /**
       Initialize the system.
    **/
    final boolean initialize(final boolean lite)
    {
        return Boolean.valueOf(PBinitialize(lite));
    }

    final Properties getDefaultModuleProperties() {
        return FileMonitor.super.getDefaultModuleProperties();
    }

    public final String getJVMProperty(final String key) {
        if (!key.startsWith("derby."))
        { return PBgetJVMProperty(key); }

        return PBgetJVMProperty(key);
    }

    /**
     * Warnings are suppressed because ThreadGroup.setDaemon() was slated
     * for removal by Open JDK build 16-ea+26-1764. See https://issues.apache.org/jira/browse/DERBY-7094
     */
    @SuppressWarnings("removal")
    public synchronized final Thread getDaemonThread(
            final Runnable task,
            final String name,
            final boolean setMinPriority) {
        try {
            return FileMonitor.super.getDaemonThread(
                task, name, setMinPriority);
        } catch (IllegalThreadStateException e) {
            // We may get an IllegalThreadStateException if all the
            // previously running daemon threads have completed and the
            // daemon group has been automatically destroyed. If that's
            // what happened, create a new daemon group and try again.
            if (daemonGroup != null && daemonGroup.isDestroyed()) {
                daemonGroup = createDaemonGroup();
                return FileMonitor.super.getDaemonThread(
                    task, name, setMinPriority);
            } else {
                throw e;
            }
        }
    }

    final InputStream applicationPropertiesStream()
        throws IOException {
        return PBapplicationPropertiesStream();
    }

    public final ProductVersionHolder getEngineVersion() {
        return engineVersion;
    }
}
