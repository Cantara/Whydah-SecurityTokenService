package net.whydah.sts.smsgw;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.exoreaction.notification.SlackNotificationFacade;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;

import net.whydah.sts.config.AppConfig;

/**
 * Monitor for SMS delivery reports with Hazelcast distributed map support.
 * Aggregates success/failure counts across cluster and sends periodic summaries to Slack.
 * Only the node with sms.monitor.notifications.enabled=true will run the reporting loop.
 */
public class SMSDeliveryMonitor {
    
    private static final Logger log = LoggerFactory.getLogger(SMSDeliveryMonitor.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    
    // Hazelcast distributed maps
    private final IMap<String, Long> successCountMap;
    private final IMap<String, FailedDelivery> failedDeliveriesMap;
    
    private final HazelcastInstance hazelcastInstance;
    private final ScheduledExecutorService scheduler;
    private final long reportIntervalMinutes;
    private final boolean enableReporting; // Only true on the designated reporting node
    
    private volatile boolean running = false;
    private volatile LocalDateTime lastReportTime;
    
    private static final String SUCCESS_COUNT_KEY = "sms_success_count";
    private static final String MAP_PREFIX = "sms_delivery_";
    
    /**
     * Represents a failed SMS delivery (stored in Hazelcast).
     */
    public static class FailedDelivery implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        
        public final String recipient;
        public final String statusCode;
        public final String detailedStatusCode;
        public final String transactionId;
        public final long timestampMillis;
        
        public FailedDelivery(String recipient, String statusCode, String detailedStatusCode, 
                             String transactionId) {
            this.recipient = recipient;
            this.statusCode = statusCode;
            this.detailedStatusCode = detailedStatusCode;
            this.transactionId = transactionId;
            this.timestampMillis = System.currentTimeMillis();
        }
        
        public LocalDateTime getTimestamp() {
            return LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(timestampMillis),
                java.time.ZoneId.systemDefault()
            );
        }
    }
    
    public SMSDeliveryMonitor(HazelcastInstance hazelcastInstance, String gridPrefix) {
        this.hazelcastInstance = hazelcastInstance;
        
        // Initialize Hazelcast distributed maps
        this.successCountMap = hazelcastInstance.getMap(gridPrefix + MAP_PREFIX + "success_count");
        this.failedDeliveriesMap = hazelcastInstance.getMap(gridPrefix + MAP_PREFIX + "failures");
        
        log.info("Connected to Hazelcast maps: {}{} (success count), {}{} (failures)",
                gridPrefix, MAP_PREFIX + "success_count",
                gridPrefix, MAP_PREFIX + "failures");
        
        // Initialize success counter if not exists
        successCountMap.putIfAbsent(SUCCESS_COUNT_KEY, 0L);
        
        // Load configuration
        AppConfig appConfig = new AppConfig();
        this.reportIntervalMinutes = getLongProperty(appConfig, 
                "sms.monitor.interval.minutes", 5L);
        this.enableReporting = getBooleanProperty(appConfig, 
                "sms.monitor.notifications.enabled", false);
        
        this.lastReportTime = LocalDateTime.now();
        
        if (enableReporting) {
            this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "SMSDeliveryMonitor");
                t.setDaemon(true);
                return t;
            });
            log.info("SMSDeliveryMonitor configured as REPORTING NODE: reportInterval={}min", 
                    reportIntervalMinutes);
        } else {
            this.scheduler = null;
            log.info("SMSDeliveryMonitor configured as DATA NODE ONLY (reporting disabled)");
        }
    }
    
    /**
     * Start the monitor.
     * Only starts the reporting loop if this node is configured for reporting.
     */
    public void start() {
        if (running) {
            log.warn("SMSDeliveryMonitor already running");
            return;
        }
        
        running = true;
        lastReportTime = LocalDateTime.now();
        
        if (enableReporting && scheduler != null) {
            log.info("Starting SMSDeliveryMonitor reporting loop");
            
            // Schedule periodic reporting
            scheduler.scheduleAtFixedRate(
                this::generateReport,
                reportIntervalMinutes,
                reportIntervalMinutes,
                TimeUnit.MINUTES
            );
        } else {
            log.info("SMSDeliveryMonitor started in data-only mode (no reporting)");
        }
    }
    
    /**
     * Stop the monitor.
     */
    public void stop() {
        if (!running) {
            return;
        }
        
        log.info("Stopping SMSDeliveryMonitor");
        
        // Send final report if this is the reporting node and there's data
        if (enableReporting && hasActivity()) {
            generateReport();
        }
        
        running = false;
        
        if (scheduler != null) {
            scheduler.shutdown();
            
            try {
                if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        log.info("SMSDeliveryMonitor stopped");
    }
    
    /**
     * Record a successful SMS delivery (cluster-wide).
     */
    public void recordSuccess(Target365DeliveryReport deliveryReport) {
        try {
            // Atomically increment the success counter in Hazelcast
            successCountMap.lock(SUCCESS_COUNT_KEY);
            try {
                Long currentCount = successCountMap.get(SUCCESS_COUNT_KEY);
                successCountMap.set(SUCCESS_COUNT_KEY, currentCount + 1);
            } finally {
                successCountMap.unlock(SUCCESS_COUNT_KEY);
            }
            
            log.debug("Recorded successful SMS delivery. Current cluster total: {}", 
                    successCountMap.get(SUCCESS_COUNT_KEY));
                    
        } catch (Exception e) {
            log.error("Error recording success to Hazelcast: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Record a failed SMS delivery (cluster-wide).
     */
    public void recordFailure(Target365DeliveryReport deliveryReport) {
        try {
            // Store details of failed delivery in Hazelcast map
            String key = deliveryReport.getTransactionId() + "_" + System.currentTimeMillis();
            FailedDelivery failure = new FailedDelivery(
                deliveryReport.getRecipient(),
                deliveryReport.getStatusCode(),
                deliveryReport.getDetailedStatusCode(),
                deliveryReport.getTransactionId()
            );
            
            failedDeliveriesMap.put(key, failure);
            
            log.debug("Recorded failed SMS delivery. Current cluster failures: {}", 
                    failedDeliveriesMap.size());
                    
        } catch (Exception e) {
            log.error("Error recording failure to Hazelcast: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Check if there has been any SMS activity since last report.
     */
    private boolean hasActivity() {
        try {
            Long successCount = successCountMap.get(SUCCESS_COUNT_KEY);
            int failureCount = failedDeliveriesMap.size();
            return (successCount != null && successCount > 0) || failureCount > 0;
        } catch (Exception e) {
            log.error("Error checking activity: {}", e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Generate and send periodic report (only runs on reporting node).
     */
    private void generateReport() {
        if (!enableReporting) {
            log.debug("Reporting disabled on this node - skipping");
            return;
        }
        
        try {
            // Only report if there's activity
            if (!hasActivity()) {
                log.debug("No SMS activity since last report - skipping");
                return;
            }
            
            // Get and reset success count atomically
            long successes = 0;
            successCountMap.lock(SUCCESS_COUNT_KEY);
            try {
                successes = successCountMap.get(SUCCESS_COUNT_KEY);
                successCountMap.set(SUCCESS_COUNT_KEY, 0L);
            } finally {
                successCountMap.unlock(SUCCESS_COUNT_KEY);
            }
            
            // Get failure count and details
            int failureCount = failedDeliveriesMap.size();
            List<FailedDelivery> failures = new ArrayList<>(failedDeliveriesMap.values());
            
            log.info("SMS Delivery Report (cluster-wide): {} successes, {} failures", 
                    successes, failureCount);
            
            // Send success summary if any
            if (successes > 0) {
                reportSuccesses(successes);
            }
            
            // Send failure details if any
            if (failureCount > 0) {
                reportFailures(failureCount, failures);
            }
            
            // Clear failed deliveries after reporting
            failedDeliveriesMap.clear();
            
            lastReportTime = LocalDateTime.now();
            
        } catch (Exception e) {
            log.error("Error generating SMS delivery report: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Report successful SMS deliveries to Slack.
     */
    private void reportSuccesses(long count) {
        if (!SlackNotificationFacade.isAvailable()) {
            log.debug("Slack not available - skipping success report for {} SMS", count);
            return;
        }
        
        try {
            Map<String, Object> context = new HashMap<>();
            context.put("count", count);
            context.put("period", reportIntervalMinutes + " minutes");
            context.put("timestamp", LocalDateTime.now().format(TIME_FORMATTER));
            context.put("clusterNode", getNodeInfo());
            
            String message = String.format("%d SMS delivered successfully in the last %d minutes (cluster-wide)", 
                    count, reportIntervalMinutes);
            
            SlackNotificationFacade.sendToChannel("info", message, context, true);
            
            log.info("Reported {} successful SMS deliveries to Slack", count);
            
        } catch (Exception e) {
            log.error("Failed to report successes to Slack: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Report failed SMS deliveries to Slack with details.
     */
    private void reportFailures(int count, List<FailedDelivery> failures) {
        if (!SlackNotificationFacade.isAvailable()) {
            log.error("Slack not available - {} SMS delivery failures not reported to Slack", count);
            return;
        }
        
        try {
            Map<String, Object> context = new HashMap<>();
            context.put("failureCount", count);
            context.put("period", reportIntervalMinutes + " minutes");
            context.put("timestamp", LocalDateTime.now().format(TIME_FORMATTER));
            context.put("clusterNode", getNodeInfo());
            
            // Add details of failed deliveries
            List<Map<String, String>> failureDetails = new ArrayList<>();
            for (FailedDelivery failure : failures) {
                Map<String, String> detail = new HashMap<>();
                detail.put("recipient", maskPhoneNumber(failure.recipient));
                detail.put("statusCode", failure.statusCode);
                detail.put("detailedStatusCode", failure.detailedStatusCode);
                detail.put("transactionId", failure.transactionId);
                detail.put("time", failure.getTimestamp().format(TIME_FORMATTER));
                failureDetails.add(detail);
            }
            context.put("failures", formatFailureDetails(failureDetails));
            
            String message = String.format("%d SMS delivery FAILED in the last %d minutes (cluster-wide)", 
                    count, reportIntervalMinutes);
            
            SlackNotificationFacade.sendAlarm(message, context);
            
            log.error("Reported {} failed SMS deliveries to Slack", count);
            
        } catch (Exception e) {
            log.error("Failed to report failures to Slack: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Format failure details for Slack message.
     */
    private String formatFailureDetails(List<Map<String, String>> failures) {
        if (failures.isEmpty()) {
            return "No details available";
        }
        
        StringBuilder sb = new StringBuilder();
        int displayCount = Math.min(failures.size(), 10); // Limit to 10 entries
        
        for (int i = 0; i < displayCount; i++) {
            Map<String, String> failure = failures.get(i);
            sb.append(String.format("\n  • %s - Code: %s/%s (TxID: %s) at %s",
                    failure.get("recipient"),
                    failure.get("statusCode"),
                    failure.get("detailedStatusCode"),
                    failure.get("transactionId"),
                    failure.get("time")));
        }
        
        if (failures.size() > displayCount) {
            sb.append(String.format("\n  ... and %d more", failures.size() - displayCount));
        }
        
        return sb.toString();
    }
    
    /**
     * Mask phone number for privacy (show only last 4 digits).
     */
    private String maskPhoneNumber(String phoneNumber) {
    	
    		//TODO: no, we need to troubleshoot and assist the one who has trouble in receiving the pin 
    		return phoneNumber;
    		/*
    		if (phoneNumber == null || phoneNumber.length() <= 4) {
            return phoneNumber;
        }
        
        int visibleDigits = 4;
        int maskLength = phoneNumber.length() - visibleDigits;
        
        return "*".repeat(maskLength) + phoneNumber.substring(maskLength);
        */
    }
    
    /**
     * Get information about this cluster node.
     */
    private String getNodeInfo() {
        try {
            if (hazelcastInstance != null && hazelcastInstance.getCluster() != null) {
                var localMember = hazelcastInstance.getCluster().getLocalMember();
                return localMember.getAddress().toString();
            }
        } catch (Exception e) {
            log.debug("Error getting node info: {}", e.getMessage());
        }
        return "unknown";
    }
    
    /**
     * Get current statistics (for monitoring/testing).
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        try {
            stats.put("successCount", successCountMap.get(SUCCESS_COUNT_KEY));
            stats.put("failureCount", failedDeliveriesMap.size());
            stats.put("isReportingNode", enableReporting);
            stats.put("successMapSize", successCountMap.size());
            stats.put("failureMapSize", failedDeliveriesMap.size());
        } catch (Exception e) {
            log.error("Error getting statistics: {}", e.getMessage(), e);
        }
        return stats;
    }
    
    /**
     * Check if monitor is running.
     */
    public boolean isRunning() {
        return running;
    }
    
    /**
     * Check if this is the reporting node.
     */
    public boolean isReportingNode() {
        return enableReporting;
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