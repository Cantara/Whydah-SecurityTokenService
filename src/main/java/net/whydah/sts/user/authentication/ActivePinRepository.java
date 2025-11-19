package net.whydah.sts.user.authentication;

import java.io.FileNotFoundException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.exoreaction.notification.util.ContextMapBuilder;
import com.hazelcast.config.Config;
import com.hazelcast.config.XmlConfigBuilder;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;

import net.whydah.sts.config.AppConfig;
import net.whydah.sts.slack.SlackNotifier;
import net.whydah.sts.smsgw.SMSGatewayCommandFactory;
import net.whydah.sts.util.HK2ServiceLocator;

public class ActivePinRepository {
    private final static Logger log = LoggerFactory.getLogger(ActivePinRepository.class);
    private static Map<String, String> pinMap;
    private static Map<String, String> smsResponseLogMap;
    private static Map<String, String> phoneNumberAndTrustedClientIdMap;
    private static Map<String, String> phoneNumberAndTrustedClientIdPinMap;
    
    // Track PIN usage for security alerts
    private static Map<String, Integer> pinUsageCountMap;
    
    // Configurable threshold for security alerts
    private static final int SECURITY_ALERT_THRESHOLD = 5;
    
    static {
        AppConfig appConfig = new AppConfig();
        String xmlFileName = System.getProperty("hazelcast.config");
        if (xmlFileName == null || xmlFileName.trim().isEmpty()) {
            xmlFileName = appConfig.getProperty("hazelcast.config");
        }
        log.info("Loading hazelcast configuration from :" + xmlFileName);
        Config hazelcastConfig = new Config();
        if (xmlFileName != null && xmlFileName.length() > 10) {
            try {
                hazelcastConfig = new XmlConfigBuilder(xmlFileName).build();
                log.info("Loading hazelcast configuration from :" + xmlFileName);
            } catch (FileNotFoundException notFound) {
                log.error("Error - not able to load hazelcast.xml configuration. Using embedded as fallback");
            }
        }
        
        hazelcastConfig.setProperty("hazelcast.logging.type", "slf4j");
        HazelcastInstance hazelcastInstance;
        try {
            hazelcastInstance = Hazelcast.newHazelcastInstance(hazelcastConfig);
        } catch(Exception ex) {
            hazelcastInstance = Hazelcast.newHazelcastInstance();
        }
        
        pinMap = hazelcastInstance.getMap(appConfig.getProperty("gridprefix")+"pinMap");
        smsResponseLogMap = hazelcastInstance.getMap(appConfig.getProperty("gridprefix")+"smsResponseLogMap");
        phoneNumberAndTrustedClientIdMap = hazelcastInstance.getMap(appConfig.getProperty("gridprefix")+"phoneNumberAndTrustedClientIdMap");
        phoneNumberAndTrustedClientIdPinMap = hazelcastInstance.getMap(appConfig.getProperty("gridprefix")+"clientPinAndPhoneNumberMap");
        pinUsageCountMap = hazelcastInstance.getMap(appConfig.getProperty("gridprefix")+"pinUsageCountMap");
        
        log.info("Connecting to map {}",appConfig.getProperty("gridprefix")+"pinMap");
        log.info("Loaded pin-Map size=" + pinMap.size());
        log.info("Security alert threshold set to {} uses", SECURITY_ALERT_THRESHOLD);
        
        // Safe cleanup of invalid entries
        Set<String> invalidKeys = new HashSet<>();
        pinMap.forEach((key, value) -> {
            if (value == null || !value.contains(":")) {
                invalidKeys.add(key);
            }
        });
        invalidKeys.forEach(key -> pinMap.remove(key));
        log.info("Cleaned {} invalid pin entries", invalidKeys.size());
    }

    public static void setPin(String phoneNr, String pin, String smsResponse) {
        pin = paddPin(pin);
        log.debug("Adding pin:{} to phone:{} ", pin, phoneNr);
        log.debug("SMS log for " + phoneNr + ": "+ smsResponse);
        String paddedPin = pin + ":" + Instant.now().toEpochMilli();
        pinMap.put(phoneNr, paddedPin);
        pinUsageCountMap.put(phoneNr, 0); // Reset usage counter
        log.debug("added pin:{} to phone:{} ", paddedPin, phoneNr);
        if(smsResponse!=null && !smsResponse.isEmpty()){
            smsResponseLogMap.put(phoneNr, smsResponse);
        }
    }

    public static void setDLR(String phoneNr, String dlr) {
        if(dlr!=null && !dlr.isEmpty()){
            smsResponseLogMap.put(phoneNr, dlr);
        }
    }
    
