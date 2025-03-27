package net.whydah.sts.config;

import net.whydah.sso.config.ApplicationMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Helper methods for reading configuration.
 */
public class AppConfig {
    public static final String IAM_CONFIG_KEY = "IAM_CONFIG";
    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    // Static instance for backward compatibility
    private static final AppConfig INSTANCE = new AppConfig();

    private final Random rand;
    private final AtomicReference<Properties> propertiesRef;
    private final AtomicReference<String> fullTokenApplicationsRef;
    private final List<String> preDefinedFullTokenApplications;

    // Default constructor for backward compatibility
    public AppConfig() {
        this(new SecureRandom(), new ArrayList<>());
    }

    // Constructor for testing and dependency injection
    public AppConfig(Random random, List<String> initialPreDefinedApps) {
        this.rand = random;
        this.propertiesRef = new AtomicReference<>(null);
        this.fullTokenApplicationsRef = new AtomicReference<>(null);
        this.preDefinedFullTokenApplications = Collections.synchronizedList(new ArrayList<>(initialPreDefinedApps));
        initializeProperties();
    }

    // Static methods for backward compatibility
    public static String getProperty(String key) {
        return INSTANCE.getPropertyInternal(key);
    }

    public static List<String> getPredefinedFullTokenApplications() {
        return INSTANCE.getPredefinedFullTokenApplicationsInternal();
    }

    public static void setFullTokenApplications(String appLinks) {
        INSTANCE.setFullTokenApplicationsInternal(appLinks);
    }

    // Instance methods
    private String getPropertyInternal(String key) {
        checkAndReloadProperties();
        Properties props = propertiesRef.get();
        return props != null ? props.getProperty(key) : null;
    }

    private List<String> getPredefinedFullTokenApplicationsInternal() {
        return Collections.unmodifiableList(preDefinedFullTokenApplications);
    }

    private void setFullTokenApplicationsInternal(String appLinks) {
        fullTokenApplicationsRef.set(appLinks);
        log.debug("updated fulltoken list: {}", appLinks);
    }

    /**
     * Checks if properties should be reloaded and performs reload if necessary.
     * Properties are reloaded if they haven't been loaded yet or with 5% probability.
     */
    private void checkAndReloadProperties() {
        if (shouldReloadProperties()) {
            initializeProperties();
        }
    }

    private boolean shouldReloadProperties() {
        return propertiesRef.get() == null || rand.nextInt(100) > 95;
    }

    /**
     * Initializes or reloads the properties from the configuration sources.
     */
    private void initializeProperties() {
        try {
            Properties newProperties = readProperties(ApplicationMode.getApplicationMode());
            propertiesRef.set(newProperties);
            logProperties(newProperties);

            String predefinedFullTokenApplicationConfig = newProperties.getProperty("fulltokenapplications");
            if (predefinedFullTokenApplicationConfig != null && predefinedFullTokenApplicationConfig.contains(",")) {
                synchronized (preDefinedFullTokenApplications) {
                    preDefinedFullTokenApplications.clear();
                    preDefinedFullTokenApplications.addAll(
                            Arrays.asList(predefinedFullTokenApplicationConfig.split("\\s*,\\s*"))
                    );
                }
            }
        } catch (IOException e) {
            log.error("Failed to initialize properties", e);
            throw new RuntimeException(e.getLocalizedMessage(), e);
        }
    }

    private void logProperties(Properties properties) {
        if (properties != null) {
            for (String key : properties.stringPropertyNames()) {
                log.info("Property: {}, value {}", key, properties.getProperty(key));
            }
        }
    }

    /**
     * Reads properties from both classpath and file system if configured.
     */
    private static Properties readProperties(String appMode) throws IOException {
        Properties properties = loadFromClasspath(appMode);

        String configFilename = System.getProperty(IAM_CONFIG_KEY);
        if (configFilename != null) {
            loadFromFile(properties, configFilename);
        }
        return properties;
    }

    private static Properties loadFromClasspath(String appMode) throws IOException {
        Properties properties = new Properties();
        String propertyfile = String.format("securitytokenservice.%s.properties", appMode);
        log.info("Loading default properties from classpath: {}", propertyfile);

        try (InputStream is = AppConfig.class.getClassLoader().getResourceAsStream(propertyfile)) {
            if (is == null) {
                log.error("Error reading {} from classpath.", propertyfile);
                throw new IOException("Could not find " + propertyfile + " in classpath");
            }
            properties.load(is);
        }
        return properties;
    }

    private static void loadFromFile(Properties properties, String configFilename) throws IOException {
        File file = new File(configFilename);
        log.info("Overriding default properties with file {}", file.getAbsolutePath());

        if (file.exists()) {
            try (FileInputStream fis = new FileInputStream(file)) {
                properties.load(fis);
            }
        } else {
            log.error("Config file {} specified by System property {} not found.", configFilename, IAM_CONFIG_KEY);
        }
    }

    // For testing purposes only
    Properties getProperties() {
        return propertiesRef.get();
    }
}