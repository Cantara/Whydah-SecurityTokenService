package net.whydah.sts.user.authentication;


import java.io.FileNotFoundException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

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
   

//	  This solves the problem authenticating an existing user against the 3rd party provider
   
//    Client try to get (phonenumber + clientid) -----> Yes, trusted clientid found, return the usertoken
//    		 |
//    		 |
//    		 No ----
//    		     	1. STS generates a pin, register a pair (pin+clientid, phonenumber) to a map, then send pin
//    				2. Client sends these params (verifying pin, phonenumber, clientid) to STS
//    				3. STS check if the pair (pin+clientid, phonenumber) correct in the map -> register (phonenumber + 	clientid) -> send usertoken
   
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
                log.error("Error - not able to load hazelcast.xml configuration.  Using embedded as fallback");
            }
        }
        
        hazelcastConfig.setProperty("hazelcast.logging.type", "slf4j");
        //hazelcastConfig.getGroupConfig().setName("STS_HAZELCAST");
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
        
        pinMap.keySet().forEach(keySet -> {
        	if(!pinMap.get(keySet).contains(":")) {
        		pinMap.remove(keySet);
        	}
        });
    
        
    }

    public static void setPin(String phoneNr, String pin, String smsResponse) {
        pin = paddPin(pin);
        log.debug("Adding pin:{}  to phone:{} ", pin, phoneNr);
        log.debug("SMS log for " + phoneNr + ": "+ smsResponse);
        String paddedPin = pin + ":" + Instant.now().toEpochMilli();
        pinMap.put(phoneNr, paddedPin);
        log.debug("added pin:{}  to phone:{} ", paddedPin, phoneNr);
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
        log.debug("Added pin:{}  to phone:{} for trusted client id {}", pin, phoneNr, clientid);
    }
    
    public static String getPinSentIfAny(String phoneNr) {
    	
    	String storedPin = pinMap.get(phoneNr);
    	if(storedPin !=null && storedPin.contains(":")) {
    		String[] parts = storedPin.split(":");
    		String pin = parts[0];
    		String datetime = parts[1];
    		Instant inst = Instant.ofEpochMilli(Long.valueOf(datetime));
    		if(Instant.now().isAfter(inst.plus(5, ChronoUnit.MINUTES))) {
    			pinMap.remove(phoneNr);
                log.debug("Removed pin for phoneNr:" + phoneNr);
    			return null;
    		} else {
                log.debug("Found pin:" + pin + " for phoneNr:" + phoneNr);
    			return pin;
    		}
    	} else {
            log.debug("Found empty pin:" + storedPin + " for phoneNr:" + phoneNr);
    		//return storedPin;
    		return null;
    	}
      
    }
    
    public static String getPinSentIfAnyForTrustedClient(String phoneNr) {
    	
    	String storedPin = pinMap.get(phoneNr);
    	if(storedPin !=null && storedPin.contains(":")) {
    		String[] parts = storedPin.split(":", 3);
    		String pin = parts[0];
    		String cid = parts[1];
    		String datetime = parts[2];
    		Instant inst = Instant.ofEpochMilli(Long.valueOf(datetime));
    		if(Instant.now().isAfter(inst.plus(5, ChronoUnit.MINUTES))) {
    			pinMap.remove(phoneNr);
                log.debug("Removed pin for phoneNr {} for client {}", phoneNr, cid);
    			return null;
    		} else {
                log.debug("Found pin:" + pin + " for phoneNr {} for client {}", phoneNr, cid);
    			return pin;
    		}
    	} else {
            log.debug("Found empty pin:" + storedPin + " for phoneNr:" + phoneNr);
    		//return storedPin;
    		return null;
    	}
      
    }

    public static boolean usePin(String phoneNr, String pin) {
        pin = paddPin(pin);
        log.debug("usePin - Trying pin {} for phone {}: ", pin, phoneNr);
        if (isValidPin(phoneNr, pin)) {
            log.info("usePin - Used pin:{} for phone: {} - removed", pin, phoneNr);
            //remove after used
            pinMap.remove(phoneNr);
            smsResponseLogMap.remove(phoneNr);
            return true;
        }
        log.debug("usePin - Failed to use pin {} for phone {}  ", pin, phoneNr);
        return false;
    }
    
    public static boolean usePinForTrustedClient(String clientId, String phoneNr, String pin) {
        pin = paddPin(pin);
        log.debug("usePin - Trying pin {} for phone {} for clientid {}: ", pin, phoneNr, clientId);
        if (isValidPinForTrustedClient(clientId, phoneNr, pin)) {
            log.info("usePin - Used pin:{} for phone: {} - removed for clientid {}", pin, phoneNr, clientId);
            //remove after used
            phoneNumberAndTrustedClientIdPinMap.remove(phoneNr);
            
            //register trusted client id for this cell phone
            log.info("register trusted client id {} for phone number {}", clientId, phoneNr);
            phoneNumberAndTrustedClientIdPinMap.put(phoneNr, clientId);
            
            return true;
        }
        log.debug("usePin - Failed to use pin {} for phone {}  ", pin, phoneNr);
        return false;
    }
   
    public static Map<String, String> getPinMap(){
        return  pinMap;
    }
    
    public static Map<String, String> getSMSResponseLogMap(){
        return smsResponseLogMap;
    }

    private static boolean isValidPin(String phoneNr, String pin) {
    	SlackNotifier slackNotifier = HK2ServiceLocator.getService(SlackNotifier.class);
        try {
            pin = paddPin(pin);
            String storedPin = pinMap.get(phoneNr);
            log.debug("isValidPin on lookup returned storedpin:{}, for phone:{}", storedPin, phoneNr);
            if (storedPin != null && storedPin.contains(":")) {
                String[] parts = storedPin.split(":");
                storedPin = parts[0];
                String datetime = parts[1];
                Instant inst = Instant.ofEpochMilli(Long.valueOf(datetime));
                if (Instant.now().isAfter(inst.plus(5, ChronoUnit.MINUTES))) {
                    pinMap.remove(phoneNr);
                    log.warn("Pin expired : {} - {}", phoneNr, pin);
                    return false;
                }
            }

            log.debug("isValidPin on pin:{},  storedpin:{}, phone:{}", pin, storedPin, phoneNr);
            if (storedPin != null && storedPin.equals(pin)) {
                log.debug("isValidPin on pin:{},  storedpin:{}, phone:{} success", pin, storedPin, phoneNr);
                return true;
            }

            log.warn("Illegal pin logon attempted. phone: {} invalid pin attempted:{}", phoneNr, pin);
            
            
            if(slackNotifier!=null) {
            	slackNotifier.sendAlarm("Illegal pin logon attempted.", ContextMapBuilder.of(
            			"phone", phoneNr,
            			"submitted_pin", pin,
            			"stored_pin", storedPin
            			));
            }
            
            return false;
        } catch (Exception e) {
            log.error("Exception in isValidPin.  phoneNo:" + phoneNr + "-pin:" + pin, e);
            if(slackNotifier!=null) {
            	slackNotifier.handleException(e, "isValidPin", ContextMapBuilder.of(
            			"phone", phoneNr,
            			"submitted_pin", pin
            			));
            	
            	return false;
            	
            } else {
            	throw e;	
            }
        }
    }
    
    private static boolean isValidPinForTrustedClient(String clientId, String phoneNr, String pin) {
    	SlackNotifier slackNotifier = HK2ServiceLocator.getService(SlackNotifier.class);
        try {
        	
            pin = paddPin(pin);
            String found = phoneNumberAndTrustedClientIdPinMap.get(phoneNr);
            log.debug("isValidPinForTrustedClient on lookup returned storedpin:{}, for phone:{} for clienid:{}", found, phoneNr, clientId);
            String storedPin = null;
            String storedClientId = null;
            if (found != null && found.contains(":")) {
                String[] parts = found.split(":", 3);
                storedPin = parts[0];
                storedClientId = parts[1];
                String datetime = parts[2];
                Instant inst = Instant.ofEpochMilli(Long.valueOf(datetime));
                if (Instant.now().isAfter(inst.plus(5, ChronoUnit.MINUTES))) {
                    pinMap.remove(phoneNr);
                    log.warn("Pin expired : {} - {}", phoneNr, pin);
                    return false;
                }
            }

            log.debug("isValidPinForTrustedClient on pin:{},  storedpin:{}, phone:{}, clientid:{}", pin, storedPin, phoneNr, clientId);
            if (storedPin != null && storedClientId != null &&  storedPin.equals(pin) && storedClientId.equals(clientId)) {
                log.debug("isValidPinForTrustedClient on pin:{},  storedpin:{}, phone:{} clientid:{} success", pin, storedPin, phoneNr, clientId);
                return true;
            }

            log.warn("Illegal pin logon attempted. phone: {} invalid pin attempted:{}", phoneNr, pin);
            
            if(slackNotifier!=null) {
            	slackNotifier.sendAlarm("Illegal pin logon attempted.", ContextMapBuilder.of(
            			"phone", phoneNr,
            			"submitted_pin", pin,
            			"stored_pin", storedPin
            			));
            }
            
            return false;
        } catch (Exception e) {
            log.error("Exception in isValidPinForTrustedClient.  phoneNo:" + phoneNr + "-pin:" + pin + "-clientid:" + clientId , e);
            if(slackNotifier!=null) {
            	slackNotifier.handleException(e, "isValidPinForTrustedClient", ContextMapBuilder.of(
            			"phone", phoneNr,
            			"submitted_pin", pin
            			));
            	
            	return false;
            	
            } else {
            	throw e;	
            }
        }
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