    public static void setPinForTrustedClient(String clientid, String phoneNr, String pin) {
        pin = paddPin(pin);
        String paddedPin = pin + ":" + clientid + ":" + Instant.now().toEpochMilli();
        phoneNumberAndTrustedClientIdPinMap.put(phoneNr, paddedPin);
        phoneNumberAndTrustedClientIdMap.put(phoneNr, clientid);
        log.debug("Added pin:{} to phone:{} for trusted client id {}", pin, phoneNr, clientid);
    }
    
    public static String getPinSentIfAny(String phoneNr) {
        String storedPin = pinMap.get(phoneNr);
        if(storedPin !=null && storedPin.contains(":")) {
            String[] parts = storedPin.split(":");
            String pin = parts[0];
            String datetime = parts[1];
            Instant inst = Instant.ofEpochMilli(Long.valueOf(datetime));
            if(Instant.now().isAfter(inst.plus(5, ChronoUnit.MINUTES))) {
                pinMap.remove(phoneNr, storedPin);
                pinUsageCountMap.remove(phoneNr);
                log.debug("Removed expired pin for phoneNr:" + phoneNr);
                return null;
            } else {
                log.debug("Found pin:" + pin + " for phoneNr:" + phoneNr);
                return pin;
            }
        } else {
            log.debug("Found empty pin:" + storedPin + " for phoneNr:" + phoneNr);
            return null;
        }
    }
    
    public static String getPinSentIfAnyForTrustedClient(String phoneNr) {
        // ✅ FIXED: Use correct map
        String storedPin = phoneNumberAndTrustedClientIdPinMap.get(phoneNr);
        if(storedPin !=null && storedPin.contains(":")) {
            String[] parts = storedPin.split(":", 3);
            String pin = parts[0];
            String cid = parts[1];
            String datetime = parts[2];
            Instant inst = Instant.ofEpochMilli(Long.valueOf(datetime));
            if(Instant.now().isAfter(inst.plus(5, ChronoUnit.MINUTES))) {
                phoneNumberAndTrustedClientIdPinMap.remove(phoneNr, storedPin);
                log.debug("Removed expired pin for phoneNr {} for client {}", phoneNr, cid);
                return null;
            } else {
                log.debug("Found pin:{} for phoneNr:{} for client:{}", pin, phoneNr, cid);
                return pin;
            }
        } else {
            log.debug("Found empty pin:" + storedPin + " for phoneNr:" + phoneNr);
            return null;
        }
    }

    /**
     * Validate and use PIN - allows reuse within 5-minute window
     * Sends Norwegian security alert only after threshold (5+ uses)
     */
    public static boolean usePin(String phoneNr, String pin) {
        pin = paddPin(pin);
        log.debug("usePin - Trying pin {} for phone {}: ", pin, phoneNr);
        
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
            pinMap.remove(phoneNr, storedValue);
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
        
        // ✅ PIN is valid - track usage
        Integer usageCount = pinUsageCountMap.getOrDefault(phoneNr, 0);
        usageCount++;
        pinUsageCountMap.put(phoneNr, usageCount);
        
        log.info("usePin - Valid pin used (usage #{}) for phone: {}", usageCount, phoneNr);
        
        // ✅ Send Norwegian security alert only if usage >= threshold
        if (usageCount >= SECURITY_ALERT_THRESHOLD) {
            sendNorwegianSecurityAlert(phoneNr, usageCount);
        } else {
            log.debug("PIN usage count {} below threshold {} - no alert sent", usageCount, SECURITY_ALERT_THRESHOLD);
        }
        
        // ✅ Keep PIN valid for 5 minutes - don't remove
        return true;
    }
    
