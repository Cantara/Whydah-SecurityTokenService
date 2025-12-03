package net.whydah.sts.user.authentication;

import java.io.FileNotFoundException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.exoreaction.notification.SlackNotificationFacade;
import com.exoreaction.notification.util.ContextMapBuilder;
import com.hazelcast.config.Config;
import com.hazelcast.config.EvictionPolicy;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.MaxSizePolicy;
import com.hazelcast.config.XmlConfigBuilder;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;

import net.whydah.sts.config.AppConfig;
import net.whydah.sts.smsgw.SMSGatewayCommandFactory;

public class ActivePinRepository {
    private final static Logger log = LoggerFactory.getLogger(ActivePinRepository.class);
    
    private static IMap<String, String> pinMap;
    private static IMap<String, String> smsResponseLogMap;
    private static IMap<String, Integer> pinUsageCountMap;
    
    // Configurable threshold for security alerts
    private static final int SECURITY_ALERT_THRESHOLD = 5;
    
    // TTL configurations (in seconds)
    private static final int PIN_TTL_SECONDS = 300; // 5 minutes
    private static final int SMS_RESPONSE_TTL_SECONDS = 3600; // 1 hour
    private static final int USAGE_COUNT_TTL_SECONDS = 300; // 5 minutes
    
    static {
        AppConfig appConfig = new AppConfig();
        String xmlFileName = System.getProperty("hazelcast.config");
        if (xmlFileName == null || xmlFileName.trim().isEmpty()) {
            xmlFileName = appConfig.getProperty("hazelcast.config");
        }
        
        log.info("Loading hazelcast configuration from: {}", xmlFileName);
        Config hazelcastConfig = new Config();
        
        if (xmlFileName != null && xmlFileName.length() > 10) {
            try {
                hazelcastConfig = new XmlConfigBuilder(xmlFileName).build();
                log.info("Loaded hazelcast configuration from: {}", xmlFileName);
            } catch (FileNotFoundException notFound) {
                log.error("Error - not able to load hazelcast.xml configuration. Using embedded as fallback");
            }
        }
        
        hazelcastConfig.setProperty("hazelcast.logging.type", "slf4j");
        
        // Configure maps programmatically with TTL and eviction
        String gridPrefix = appConfig.getProperty("gridprefix");
        configurePinMap(hazelcastConfig, gridPrefix);
        configureSmsResponseMap(hazelcastConfig, gridPrefix);
        configurePinUsageCountMap(hazelcastConfig, gridPrefix);
        
        HazelcastInstance hazelcastInstance;
        try {
            hazelcastInstance = Hazelcast.newHazelcastInstance(hazelcastConfig);
        } catch (Exception ex) {
            log.error("Failed to initialize Hazelcast with config, using defaults", ex);
            hazelcastInstance = Hazelcast.newHazelcastInstance();
        }
        
        pinMap = hazelcastInstance.getMap(gridPrefix + "pinMap");
        smsResponseLogMap = hazelcastInstance.getMap(gridPrefix + "smsResponseLogMap");
        pinUsageCountMap = hazelcastInstance.getMap(gridPrefix + "pinUsageCountMap");
        
        log.info("Connected to map: {}pinMap (size: {})", gridPrefix, pinMap.size());
        log.info("Connected to map: {}smsResponseLogMap (size: {})", gridPrefix, smsResponseLogMap.size());
        log.info("Connected to map: {}pinUsageCountMap (size: {})", gridPrefix, pinUsageCountMap.size());
        log.info("Security alert threshold set to {} uses", SECURITY_ALERT_THRESHOLD);
        
        // Safe cleanup of invalid entries
        cleanupInvalidEntries();
    }
    
    /**
     * Configure PIN map with TTL and eviction policy.
     */
    private static void configurePinMap(Config config, String gridPrefix) {
        MapConfig mapConfig = new MapConfig(gridPrefix + "pinMap");
        
        // Set TTL (time to live) - entries expire after 5 minutes
        mapConfig.setTimeToLiveSeconds(PIN_TTL_SECONDS);
        
        // Set eviction policy
        mapConfig.getEvictionConfig()
            .setEvictionPolicy(EvictionPolicy.LRU) // Least Recently Used
            .setMaxSizePolicy(MaxSizePolicy.PER_NODE)
            .setSize(10000); // Max 10k entries per node
        
        config.addMapConfig(mapConfig);
        log.info("Configured pinMap with TTL={}s, eviction=LRU, maxSize=10k", PIN_TTL_SECONDS);
    }
    
