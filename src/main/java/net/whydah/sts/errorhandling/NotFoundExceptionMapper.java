package net.whydah.sts.errorhandling;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.exoreaction.notification.SlackNotificationFacade;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import net.whydah.sts.config.AppConfig;

@Provider
@Produces(MediaType.APPLICATION_JSON)
public class NotFoundExceptionMapper implements ExceptionMapper<NotFoundException> {

    private static final Logger log = LoggerFactory.getLogger(NotFoundExceptionMapper.class);

    @Context
    private UriInfo uriInfo;
    
    @Context
    private ResourceInfo resourceInfo;
    
    private Set<String> ignoredResourcesCache;
    
    @Override
    public Response toResponse(NotFoundException ex) {
        
        // Get the requested path
        String requestedPath = getRequestedPath(ex);
        
        log.debug("Resource not found: {}", requestedPath);
        
        boolean shouldIgnore = shouldIgnoreResource(requestedPath);
        
        if (!shouldIgnore && SlackNotificationFacade.isAvailable()) {
            Map<String, Object> context = new HashMap<>();
            context.put("requestedPath", requestedPath);
            context.put("method", uriInfo != null ? uriInfo.getRequestUri().toString() : "unknown");
            
            SlackNotificationFacade.handleExceptionAsWarning(
                ex, 
                "NotFoundExceptionMapper", 
                "Resource not found: " + requestedPath, 
                context
            );
        } else if (shouldIgnore) {
            log.trace("Ignored 404 for resource: {}", requestedPath);
        }
        
        return Response.status(Response.Status.NOT_FOUND)
                .entity(ExceptionConfig.handleSecurity(new ErrorMessage(ex)).toString())
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
    
    /**
     * Extract the requested path from the exception and URI info.
     * 
     * @param ex The NotFoundException
     * @return The requested path
     */
    private String getRequestedPath(NotFoundException ex) {
        // Try to get from UriInfo first (most reliable)
        if (uriInfo != null) {
            String path = uriInfo.getPath();
            if (path != null && !path.isEmpty()) {
                return path;
            }
            
            // Try full URI
            String fullUri = uriInfo.getRequestUri().toString();
            if (fullUri != null && !fullUri.isEmpty()) {
                return fullUri;
            }
        }
        
        // Fallback to exception message
        String message = ex.getMessage();
        if (message != null && !message.isEmpty()) {
            // JAX-RS NotFoundException message usually contains "HTTP 404 Not Found"
            // We might need to parse it
            return parsePathFromMessage(message);
        }
        
        return "unknown";
    }
    
    /**
     * Parse the path from exception message.
     * JAX-RS NotFoundException message format can vary.
     * 
     * @param message The exception message
     * @return Parsed path or the original message
     */
    private String parsePathFromMessage(String message) {
        // Common formats:
        // "HTTP 404 Not Found"
        // "Not Found"
        // Sometimes includes the path
        
        if (message.contains("HTTP 404")) {
            // Extract path if included after "HTTP 404 Not Found"
            String[] parts = message.split("HTTP 404 Not Found");
            if (parts.length > 1) {
                return parts[1].trim();
            }
        }
        
        return message;
    }
    
    /**
     * Check if the resource should be ignored based on configuration.
     * 
     * @param resourcePath The requested resource path
     * @return true if should be ignored
     */
    private boolean shouldIgnoreResource(String resourcePath) {
        if (resourcePath == null || resourcePath.trim().isEmpty()) {
            return false;
        }
        
        Set<String> ignoredResources = getIgnoredResources();
        
        // Normalize path for comparison
        String normalizedPath = resourcePath.toLowerCase().trim();
        
        // Remove leading slash if present
        if (normalizedPath.startsWith("/")) {
            normalizedPath = normalizedPath.substring(1);
        }
        
        // Check if resource path contains any of the ignored patterns
        for (String ignoredPattern : ignoredResources) {
            if (normalizedPath.contains(ignoredPattern.toLowerCase())) {
                return true;
            }
            
            // Check if it ends with the pattern (for file extensions)
            if (normalizedPath.endsWith(ignoredPattern.toLowerCase())) {
                return true;
            }
            
            // Check if it starts with the pattern (for directories)
            if (normalizedPath.startsWith(ignoredPattern.toLowerCase())) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * Get the set of ignored resources from configuration.
     * Cached after first load.
     * 
     * @return Set of resource patterns to ignore
     */
    private Set<String> getIgnoredResources() {
        if (ignoredResourcesCache == null) {
            synchronized (this) {
                if (ignoredResourcesCache == null) {
                    ignoredResourcesCache = new HashSet<>();
                    String ignoredResourcesConfig = AppConfig.getProperty("app.slack.ignore-resources");
                    
                    if (ignoredResourcesConfig != null && !ignoredResourcesConfig.trim().isEmpty()) {
                        ignoredResourcesCache = Arrays.stream(ignoredResourcesConfig.split(","))
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .collect(Collectors.toSet());
                        
                        log.info("Loaded {} ignored resource patterns for 404 notifications", 
                                ignoredResourcesCache.size());
                        log.debug("Ignored patterns: {}", ignoredResourcesCache);
                    } else {
                        log.warn("No ignored resources configured for 404 notifications");
                    }
                }
            }
        }
        return ignoredResourcesCache;
    }
}