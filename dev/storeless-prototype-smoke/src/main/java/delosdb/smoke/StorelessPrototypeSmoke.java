package delosdb.smoke;

import java.io.InputStream;
import java.util.Properties;

import org.apache.derby.impl.storeless.StorelessService;
import org.apache.derby.shared.common.reference.EngineType;
import org.apache.derby.shared.common.reference.Property;

public final class StorelessPrototypeSmoke {
    private static final String MODULES_RESOURCE = "org/apache/derby/modules.properties";

    private StorelessPrototypeSmoke() {
    }

    public static void main(String[] args) throws Exception {
        assertLoadable("org.apache.derby.impl.storeless.StorelessDatabase");
        assertLoadable("org.apache.derby.impl.storeless.StorelessService");
        assertLoadable("org.apache.derby.impl.storeless.EmptyDictionary");
        assertLoadable("org.apache.derby.impl.storeless.NoOpTransaction");

        Properties modules = loadModulesProperties();
        assertEquals(
                "org.apache.derby.impl.storeless.StorelessService",
                modules.getProperty("derby.module.storeless.service"),
                "storeless service module");
        assertEquals(
                "org.apache.derby.impl.storeless.StorelessDatabase",
                modules.getProperty("derby.module.storeless.database"),
                "storeless database module");
        assertEquals(
                "org.apache.derby.impl.storeless.EmptyDictionary",
                modules.getProperty("derby.module.storeless.dictionary"),
                "storeless dictionary module");

        StorelessService service = new StorelessService();
        assertEquals("storeless", service.getType(), "storeless service type");
        assertEquals(false, service.hasStorageFactory(), "storeless storage factory flag");
        assertEquals(true, service.isSameService("storeless", "storeless"), "storeless service identity");

        Properties serviceProperties = service.getServiceProperties("storeless", new Properties());
        assertEquals(
                "org.apache.derby.database.Database",
                serviceProperties.getProperty(Property.SERVICE_PROTOCOL),
                "storeless service protocol");
        assertEquals(
                Integer.toString(EngineType.STORELESS_ENGINE),
                serviceProperties.getProperty(EngineType.PROPERTY),
                "storeless engine type");

        System.out.println("DelosDB storeless prototype smoke test passed.");
    }

    private static void assertLoadable(String className) throws ClassNotFoundException {
        Class.forName(className, false, StorelessPrototypeSmoke.class.getClassLoader());
    }

    private static Properties loadModulesProperties() throws Exception {
        try (InputStream stream = StorelessPrototypeSmoke.class.getClassLoader().getResourceAsStream(MODULES_RESOURCE)) {
            if (stream == null) {
                throw new AssertionError("Missing storeless modules resource: " + MODULES_RESOURCE);
            }
            Properties properties = new Properties();
            properties.load(stream);
            return properties;
        }
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + " expected <" + expected + "> but was <" + actual + ">");
        }
    }
}
