package net.whydah.sts.slack;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reusable wrapper for sending application lifecycle notifications to Slack.
 * Handles startup success, startup failure, and shutdown notifications.
 */
public class AppLifecycleNotifier {
    
    private static final Logger log = LoggerFactory.getLogger(AppLifecycleNotifier.class);
    
    private final String serviceName;
    private final String version;
    private final String applicationMode;
    private final int port;
    private final String contextPath;
    private final Map<String, Object> additionalContext;
    
    private AppLifecycleNotifier(Builder builder) {
        this.serviceName = builder.serviceName;
        this.version = builder.version;
        this.applicationMode = builder.applicationMode;
        this.port = builder.port;
        this.contextPath = builder.contextPath;
        this.additionalContext = builder.additionalContext;
    }
    
    /**
     * Send notification when service starts successfully.
     * Sends to "info" channel with SUCCESS status.
     */
    public void notifyStartupSuccess() {
        try {
            Map<String, Object> context = buildBaseContext();
            context.put("healthEndpoint", buildHealthEndpoint());
            context.put("statusEndpoint", buildStatusEndpoint());
            
            String message = String.format("%s is back in service", serviceName);
            
            SlackNotifications.sendToChannel(
                "info", 
                message, 
                context, 
                true  // isSuccess = true
            );
            
            log.info("Sent startup success notification to Slack for {}", serviceName);
            
        } catch (Exception e) {
            log.warn("Failed to send startup notification to Slack: {}", e.getMessage());
        }
    }
    
    /**
     * Send notification when service fails to start.
     * Sends as ALARM with exception details.
     * 
     * @param error The exception that caused the startup failure
     */
    public void notifyStartupFailure(Throwable error) {
        try {
            Map<String, Object> context = buildBaseContext();
            context.put("errorType", error.getClass().getSimpleName());
            context.put("errorMessage", error.getMessage());
            
            String message = String.format("%s failed to start", serviceName);
            
            SlackNotifications.handleException(
                error, 
                "Application Startup", 
                message, 
                context
            );
            
            log.info("Sent startup failure notification to Slack for {}", serviceName);
            
            // Give Slack a moment to send the notification before the app exits
            waitForNotificationDelivery(2000);
            
        } catch (Exception e) {
            log.error("Failed to send startup failure notification to Slack: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Send notification when service is shutting down.
     * Sends as ALARM to alert operations team.
     */
    public void notifyShutdown() {
        try {
            Map<String, Object> context = buildBaseContext();
            context.put("shutdownTime", System.currentTimeMillis());
            context.put("shutdownReason", "Normal shutdown");
            
            String message = String.format("%s is shutting down", serviceName);
            
            SlackNotifications.sendAlarm(message, context);
            
            log.info("Sent shutdown notification to Slack for {}", serviceName);
            
            // Give Slack a moment to send the notification before shutdown completes
            waitForNotificationDelivery(1000);
            
        } catch (Exception e) {
            log.warn("Failed to send shutdown notification to Slack: {}", e.getMessage());
        }
    }
    
    /**
     * Send notification when service is shutting down with a specific reason.
     * 
     * @param reason The reason for shutdown (e.g., "Configuration reload", "Crash detected")
     */
    public void notifyShutdown(String reason) {
        try {
            Map<String, Object> context = buildBaseContext();
            context.put("shutdownTime", System.currentTimeMillis());
            context.put("shutdownReason", reason);
            
            String message = String.format("%s is shutting down: %s", serviceName, reason);
            
            SlackNotifications.sendAlarm(message, context);
            
            log.info("Sent shutdown notification to Slack for {} (reason: {})", serviceName, reason);
            
            waitForNotificationDelivery(1000);
            
        } catch (Exception e) {
            log.warn("Failed to send shutdown notification to Slack: {}", e.getMessage());
        }
    }
    
    /**
     * Send a custom lifecycle notification with specific message and context.
     * 
     * @param message Custom message
     * @param additionalContext Additional context to include
     * @param isSuccess Whether this is a success notification
     */
    public void notifyCustom(String message, Map<String, Object> additionalContext, boolean isSuccess) {
        try {
            Map<String, Object> context = buildBaseContext();
            if (additionalContext != null) {
                context.putAll(additionalContext);
            }
            
            SlackNotifications.sendToChannel(
                "info", 
                message, 
                context, 
                isSuccess
            );
            
            log.info("Sent custom notification to Slack: {}", message);
            
        } catch (Exception e) {
            log.warn("Failed to send custom notification to Slack: {}", e.getMessage());
        }
    }
    
    /**
     * Build base context with service information.
     */
    private Map<String, Object> buildBaseContext() {
        Map<String, Object> context = new HashMap<>();
        context.put("service", serviceName);
        context.put("version", version);
        context.put("mode", applicationMode);
        context.put("port", port);
        context.put("contextPath", contextPath);
        
        // Add any additional context provided during construction
        if (additionalContext != null && !additionalContext.isEmpty()) {
            context.putAll(additionalContext);
        }
        
        return context;
    }
    
    /**
     * Build health endpoint URL.
     */
    private String buildHealthEndpoint() {
        return String.format("http://localhost:%d%s/health", port, contextPath);
    }
    
    /**
     * Build status endpoint URL.
     */
    private String buildStatusEndpoint() {
        return String.format("http://localhost:%d%s/", port, contextPath);
    }
    
    /**
     * Wait for notification delivery before proceeding.
     * This is important for shutdown scenarios to ensure the notification is sent.
     */
    private void waitForNotificationDelivery(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Interrupted while waiting for notification delivery");
        }
    }
    
    /**
     * Create a new builder instance.
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Builder for AppLifecycleNotifier.
     */
    public static class Builder {
        private String serviceName = "Application";
        private String version = "unknown";
        private String applicationMode = "unknown";
        private int port = 0;
        private String contextPath = "";
        private Map<String, Object> additionalContext = new HashMap<>();
        
        /**
         * Set the service name (e.g., "STS", "UserAdminService").
         */
        public Builder serviceName(String serviceName) {
            this.serviceName = serviceName;
            return this;
        }
        
        /**
         * Set the application version.
         */
        public Builder version(String version) {
            this.version = version != null ? version : "unknown";
            return this;
        }
        
        /**
         * Set the application mode (e.g., "DEV", "PROD", "TEST").
         */
        public Builder applicationMode(String applicationMode) {
            this.applicationMode = applicationMode;
            return this;
        }
        
        /**
         * Set the service port.
         */
        public Builder port(int port) {
            this.port = port;
            return this;
        }
        
        /**
         * Set the context path (e.g., "/tokenservice").
         */
        public Builder contextPath(String contextPath) {
            this.contextPath = contextPath;
            return this;
        }
        
        /**
         * Add additional context that will be included in all notifications.
         */
        public Builder addContext(String key, Object value) {
            this.additionalContext.put(key, value);
            return this;
        }
        
        /**
         * Add multiple context entries.
         */
        public Builder addContext(Map<String, Object> context) {
            if (context != null) {
                this.additionalContext.putAll(context);
            }
            return this;
        }
        
        /**
         * Build the AppLifecycleNotifier instance.
         */
        public AppLifecycleNotifier build() {
            return new AppLifecycleNotifier(this);
        }
    }
}