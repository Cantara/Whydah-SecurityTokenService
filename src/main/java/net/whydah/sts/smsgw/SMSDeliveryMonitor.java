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
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.exoreaction.notification.SlackNotificationFacade;
import com.hazelcast.config.Config;
import com.hazelcast.config.EvictionPolicy;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.MaxSizePolicy;
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
    private final IMap<String, Integer> failureCountByPhoneMap;
    private final IMap<String, String> persistentFailureAlertSentMap;

    private final HazelcastInstance hazelcastInstance;
    private final ScheduledExecutorService scheduler;
    private final long reportIntervalMinutes;
    private final boolean enableReporting; // Only true on the designated reporting node
    private final int persistentFailureThreshold;

    private volatile boolean running = false;
    private volatile LocalDateTime lastReportTime;
    /** JVM-level dedup for the persistent-offenders summary alarm. Not Hazelcast — survives map eviction/TTL issues. */
    private volatile String lastReportedOffenderSet = null;

    private static final String SUCCESS_COUNT_KEY = "sms_success_count";
    private static final String SUMMARY_OFFENDERS_KEY = "_summary_offenders";
    private static final String MAP_PREFIX = "sms_delivery_";
    private static final int PERSISTENT_FAILURE_ALERT_COOLDOWN_HOURS = 24;
    
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
        
        configureMaps(hazelcastInstance.getConfig(), gridPrefix);

        // Initialize Hazelcast distributed maps
        this.successCountMap = hazelcastInstance.getMap(gridPrefix + MAP_PREFIX + "success_count");
        this.failedDeliveriesMap = hazelcastInstance.getMap(gridPrefix + MAP_PREFIX + "failures");
        this.failureCountByPhoneMap = hazelcastInstance.getMap(gridPrefix + MAP_PREFIX + "failure_count_by_phone");
        this.persistentFailureAlertSentMap = hazelcastInstance.getMap(gridPrefix + MAP_PREFIX + "persistent_alert_sent");

        log.info("Connected to Hazelcast maps: success_count, failures, failure_count_by_phone, persistent_alert_sent");

        // Initialize success counter if not exists
        successCountMap.putIfAbsent(SUCCESS_COUNT_KEY, 0L);

        // Load configuration
        AppConfig appConfig = new AppConfig();
        this.reportIntervalMinutes = getLongProperty(appConfig,
                "sms.monitor.interval.minutes", 5L);
        this.enableReporting = getBooleanProperty(appConfig,
                "sms.monitor.notifications.enabled", false);
        this.persistentFailureThreshold = (int) getLongProperty(appConfig,
                "sms.monitor.persistent.failure.threshold", 3L);
        
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
    
    private void configureMaps(Config config, String gridPrefix) {
        // Configure success count map (TTL = 24 hours)
        MapConfig successConfig = new MapConfig(gridPrefix + MAP_PREFIX + "success_count");
        successConfig.setTimeToLiveSeconds((int) TimeUnit.HOURS.toSeconds(24));
        successConfig.getEvictionConfig()
            .setEvictionPolicy(EvictionPolicy.LRU)
            .setMaxSizePolicy(MaxSizePolicy.PER_NODE)
            .setSize(100); // Small map, just counters
        config.addMapConfig(successConfig);
        
        // Configure failed deliveries map (TTL = 1 hour)
        MapConfig failuresConfig = new MapConfig(gridPrefix + MAP_PREFIX + "failures");
        failuresConfig.setTimeToLiveSeconds((int) TimeUnit.HOURS.toSeconds(1));
        failuresConfig.getEvictionConfig()
            .setEvictionPolicy(EvictionPolicy.LRU)
            .setMaxSizePolicy(MaxSizePolicy.PER_NODE)
            .setSize(10000); // Max 10k failed deliveries per node
        config.addMapConfig(failuresConfig);
        
        // Per-phone failure count — rolling 24h window
        MapConfig failureCountConfig = new MapConfig(gridPrefix + MAP_PREFIX + "failure_count_by_phone");
        failureCountConfig.setTimeToLiveSeconds((int) TimeUnit.HOURS.toSeconds(24));
        failureCountConfig.getEvictionConfig()
            .setEvictionPolicy(EvictionPolicy.LRU)
            .setMaxSizePolicy(MaxSizePolicy.PER_NODE)
            .setSize(10000);
        config.addMapConfig(failureCountConfig);

        // Dedup map — prevents re-alerting the same phone within 24h
        MapConfig alertSentConfig = new MapConfig(gridPrefix + MAP_PREFIX + "persistent_alert_sent");
        alertSentConfig.setTimeToLiveSeconds((int) TimeUnit.HOURS.toSeconds(PERSISTENT_FAILURE_ALERT_COOLDOWN_HOURS));
        alertSentConfig.getEvictionConfig()
            .setEvictionPolicy(EvictionPolicy.LRU)
            .setMaxSizePolicy(MaxSizePolicy.PER_NODE)
            .setSize(10000);
        config.addMapConfig(alertSentConfig);

        log.info("Configured SMS delivery maps with TTL: success=24h, failures=1h, failure_count_by_phone=24h, persistent_alert_sent=24h");
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
            successCountMap.lock(SUCCESS_COUNT_KEY);
            try {
                Long currentCount = successCountMap.get(SUCCESS_COUNT_KEY);
                successCountMap.set(SUCCESS_COUNT_KEY, currentCount + 1);
            } finally {
                successCountMap.unlock(SUCCESS_COUNT_KEY);
            }

            // If this phone was a persistent offender, clear it immediately on recovery.
            // The next reportPersistentOffenders() cycle will detect the set changed and fire
            // an updated summary alarm automatically.
            String phone = deliveryReport.getRecipient();
            failureCountByPhoneMap.lock(phone);
            try {
                if (failureCountByPhoneMap.containsKey(phone)) {
                    failureCountByPhoneMap.remove(phone);
                    persistentFailureAlertSentMap.remove(phone); // re-arm per-phone alert if failures resume
                    log.info("Phone {} delivered successfully — cleared from persistent failure tracking", phone);
                }
            } finally {
                failureCountByPhoneMap.unlock(phone);
            }

            log.debug("Recorded successful SMS delivery for {}. Cluster total: {}",
                    phone, successCountMap.get(SUCCESS_COUNT_KEY));

        } catch (Exception e) {
            log.error("Error recording success to Hazelcast: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Record a failed SMS delivery (cluster-wide).
     */
    public void recordFailure(Target365DeliveryReport deliveryReport) {
        try {
            String key = deliveryReport.getTransactionId() + "_" + System.currentTimeMillis();
            FailedDelivery failure = new FailedDelivery(
                deliveryReport.getRecipient(),
                deliveryReport.getStatusCode(),
                deliveryReport.getDetailedStatusCode(),
                deliveryReport.getTransactionId()
            );
            failedDeliveriesMap.put(key, failure);

            // Track per-phone failure count (24h rolling window)
            String phone = deliveryReport.getRecipient();
            int count = incrementPhoneFailureCount(phone);
            log.debug("Recorded failed SMS delivery for {}. Phone 24h count: {}, cluster total: {}",
                    phone, count, failedDeliveriesMap.size());

            if (count >= persistentFailureThreshold) {
                checkAndAlertPersistentFailure(phone, count, failure.detailedStatusCode);
            }
        } catch (Exception e) {
            log.error("Error recording failure to Hazelcast: {}", e.getMessage(), e);
        }
    }

    private int incrementPhoneFailureCount(String phone) {
        failureCountByPhoneMap.lock(phone);
        try {
            int current = failureCountByPhoneMap.getOrDefault(phone, 0) + 1;
            failureCountByPhoneMap.put(phone, current,
                    PERSISTENT_FAILURE_ALERT_COOLDOWN_HOURS, TimeUnit.HOURS);
            return current;
        } finally {
            failureCountByPhoneMap.unlock(phone);
        }
    }

    private void checkAndAlertPersistentFailure(String phone, int count, String lastErrorCode) {
        String previous = persistentFailureAlertSentMap.putIfAbsent(phone, String.valueOf(count),
                PERSISTENT_FAILURE_ALERT_COOLDOWN_HOURS, TimeUnit.HOURS);
        if (previous != null) {
            log.debug("Persistent SMS failure alert already sent for {} today (count={})", phone, count);
            return;
        }
        log.warn("Persistent SMS delivery failures for {} — {} failures in 24h (threshold={})",
                phone, count, persistentFailureThreshold);
        Map<String, Object> context = new HashMap<>();
        context.put("phone", phone);
        context.put("failureCount", count);
        context.put("threshold", persistentFailureThreshold);
        context.put("lastErrorCode", lastErrorCode);
        context.put("window", "24 hours");
        context.put("timestamp", LocalDateTime.now().format(TIME_FORMATTER));
        SlackNotificationFacade.sendAlarm(
                String.format("Phone %s has %d SMS delivery failures in the last 24h", phone, count),
                context);
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

            // Include persistent offenders summary (numbers with >= threshold failures in 24h)
            reportPersistentOffenders();

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
    
    private void reportPersistentOffenders() {
        try {
            List<Map.Entry<String, Integer>> offenders = failureCountByPhoneMap.entrySet().stream()
                    .filter(e -> e.getValue() >= persistentFailureThreshold)
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .collect(Collectors.toList());

            // Compute current offender set as a canonical sorted string for comparison.
            String currentSet = offenders.stream()
                    .map(Map.Entry::getKey)
                    .sorted()
                    .collect(Collectors.joining(","));

            // Compare against the last reported set — only fire when the set changes
            // (phone added or recovered). Uses a JVM-level field so this is immune to
            // Hazelcast TTL eviction or map state being wiped on restart.
            String effectiveLast = lastReportedOffenderSet != null ? lastReportedOffenderSet : "";
            if (currentSet.equals(effectiveLast)) {
                log.debug("Persistent SMS offender set unchanged — skipping report");
                return;
            }

            if (currentSet.isEmpty()) {
                lastReportedOffenderSet = null;
                persistentFailureAlertSentMap.remove(SUMMARY_OFFENDERS_KEY);
                return; // offenders all cleared — no alarm needed
            }
            lastReportedOffenderSet = currentSet;
            persistentFailureAlertSentMap.put(SUMMARY_OFFENDERS_KEY, currentSet,
                    Integer.MAX_VALUE, TimeUnit.SECONDS);

            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Integer> entry : offenders) {
                sb.append(String.format("\n  • %s — %d failures in 24h", entry.getKey(), entry.getValue()));
            }

            Map<String, Object> context = new HashMap<>();
            context.put("offenderCount", offenders.size());
            context.put("threshold", persistentFailureThreshold);
            context.put("offenders", sb.toString());
            context.put("timestamp", LocalDateTime.now().format(TIME_FORMATTER));

            String message = String.format(
                    "%d phone number(s) with persistent SMS delivery failures (>= %d in 24h)",
                    offenders.size(), persistentFailureThreshold);

            log.warn("Persistent SMS failure offenders: {}", message);
            SlackNotificationFacade.sendAlarm(message, context);
        } catch (Exception e) {
            log.error("Failed to report persistent SMS offenders to Slack: {}", e.getMessage(), e);
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