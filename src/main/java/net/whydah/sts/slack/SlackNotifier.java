package net.whydah.sts.slack;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.exoreaction.notification.helper.SlackNotificationHelper;
import com.exoreaction.notification.helper.SlackNotificationHelperFactory;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Convenient wrapper for Slack notifications.
 * Provides simplified methods for common notification scenarios.
 */
@Singleton
public class SlackNotifier {
    
    private static final Logger log = LoggerFactory.getLogger(SlackNotifier.class);
    
    private final SlackNotificationHelperFactory slackFactory;
    private final boolean isEnabled;
    
    /**
     * Constructor for HK2 dependency injection.
     * 
     * @param slackFactory The factory for creating notification helpers
     */
    @Inject
    public SlackNotifier(SlackNotificationHelperFactory slackFactory) {
        this.slackFactory = slackFactory;
        this.isEnabled = slackFactory != null;
        
        if (isEnabled) {
            log.info("SlackNotifier initialized with active Slack service");
        } else {
            log.info("SlackNotifier initialized in no-op mode (Slack disabled)");
        }
    }
    
    // ============================================================================
    // INFO NOTIFICATIONS
    // ============================================================================
    
    /**
     * Send an info notification with custom message and context.
     * 
     * @param channel The target Slack channel name or ID
     * @param message The notification message
     * @param contexts Additional context key-value pairs
     */
    public void sendToChannel(String channel, String message, Map<String, Object> contexts) {
        if (!isAvailable()) {
            log.debug("Slack disabled - Info message not sent: {}", message);
            return;
        }
        
        try {
            SlackNotificationHelper helper = slackFactory.create()
                .message(message)
                .asInfo()
                .toChannel(channel);
            
            if (contexts != null && !contexts.isEmpty()) {
                helper.addContext(contexts);
            }
            
            helper.send();
        } catch (Exception e) {
            log.error("Failed to send info notification to channel {}: {}", channel, e.getMessage(), e);
        }
    }
    
    /**
     * Send an info notification with custom message only.
     * 
     * @param channel The target Slack channel name or ID
     * @param message The notification message
     */
    public void sendToChannel(String channel, String message) {
    	sendToChannel(channel, message, null);
    }
    
    // ============================================================================
    // CUSTOM CHANNEL NOTIFICATIONS
    // ============================================================================
    
    /**
     * Send a notification to a specific channel with success/info type.
     * 
     * @param channel Target channel
     * @param message Message content
     * @param contexts Context map
     * @param isSuccess Whether this is a success notification
     */
    public void sendToChannel(String channel, String message, Map<String, Object> contexts, boolean isSuccess) {
        if (!isAvailable()) {
            log.debug("Slack disabled - Channel message not sent: {}", message);
            return;
        }
        
        try {
            SlackNotificationHelper helper = slackFactory.create()
                .message(message)
                .toChannel(channel);
            
            if (contexts != null && !contexts.isEmpty()) {
                helper.addContext(contexts);
            }
            
            if (isSuccess) {
                helper.asSuccess();
            } else {
                helper.asInfo();
            }
            
            helper.send();
        } catch (Exception e) {
            log.error("Failed to send notification to channel {}: {}", channel, e.getMessage(), e);
        }
    }
    
    // ============================================================================
    // ALARM NOTIFICATIONS
    // ============================================================================
    