    /**
     * Send Norwegian security alert SMS
     * Only called when usage count >= SECURITY_ALERT_THRESHOLD
     */
    private static void sendNorwegianSecurityAlert(String phoneNr, int usageCount) {
        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
            String message;
            
            if (usageCount == SECURITY_ALERT_THRESHOLD) {
                // First alert - inform user of suspicious activity
                message = String.format(
                    "SIKKERHETSVARSLING: Din konto har blitt åpnet %d ganger siden kl %s. " +
                    "Hvis dette ikke var deg, kontakt support umiddelbart.", 
                    usageCount, timestamp
                );
            } else {
                // Subsequent alerts - more urgent
                message = String.format(
                    "ADVARSEL: Din konto har nå blitt åpnet %d ganger. " +
                    "Hvis dette ikke var deg, kontakt support NÅ!", 
                    usageCount
                );
            }
            
            log.warn("Sending Norwegian security alert to {} (usage: {}): {}", phoneNr, usageCount, message);
            
            String response = SMSGatewayCommandFactory.getInstance()
                .createSendSMSCommand(phoneNr, message)
                .execute();
            
            if (response != null && !response.isEmpty()) {
                log.info("Security alert SMS sent successfully: {}", response);
                setDLR(phoneNr, "SECURITY_ALERT_" + usageCount + ": " + response);
            }
            
            // Also send Slack alert for monitoring
            sendSlackSecurityAlert(phoneNr, usageCount);
            
        } catch (Exception e) {
            log.error("Failed to send Norwegian security alert to {}", phoneNr, e);
            // Don't fail authentication if SMS fails
        }
    }
    
    /**
     * Send Slack alert for internal monitoring
     */
    private static void sendSlackSecurityAlert(String phoneNr, int usageCount) {
        try {
            SlackNotifier slackNotifier = HK2ServiceLocator.getService(SlackNotifier.class);
            if (slackNotifier != null) {
                slackNotifier.sendAlarm(
                    "High PIN reuse detected", 
                    ContextMapBuilder.of(
                        "phone", phoneNr,
                        "usage_count", String.valueOf(usageCount),
                        "threshold", String.valueOf(SECURITY_ALERT_THRESHOLD),
                        "severity", usageCount >= 10 ? "HIGH" : "MEDIUM"
                    )
                );
            }
        } catch (Exception e) {
            log.warn("Failed to send Slack security alert", e);
        }
    }
    
    private static void sendInvalidPinAlert(String phoneNr, String submittedPin, String storedPin) {
        SlackNotifier slackNotifier = HK2ServiceLocator.getService(SlackNotifier.class);
        if (slackNotifier != null) {
            slackNotifier.sendAlarm("Illegal pin logon attempted.", ContextMapBuilder.of(
                "phone", phoneNr,
                "submitted_pin", submittedPin,
                "stored_pin", storedPin
            ));
        }
    }
    
    public static boolean usePinForTrustedClient(String clientId, String phoneNr, String pin) {
        pin = paddPin(pin);
        
        String storedValue = phoneNumberAndTrustedClientIdPinMap.get(phoneNr);
        if (storedValue == null || !storedValue.contains(":")) {
            return false;
        }
        
        String[] parts = storedValue.split(":", 3);
        String storedPin = parts[0];
        String storedClientId = parts[1];
        String datetime = parts[2];
        
        // Check expiration
        Instant inst = Instant.ofEpochMilli(Long.valueOf(datetime));
        if (Instant.now().isAfter(inst.plus(5, ChronoUnit.MINUTES))) {
            phoneNumberAndTrustedClientIdPinMap.remove(phoneNr, storedValue);
            return false;
        }
        
        // Validate pin and clientId
        if (!storedPin.equals(pin) || !storedClientId.equals(clientId)) {
            log.warn("Invalid pin/client for phone: {}", phoneNr);
            return false;
        }
        
        // Atomic remove
        boolean removed = phoneNumberAndTrustedClientIdPinMap.remove(phoneNr, storedValue);
        if (removed) {
            // Register trusted client
            phoneNumberAndTrustedClientIdMap.put(phoneNr, clientId);
            log.info("Registered trusted client {} for phone {}", clientId, phoneNr);
            return true;
        }
        
        return false;
    }
   
    public static Map<String, String> getPinMap(){
        return pinMap;
    }
    
    public static Map<String, String> getSMSResponseLogMap(){
        return smsResponseLogMap;
    }

    public static boolean isTrustedClientRegistered(String clientId, String phoneNumber) {
        return phoneNumberAndTrustedClientIdMap.containsKey(phoneNumber)
                && phoneNumberAndTrustedClientIdMap.get(phoneNumber).equals(clientId);
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
    
    /**
     * Get current usage count for a phone number
     */
    public static int getPinUsageCount(String phoneNr) {
        return pinUsageCountMap.getOrDefault(phoneNr, 0);
    }
    
    /**
     * Clean up expired PINs and reset usage counters
     * Should be called periodically
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
            log.info("Cleaned up {} expired PINs", expiredKeys.size());
        }
    }
    
    /**
     * Get statistics for monitoring
     */
    public static Map<String, Object> getPinStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("active_pins", pinMap.size());
        stats.put("pins_above_threshold", pinUsageCountMap.values().stream()
            .filter(count -> count >= SECURITY_ALERT_THRESHOLD).count());
        stats.put("max_usage_count", pinUsageCountMap.values().stream()
            .max(Integer::compareTo).orElse(0));
        stats.put("total_active_sessions", pinUsageCountMap.size());
        return stats;
    }
}