    /**
     * Configure SMS response log map with TTL and eviction policy.
     */
    private static void configureSmsResponseMap(Config config, String gridPrefix) {
        MapConfig mapConfig = new MapConfig(gridPrefix + "smsResponseLogMap");
        
        // Set TTL - logs expire after 1 hour
        mapConfig.setTimeToLiveSeconds(SMS_RESPONSE_TTL_SECONDS);
        
        // Set eviction policy
        mapConfig.getEvictionConfig()
            .setEvictionPolicy(EvictionPolicy.LRU)
            .setMaxSizePolicy(MaxSizePolicy.PER_NODE)
            .setSize(50000); // Max 50k entries per node (larger for logs)
        
        config.addMapConfig(mapConfig);
        log.info("Configured smsResponseLogMap with TTL={}s, eviction=LRU, maxSize=50k", 
                SMS_RESPONSE_TTL_SECONDS);
    }
    
    /**
     * Configure PIN usage count map with TTL and eviction policy.
     */
    private static void configurePinUsageCountMap(Config config, String gridPrefix) {
        MapConfig mapConfig = new MapConfig(gridPrefix + "pinUsageCountMap");
        
        // Set TTL - usage counts expire after 5 minutes
        mapConfig.setTimeToLiveSeconds(USAGE_COUNT_TTL_SECONDS);
        
        // Set eviction policy
        mapConfig.getEvictionConfig()
            .setEvictionPolicy(EvictionPolicy.LRU)
            .setMaxSizePolicy(MaxSizePolicy.PER_NODE)
            .setSize(10000); // Max 10k entries per node
        
        config.addMapConfig(mapConfig);
        log.info("Configured pinUsageCountMap with TTL={}s, eviction=LRU, maxSize=10k", 
                USAGE_COUNT_TTL_SECONDS);
    }
    
    /**
     * Clean up invalid entries on startup.
     */
    private static void cleanupInvalidEntries() {
        Set<String> invalidKeys = new HashSet<>();
        pinMap.forEach((key, value) -> {
            if (value == null || !value.contains(":")) {
                invalidKeys.add(key);
            }
        });
        invalidKeys.forEach(key -> pinMap.remove(key));
        if (!invalidKeys.isEmpty()) {
            log.info("Cleaned {} invalid pin entries on startup", invalidKeys.size());
        }
    }

    public static void setPin(String phoneNr, String pin, String smsResponse) {
        pin = paddPin(pin);
        log.debug("Adding pin:{} to phone:{}", pin, phoneNr);
        log.debug("SMS log for {}: {}", phoneNr, smsResponse);
        
        String paddedPin = pin + ":" + Instant.now().toEpochMilli();
        
        // Put with explicit TTL (Hazelcast will auto-expire)
        pinMap.put(phoneNr, paddedPin);
        pinUsageCountMap.put(phoneNr, 0); // Reset usage counter
        
        log.debug("Added pin:{} to phone:{} (will auto-expire in {} seconds)", 
                paddedPin, phoneNr, PIN_TTL_SECONDS);
        
        if (smsResponse != null && !smsResponse.isEmpty()) {
            smsResponseLogMap.put(phoneNr, smsResponse);
        }
    }

    public static void setDLR(String phoneNr, String dlr) {
        if (dlr != null && !dlr.isEmpty()) {
            smsResponseLogMap.put(phoneNr, dlr);
        }
    }
    
    public static String getPinSentIfAny(String phoneNr) {
        String storedPin = pinMap.get(phoneNr);
        if (storedPin != null && storedPin.contains(":")) {
            String[] parts = storedPin.split(":");
            String pin = parts[0];
            String datetime = parts[1];
            Instant inst = Instant.ofEpochMilli(Long.valueOf(datetime));
            if (Instant.now().isAfter(inst.plus(5, ChronoUnit.MINUTES))) {
                // Hazelcast should have already removed this due to TTL, but clean up just in case
                pinMap.remove(phoneNr);
                pinUsageCountMap.remove(phoneNr);
                log.debug("Removed expired pin for phoneNr: {}", phoneNr);
                return null;
            } else {
                log.debug("Found pin: {} for phoneNr: {}", pin, phoneNr);
                return pin;
            }
        } else {
            log.debug("Found empty/invalid pin: {} for phoneNr: {}", storedPin, phoneNr);
            return null;
        }
    }
    
