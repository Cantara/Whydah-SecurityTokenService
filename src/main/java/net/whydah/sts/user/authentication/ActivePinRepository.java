package net.whydah.sts.user.authentication;

import java.io.FileNotFoundException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import net.whydah.sts.util.HK2ServiceLocator;

public class ActivePinRepository {
    private final static Logger log = LoggerFactory.getLogger(ActivePinRepository.class);
    private static Map<String, String> pinMap;
    private static Map<String, String> smsResponseLogMap;
    private static Map<String, String> phoneNumberAndTrustedClientIdMap;
    private static Map<String, String> phoneNumberAndTrustedClientIdPinMap;

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
        
        log.info("Connecting to map {}",appConfig.getProperty("gridprefix")+"pinMap");
        log.info("Loaded pin-Map size=" + pinMap.size());
        
        // FIXED: Safe cleanup of invalid entries
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
                pinMap.remove(phoneNr, storedPin);  // ✅ FIXED: Atomic remove
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
                phoneNumberAndTrustedClientIdPinMap.remove(phoneNr, storedPin);  // ✅ FIXED: Atomic
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
            pinMap.remove(phoneNr, storedValue);  // ✅ FIXED: Atomic remove
            log.warn("Pin expired for phone {}", phoneNr);
            return false;
        }
        
        // Check pin match
        if (!storedPin.equals(pin)) {
            log.warn("Illegal pin logon attempted. phone: {} invalid pin: {}", phoneNr, pin);
            sendInvalidPinAlert(phoneNr, pin, storedPin);
            return false;
        }
        
        // Atomic remove - only succeeds if value hasn't changed
        boolean removed = pinMap.remove(phoneNr, storedValue);
        if (removed) {
            log.info("usePin - Used pin:{} for phone: {} - removed", pin, phoneNr);
            smsResponseLogMap.remove(phoneNr);
            return true;
        } else {
            log.warn("Pin was already used by another thread for phone: {}", phoneNr);
            return false;
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
            phoneNumberAndTrustedClientIdPinMap.remove(phoneNr, storedValue);  // ✅ FIXED: Atomic
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
}