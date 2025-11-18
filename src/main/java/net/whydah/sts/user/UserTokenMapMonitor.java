package net.whydah.sts.user;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hazelcast.core.HazelcastInstance;

import net.whydah.sts.config.AppConfig;
import net.whydah.sts.slack.SlackNotifications;

/**
 * Background monitor for tracking UserToken map size changes.
 * Only runs on the Hazelcast master node to avoid duplicate notifications.
 * Only sends notifications when Slack is enabled and available.
 */
public class UserTokenMapMonitor {
    
    private static final Logger log = LoggerFactory.getLogger(UserTokenMapMonitor.class);
    
    private final HazelcastInstance hazelcastInstance;
    private final Map<String, ?> activeUserTokensMap;
    private final ScheduledExecutorService scheduler;
    
    private final AtomicInteger previousMapSize = new AtomicInteger(0);
    private final AtomicInteger currentMapSize = new AtomicInteger(0);
    private final AtomicInteger consecutiveUnchangedChecks = new AtomicInteger(0);
    
    private final long checkIntervalMinutes;
    private final long reportThresholdMinutes;
    private final int sizeChangeThreshold;
    private final boolean enableNotifications;
    
    private volatile boolean running = false;
    
    /**
     * Create a new UserTokenMapMonitor.
     * 
     * @param hazelcastInstance The Hazelcast instance
     * @param activeUserTokensMap The map to monitor
     */
    public UserTokenMapMonitor(HazelcastInstance hazelcastInstance, 
                              Map<String, ?> activeUserTokensMap) {
        this.hazelcastInstance = hazelcastInstance;
        this.activeUserTokensMap = activeUserTokensMap;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "UserTokenMapMonitor");
            t.setDaemon(true);
            return t;
        });
        
        // Load configuration
        AppConfig appConfig = new AppConfig();
        this.checkIntervalMinutes = getLongProperty(appConfig, 
                "usertoken.monitor.interval.minutes", 5L);
        this.reportThresholdMinutes = getLongProperty(appConfig, 
                "usertoken.monitor.report.threshold.minutes", 15L);
        this.sizeChangeThreshold = getIntProperty(appConfig, 
                "usertoken.monitor.size.change.threshold", 1);
        this.enableNotifications = getBooleanProperty(appConfig, 
                "usertoken.monitor.notifications.enabled", true);
        
        log.info("UserTokenMapMonitor configured: checkInterval={}min, reportThreshold={}min, " +
                "sizeChangeThreshold={}, notificationsEnabled={}", 
                checkIntervalMinutes, reportThresholdMinutes, sizeChangeThreshold, enableNotifications);
    }
    
    /**
     * Start the monitor.
     */
    public void start() {
        if (running) {
            log.warn("UserTokenMapMonitor already running");
            return;
        }
        
        running = true;
        previousMapSize.set(activeUserTokensMap.size());
        currentMapSize.set(activeUserTokensMap.size());
        
        log.info("Starting UserTokenMapMonitor with initial map size: {}", currentMapSize.get());
        
        // Schedule periodic checks
        scheduler.scheduleAtFixedRate(
            this::checkMapSize,
            checkIntervalMinutes,
            checkIntervalMinutes,
            TimeUnit.MINUTES
        );
    }
    
    /**
     * Stop the monitor.
     */
    public void stop() {
        if (!running) {
            return;
        }
        
        log.info("Stopping UserTokenMapMonitor");
        running = false;
        scheduler.shutdown();
        
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        log.info("UserTokenMapMonitor stopped");
    }
    
    /**
     * Check if this node is the Hazelcast master (oldest member).
     */
    private boolean isMasterNode() {
        try {
            if (hazelcastInstance == null || !hazelcastInstance.getLifecycleService().isRunning()) {
                log.debug("Hazelcast instance is not running");
                return false;
            }
            
            var cluster = hazelcastInstance.getCluster();
            var localMember = cluster.getLocalMember();
            var members = cluster.getMembers();
            
            if (members.isEmpty()) {
                return false;
            }
            
            // The oldest member (first in the list) is considered the master
            var oldestMember = members.iterator().next();
            boolean isMaster = localMember.getUuid().equals(oldestMember.getUuid());
            
            if (isMaster) {
                log.trace("This node is the Hazelcast master");
            } else {
                log.trace("This node is NOT the master. Master UUID: {}, Local UUID: {}", 
                        oldestMember.getUuid(), localMember.getUuid());
            }
            
            return isMaster;
            
        } catch (Exception e) {
            log.error("Error checking if master node: {}", e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Check if Slack notifications are available and enabled.
     */
    private boolean isSlackAvailable() {
        return SlackNotifications.isAvailable();
    }
    
    /**
     * Periodic check of map size.
     */
    private void checkMapSize() {
        try {
            // Only run on master node
            if (!isMasterNode()) {
                log.trace("Skipping map size check - not master node");
                return;
            }
            
            // Get current size
            int newSize = activeUserTokensMap.size();
            int oldSize = currentMapSize.get();
            int sizeDifference = newSize - oldSize;
            
            log.debug("UserToken map size check: previous={}, current={}, difference={}", 
                    oldSize, newSize, sizeDifference);
            
            // Update current size
            currentMapSize.set(newSize);
            
            // Check if size changed
            if (Math.abs(sizeDifference) >= sizeChangeThreshold) {
                // Size changed - reset counter and potentially notify
                consecutiveUnchangedChecks.set(0);
                previousMapSize.set(oldSize);
                
                reportSizeChange(oldSize, newSize, sizeDifference);
                
            } else {
                // Size unchanged - increment counter
                int unchangedCount = consecutiveUnchangedChecks.incrementAndGet();
                long minutesSinceLastChange = unchangedCount * checkIntervalMinutes;
                
                log.debug("Map size unchanged for {} consecutive checks ({} minutes)", 
                        unchangedCount, minutesSinceLastChange);
                
                // Report if threshold reached
                if (minutesSinceLastChange >= reportThresholdMinutes && 
                    minutesSinceLastChange % reportThresholdMinutes == 0) {
                    reportNoChange(newSize, minutesSinceLastChange);
                }
            }
            
        } catch (Exception e) {
            log.error("Error in UserTokenMapMonitor check: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Report map size change to Slack.
     */
    private void reportSizeChange(int oldSize, int newSize, int difference) {
        // Check if notifications are enabled
        if (!enableNotifications) {
            log.debug("Notifications disabled in configuration - skipping report");
            return;
        }
        
        // Check if Slack is available
        if (!isSlackAvailable()) {
            log.debug("Slack not available - skipping notification. " +
                     "Size changed: {} -> {} ({})", oldSize, newSize, difference);
            return;
        }
        
        try {
            Map<String, Object> context = new HashMap<>();
            context.put("previousSize", oldSize);
            context.put("currentSize", newSize);
            context.put("difference", difference);
            context.put("changePercentage", calculatePercentageChange(oldSize, newSize));
            context.put("clusterMembers", AuthenticatedUserTokenRepository.getNoOfClusterMembers());
            context.put("usernameMapSize", AuthenticatedUserTokenRepository.getActiveUsernameMapSize());
            
            String message;
            boolean isSuccess;
            
            if (difference > 0) {
                message = String.format("Active user sessions increased by %d (%.1f%%)", 
                        difference, calculatePercentageChange(oldSize, newSize));
                isSuccess = true;
            } else {
                message = String.format("Active user sessions decreased by %d (%.1f%%)", 
                        Math.abs(difference), calculatePercentageChange(oldSize, newSize));
                isSuccess = false;
            }
            
            SlackNotifications.sendToChannel("info", message, context, isSuccess);
            
            log.info("Reported map size change to Slack: {} -> {}", oldSize, newSize);
            
        } catch (Exception e) {
            log.error("Failed to report size change to Slack: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Report that map size hasn't changed for a while.
     */
    private void reportNoChange(int currentSize, long minutesSinceLastChange) {
        // Check if notifications are enabled
        if (!enableNotifications) {
            log.debug("Notifications disabled in configuration - skipping report");
            return;
        }
        
        // Check if Slack is available
        if (!isSlackAvailable()) {
            log.debug("Slack not available - skipping stability notification. " +
                     "Size stable at {} for {} minutes", currentSize, minutesSinceLastChange);
            return;
        }
        
        try {
            Map<String, Object> context = new HashMap<>();
            context.put("currentSize", currentSize);
            context.put("minutesSinceLastChange", minutesSinceLastChange);
            context.put("checkIntervalMinutes", checkIntervalMinutes);
            context.put("clusterMembers", AuthenticatedUserTokenRepository.getNoOfClusterMembers());
            
            String message = String.format(
                    "Active user sessions stable at %d for the last %d minutes", 
                    currentSize, minutesSinceLastChange);
            
            SlackNotifications.sendToChannel("info", message, context);
            
            log.info("Reported stable map size to Slack: size={}, stable for {} minutes", 
                    currentSize, minutesSinceLastChange);
            
        } catch (Exception e) {
            log.error("Failed to report stable size to Slack: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Calculate percentage change between two values.
     */
    private double calculatePercentageChange(int oldValue, int newValue) {
        if (oldValue == 0) {
            return newValue > 0 ? 100.0 : 0.0;
        }
        return ((double) (newValue - oldValue) / oldValue) * 100.0;
    }
    
    /**
     * Get current map size (for testing/monitoring).
     */
    public int getCurrentMapSize() {
        return currentMapSize.get();
    }
    
    /**
     * Get previous map size (for testing/monitoring).
     */
    public int getPreviousMapSize() {
        return previousMapSize.get();
    }
    
    /**
     * Check if monitor is running.
     */
    public boolean isRunning() {
        return running;
    }
    
    // ============================================================================
    // Configuration Helper Methods
    // ============================================================================
    
    private long getLongProperty(AppConfig config, String key, long defaultValue) {
        try {
            String value = config.getProperty(key);
            return value != null ? Long.parseLong(value) : defaultValue;
        } catch (NumberFormatException e) {
            log.warn("Invalid long property {}, using default: {}", key, defaultValue);
            return defaultValue;
        }
    }
    
    private int getIntProperty(AppConfig config, String key, int defaultValue) {
        try {
            String value = config.getProperty(key);
            return value != null ? Integer.parseInt(value) : defaultValue;
        } catch (NumberFormatException e) {
            log.warn("Invalid int property {}, using default: {}", key, defaultValue);
            return defaultValue;
        }
    }
    
    private boolean getBooleanProperty(AppConfig config, String key, boolean defaultValue) {
        try {
            String value = config.getProperty(key);
            return value != null ? Boolean.parseBoolean(value) : defaultValue;
        } catch (Exception e) {
            log.warn("Invalid boolean property {}, using default: {}", key, defaultValue);
            return defaultValue;
        }
    }
}