    public static boolean usePin(String phoneNr, String pin) {
        pin = paddPin(pin);
        log.debug("usePin - Trying pin {} for phone {}", pin, phoneNr);
        
        String storedValue = pinMap.get(phoneNr);
        if (storedValue == null || !storedValue.contains(":")) {
            log.debug("usePin - No valid pin found for phone {}", phoneNr);
            return false;
        }
        
        String[] parts = storedValue.split(":");
        String storedPin = parts[0];
        String datetime = parts[1];
        
        // Check expiration
        Instant inst = Instant.ofEpochMilli(Long.valueOf(datetime));
        if (Instant.now().isAfter(inst.plus(5, ChronoUnit.MINUTES))) {
            pinMap.remove(phoneNr);
            pinUsageCountMap.remove(phoneNr);
            log.warn("Pin expired for phone {}", phoneNr);
            return false;
        }
        
        // Check pin match
        if (!storedPin.equals(pin)) {
            log.warn("Illegal pin logon attempted. phone: {} invalid pin: {}", phoneNr, pin);
            sendInvalidPinAlert(phoneNr, pin, storedPin);
            return false;
        }
        
        // PIN is valid - track usage
        Integer usageCount = pinUsageCountMap.getOrDefault(phoneNr, 0);
        usageCount++;
        pinUsageCountMap.put(phoneNr, usageCount);
        
        log.info("usePin - Valid pin used (usage #{}) for phone: {}", usageCount, phoneNr);
        
        // Send Norwegian security alert only if usage >= threshold
        if (usageCount >= SECURITY_ALERT_THRESHOLD) {
            sendNorwegianSecurityAlert(phoneNr, usageCount);
        } else {
            log.debug("PIN usage count {} below threshold {} - no alert sent", 
                    usageCount, SECURITY_ALERT_THRESHOLD);
        }
        
        return true;
    }
    
    private static void sendNorwegianSecurityAlert(String phoneNr, int usageCount) {
        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
            String message;
            
            if (usageCount == SECURITY_ALERT_THRESHOLD) {
                message = String.format(
                    "SIKKERHETSVARSLING: Din konto har blitt åpnet %d ganger siden kl %s. " +
                    "Hvis dette ikke var deg, kontakt support umiddelbart.", 
                    usageCount, timestamp
                );
            } else {
                message = String.format(
                    "ADVARSEL: Din konto har nå blitt åpnet %d ganger. " +
                    "Hvis dette ikke var deg, kontakt support NÅ!", 
                    usageCount
                );
            }
            
            log.warn("Sending Norwegian security alert to {} (usage: {})", phoneNr, usageCount);
            
            String response = SMSGatewayCommandFactory.getInstance()
                .createSendSMSCommand(phoneNr, message)
                .execute();
            
            if (response != null && !response.isEmpty()) {
                log.info("Security alert SMS sent successfully: {}", response);
                setDLR(phoneNr, "SECURITY_ALERT_" + usageCount + ": " + response);
            }
            
        } catch (Exception e) {
            log.error("Failed to send Norwegian security alert to {}", phoneNr, e);
        }
    }
    
    private static void sendInvalidPinAlert(String phoneNr, String submittedPin, String storedPin) {
        SlackNotificationFacade.sendAlarm("Illegal pin logon attempted.", 
            ContextMapBuilder.of(
                "phone", phoneNr,
                "submitted_pin", submittedPin,
                "stored_pin", storedPin
            ));
    }
   
    public static Map<String, String> getPinMap() {
        return pinMap;
    }
    
    public static Map<String, String> getSMSResponseLogMap() {
        return smsResponseLogMap;
    }

    public static String paddPin(String pin) {
        if (pin.length() == 3) {
            pin = "0" + pin;
        } else if (pin.length() == 2) {
            pin = "00" + pin;
        } else if (pin.length() == 1) {
            pin = "000" + pin;
        }
        return pin;
    }
    
    public static int getPinUsageCount(String phoneNr) {
        return pinUsageCountMap.getOrDefault(phoneNr, 0);
    }
    
    /**
     * Clean up expired PINs manually (if needed, though TTL handles most of it).
     * This is a safety net for edge cases.
     */
    public static void cleanupExpiredPins() {
        Set<String> expiredKeys = new HashSet<>();
        Instant now = Instant.now();
        
        pinMap.forEach((phoneNr, storedValue) -> {
            if (storedValue != null && storedValue.contains(":")) {
                String[] parts = storedValue.split(":");
                String datetime = parts[1];
                Instant inst = Instant.ofEpochMilli(Long.valueOf(datetime));
                if (now.isAfter(inst.plus(5, ChronoUnit.MINUTES))) {
                    expiredKeys.add(phoneNr);
                }
            }
        });
        
        expiredKeys.forEach(key -> {
            pinMap.remove(key);
            pinUsageCountMap.remove(key);
        });
        
        if (!expiredKeys.isEmpty()) {
            log.info("Manually cleaned up {} expired PINs (TTL should have handled most)", 
                    expiredKeys.size());
        }
    }
    
    /**
     * Get statistics for monitoring.
     */
    public static Map<String, Object> getPinStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("active_pins", pinMap.size());
        stats.put("sms_response_logs", smsResponseLogMap.size());
        stats.put("pins_above_threshold", pinUsageCountMap.values().stream()
            .filter(count -> count >= SECURITY_ALERT_THRESHOLD).count());
        stats.put("max_usage_count", pinUsageCountMap.values().stream()
            .max(Integer::compareTo).orElse(0));
        stats.put("total_active_sessions", pinUsageCountMap.size());
        stats.put("pin_ttl_seconds", PIN_TTL_SECONDS);
        stats.put("sms_log_ttl_seconds", SMS_RESPONSE_TTL_SECONDS);
        return stats;
    }
}