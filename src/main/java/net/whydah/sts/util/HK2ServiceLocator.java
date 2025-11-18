package net.whydah.sts.util;

import org.glassfish.hk2.api.ServiceLocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service locator for accessing HK2 managed services in static contexts.
 * This is a workaround for static utility classes that need dependency injection.
 */
public class HK2ServiceLocator {
    
    private static final Logger log = LoggerFactory.getLogger(HK2ServiceLocator.class);
    private static ServiceLocator serviceLocator;
    
    /**
     * Initialize the service locator. Should be called once during application startup.
     */
    public static void initialize(ServiceLocator locator) {
        if (serviceLocator != null) {
            log.warn("ServiceLocator already initialized. Ignoring duplicate initialization.");
            return;
        }
        serviceLocator = locator;
        log.info("HK2 ServiceLocator initialized");
    }
    
    /**
     * Get a service instance by class.
     * 
     * @param serviceClass The service class
     * @return Service instance or null if not found
     */
    public static <T> T getService(Class<T> serviceClass) {
        if (serviceLocator == null) {
            log.error("ServiceLocator not initialized. Cannot retrieve service: {}", 
                    serviceClass.getSimpleName());
            return null;
        }
        
        try {
            return serviceLocator.getService(serviceClass);
        } catch (Exception e) {
            log.error("Failed to retrieve service: {}", serviceClass.getSimpleName(), e);
            return null;
        }
    }
    
    /**
     * Check if service locator is initialized.
     */
    public static boolean isInitialized() {
        return serviceLocator != null;
    }
}