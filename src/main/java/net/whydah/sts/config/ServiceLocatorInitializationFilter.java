package net.whydah.sts.config;

import org.glassfish.hk2.api.ServiceLocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;
import net.whydah.sts.util.HK2ServiceLocator;

/**
 * Request filter that captures the ServiceLocator on first request.
 */
@Provider
public class ServiceLocatorInitializationFilter implements ContainerRequestFilter {
    
    private static final Logger log = LoggerFactory.getLogger(ServiceLocatorInitializationFilter.class);
    private static boolean initialized = false;
    
    @jakarta.inject.Inject
    private ServiceLocator serviceLocator;
    
    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (!initialized && serviceLocator != null) {
            synchronized (ServiceLocatorInitializationFilter.class) {
                if (!initialized) {
                    HK2ServiceLocator.initialize(serviceLocator);
                    initialized = true;
                    log.info("ServiceLocator initialized via request filter");
                }
            }
        }
    }
}