    /**
     * Send an alarm (critical) notification with context.
     * 
     * @param message The alarm message
     * @param contexts Additional context
     */
    public void sendAlarm(String message, Map<String, Object> contexts) {
        if (!isAvailable()) {
            log.warn("Slack disabled - ALARM not sent: {}", message);
            return;
        }
        
        try {
            SlackNotificationHelper helper = slackFactory.create()
                .message(message)
                .asAlarm();
            
            if (contexts != null && !contexts.isEmpty()) {
                helper.addContext(contexts);
            }
            
            helper.captureLocation().send();
        } catch (Exception e) {
            log.error("Failed to send alarm notification: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Send an alarm notification without context.
     * 
     * @param message The alarm message
     */
    public void sendAlarm(String message) {
        sendAlarm(message, null);
    }
    
    // ============================================================================
    // EXCEPTION HANDLING
    // ============================================================================
    
    /**
     * Handle exception with method name and additional contexts.
     * 
     * @param e The exception
     * @param methodName The method where exception occurred
     * @param additionalContexts Additional context key-value pairs
     */
    public void handleException(Throwable e, String methodName, Map<String, Object> additionalContexts) {
        log.error("Unexpected error in {}: {}", methodName, e.getMessage(), e);
        
        if (!isAvailable()) {
            return;
        }
        
        try {
            SlackNotificationHelper helper = slackFactory.create()
                .message("Unexpected error")
                .exception(e)
                .captureLocation();
            
            if (methodName != null && !methodName.isEmpty()) {
                helper.addContext("method", methodName);
            }
            
            if (additionalContexts != null && !additionalContexts.isEmpty()) {
                helper.addContext(additionalContexts);
            }
            
            helper.send();
        } catch (Exception ex) {
            log.error("Failed to send exception notification: {}", ex.getMessage(), ex);
        }
    }
    
    /**
     * Handle exception with custom message and contexts.
     * 
     * @param e The exception
     * @param methodName The method where exception occurred
     * @param message Custom error message
     * @param contexts Additional context information
     */
    public void handleException(Throwable e, String methodName, String message, Map<String, Object> contexts) {
        log.error("{} in {}: {}", message, methodName, e.getMessage(), e);
        
        if (!isAvailable()) {
            return;
        }
        
        try {
            SlackNotificationHelper helper = slackFactory.create()
                .message(message)
                .exception(e)
                .captureLocation();
            
            if (methodName != null && !methodName.isEmpty()) {
                helper.addContext("method", methodName);
            }
            
            if (contexts != null && !contexts.isEmpty()) {
                helper.addContext(contexts);
            }
            
            helper.send();
        } catch (Exception ex) {
            log.error("Failed to send exception notification: {}", ex.getMessage(), ex);
        }
    }
    
    /**
     * Handle exception with method name and custom message.
     * 
     * @param e The exception
     * @param methodName The method where exception occurred
     * @param message Custom error message
     */
    public void handleException(Throwable e, String methodName, String message) {
        handleException(e, methodName, message, null);
    }
    
    /**
     * Handle exception with method name only.
     * 
     * @param e The exception
     * @param methodName The method where exception occurred
     */
    public void handleException(Throwable e, String methodName) {
        handleException(e, methodName, "Unexpected error occurred", null);
    }
    
    /**
     * Handle exception with minimal information.
     * 
     * @param e The exception
     */
    public void handleException(Throwable e) {
        handleException(e, null, "Unexpected error occurred", null);
    }
    
    /**
     * Handle exception as WARNING instead of ERROR.
     * Useful for non-critical errors.
     * 
     * @param e The exception
     * @param methodName The method where exception occurred
     * @param message Custom message
     * @param contexts Additional context
     */
    public void handleExceptionAsWarning(Throwable e, String methodName, String message, Map<String, Object> contexts) {
        log.warn("{} in {}: {}", message, methodName, e.getMessage(), e);
        
        if (!isAvailable()) {
            return;
        }
        
        try {
            SlackNotificationHelper helper = slackFactory.create()
                .message(message)
                .exception(e)
                .asWarning()
                .captureLocation();
            
            if (methodName != null && !methodName.isEmpty()) {
                helper.addContext("method", methodName);
            }
            
            if (contexts != null && !contexts.isEmpty()) {
                helper.addContext(contexts);
            }
            
            helper.send();
        } catch (Exception ex) {
            log.error("Failed to send warning notification: {}", ex.getMessage(), ex);
        }
    }
    
    /**
     * Check if Slack notifications are available and enabled.
     * 
     * @return true if SlackNotificationHelperFactory is initialized and enabled
     */
    public boolean isAvailable() {
        return isEnabled && slackFactory != null;
    }
}