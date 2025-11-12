package net.whydah.sts.smsgw;

import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.whydah.sts.config.AppConfig;

/**
 * Configuration for SMS Gateway providers using AppConfig
 */
public class SMSGatewayConfig {
    
    private static final Logger log = LoggerFactory.getLogger(SMSGatewayConfig.class);
    
    private final Properties testProperties;
    
    // Active provider
    private static final String ACTIVE_PROVIDER_KEY = "smsgw.active.provider";
    
    // Puzzel configuration keys
    private static final String PUZZEL_SERVICE_URL_KEY = "smsgw.puzzel.serviceurl";
    private static final String PUZZEL_SERVICE_ACCOUNT_KEY = "smsgw.puzzel.serviceaccount";
    private static final String PUZZEL_USERNAME_KEY = "smsgw.puzzel.username";
    private static final String PUZZEL_PASSWORD_KEY = "smsgw.puzzel.password";
    private static final String PUZZEL_QUERY_PARAMS_KEY = "smsgw.puzzel.queryparams";
    
    // Target365 configuration keys
    private static final String TARGET365_SERVICE_URL_KEY = "smsgw.target365.serviceurl";
    private static final String TARGET365_API_KEY = "smsgw.target365.apikey";
    private static final String TARGET365_SENDER_KEY = "smsgw.target365.sender";
    private static final String TARGET365_TAG_KEY = "smsgw.target365.tag";
    
    /**
     * Default constructor using AppConfig
     */
    public SMSGatewayConfig() {
        this.testProperties = null;
        validateConfiguration();
    }
    
    /**
     * Constructor for testing with custom Properties
     */
    public SMSGatewayConfig(Properties testProperties) {
        this.testProperties = testProperties;
        validateConfiguration();
    }
    
    /**
     * Get property value - uses testProperties if available, otherwise AppConfig
     */
    private String getProperty(String key) {
        if (testProperties != null) {
            return testProperties.getProperty(key);
        }
        return AppConfig.getProperty(key);
    }
    
    private void validateConfiguration() {
        String provider = getActiveProvider();
        if (provider == null || provider.isEmpty()) {
            log.warn("No active SMS gateway provider configured");
            return;
        }
        
        switch (provider.toLowerCase()) {
            case "puzzel":
                validatePuzzelConfig();
                break;
            case "target365":
            case "strex":
                validateTarget365Config();
                break;
            default:
                log.warn("Unknown SMS gateway provider configured: {}", provider);
        }
    }
    
    private void validatePuzzelConfig() {
        if (getPuzzelServiceUrl() == null || getPuzzelServiceUrl().isEmpty()) {
            log.error("Puzzel service URL not configured");
        }
        if (getPuzzelServiceAccount() == null || getPuzzelServiceAccount().isEmpty()) {
            log.error("Puzzel service account not configured");
        }
        if (getPuzzelUsername() == null || getPuzzelUsername().isEmpty()) {
            log.error("Puzzel username not configured");
        }
        if (getPuzzelPassword() == null || getPuzzelPassword().isEmpty()) {
            log.error("Puzzel password not configured");
        }
    }
    
    private void validateTarget365Config() {
        if (getTarget365ServiceUrl() == null || getTarget365ServiceUrl().isEmpty()) {
            log.error("Target365 service URL not configured");
        }
        if (getTarget365ApiKey() == null || getTarget365ApiKey().isEmpty()) {
            log.error("Target365 API key not configured");
        }
        if (getTarget365Sender() == null || getTarget365Sender().isEmpty()) {
            log.warn("Target365 sender not configured");
        }
    }
    
    // Getters
    
    public String getActiveProvider() {
        String provider = getProperty(ACTIVE_PROVIDER_KEY);
        return provider != null ? provider.trim() : "puzzel";
    }
    
    // Puzzel getters
    
    public String getPuzzelServiceUrl() {
        return getProperty(PUZZEL_SERVICE_URL_KEY);
    }
    
    public String getPuzzelServiceAccount() {
        return getProperty(PUZZEL_SERVICE_ACCOUNT_KEY);
    }
    
    public String getPuzzelUsername() {
        return getProperty(PUZZEL_USERNAME_KEY);
    }
    
    public String getPuzzelPassword() {
        return getProperty(PUZZEL_PASSWORD_KEY);
    }
    
    public String getPuzzelQueryParams() {
        return getProperty(PUZZEL_QUERY_PARAMS_KEY);
    }
    
    // Target365 getters
    
    public String getTarget365ServiceUrl() {
        return getProperty(TARGET365_SERVICE_URL_KEY);
    }
    
    public String getTarget365ApiKey() {
        return getProperty(TARGET365_API_KEY);
    }
    
    public String getTarget365Sender() {
        return getProperty(TARGET365_SENDER_KEY);
    }
    
    public String getTarget365DefaultTag() {
        String tag = getProperty(TARGET365_TAG_KEY);
        return tag != null ? tag : "sso";
    }
}