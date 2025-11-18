package net.whydah.sts.slack;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * No-op implementation of SlackNotifier for when Slack is disabled.
 * All methods log but don't send actual notifications.
 */
public class NoOpSlackNotifier extends SlackNotifier {
    
    private static final Logger log = LoggerFactory.getLogger(NoOpSlackNotifier.class);
    
    public NoOpSlackNotifier() {
        super(null); // Pass null - we override all methods
        log.info("NoOpSlackNotifier initialized - notifications will be logged only");
    }
    
    @Override
    public void sendToChannel(String channel, String message, Map<String, Object> contexts) {
        log.debug("Slack disabled - INFO to {}: {}", channel, message);
    }
    
    @Override
    public void sendToChannel(String channel, String message) {
        log.debug("Slack disabled - INFO to {}: {}", channel, message);
    }
    
    @Override
    public void sendToChannel(String channel, String message, Map<String, Object> contexts, boolean isSuccess) {
        log.debug("Slack disabled - {} to {}: {}", isSuccess ? "SUCCESS" : "INFO", channel, message);
    }
    
    @Override
    public void sendAlarm(String message, Map<String, Object> contexts) {
        log.warn("Slack disabled - ALARM: {}", message);
    }
    
    @Override
    public void sendAlarm(String message) {
        log.warn("Slack disabled - ALARM: {}", message);
    }
    
    @Override
    public void handleException(Throwable e, String methodName, Map<String, Object> additionalContexts) {
        log.error("Slack disabled - Exception in {}: {}", methodName, e.getMessage(), e);
    }
    
    @Override
    public void handleException(Throwable e, String methodName, String message, Map<String, Object> contexts) {
        log.error("Slack disabled - {} in {}: {}", message, methodName, e.getMessage(), e);
    }
    
    @Override
    public void handleException(Throwable e, String methodName, String message) {
        log.error("Slack disabled - {} in {}: {}", message, methodName, e.getMessage(), e);
    }
    
    @Override
    public void handleException(Throwable e, String methodName) {
        log.error("Slack disabled - Exception in {}: {}", methodName, e.getMessage(), e);
    }
    
    @Override
    public void handleException(Throwable e) {
        log.error("Slack disabled - Exception: {}", e.getMessage(), e);
    }
    
    @Override
    public void handleExceptionAsWarning(Throwable e, String methodName, String message, Map<String, Object> contexts) {
        log.warn("Slack disabled - {} in {}: {}", message, methodName, e.getMessage(), e);
    }
    
    @Override
    public boolean isAvailable() {
        return false;
    }
}