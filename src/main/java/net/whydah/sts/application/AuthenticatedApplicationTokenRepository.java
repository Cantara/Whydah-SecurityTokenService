package net.whydah.sts.application;

import java.io.FileNotFoundException;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import javax.crypto.spec.IvParameterSpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hazelcast.config.Config;
import com.hazelcast.config.XmlConfigBuilder;
import com.hazelcast.core.HazelcastInstance;

import net.whydah.sso.application.mappers.ApplicationCredentialMapper;
import net.whydah.sso.application.mappers.ApplicationTagMapper;
import net.whydah.sso.application.mappers.ApplicationTokenMapper;
import net.whydah.sso.application.types.Application;
import net.whydah.sso.application.types.ApplicationCredential;
import net.whydah.sso.application.types.ApplicationToken;
import net.whydah.sso.application.types.Tag;
import net.whydah.sso.ddd.model.application.ApplicationTokenExpires;
import net.whydah.sso.session.baseclasses.ExchangeableKey;
import net.whydah.sts.config.AppConfig;
import net.whydah.sts.slack.SlackNotifications;


public class AuthenticatedApplicationTokenRepository {
    private final static Logger log = LoggerFactory.getLogger(AuthenticatedApplicationTokenRepository.class);

    public static final long DEFAULT_APPLICATION_SESSION_EXTENSION_TIME_IN_SECONDS;
    public static final int APPLICATION_SESSION_CHECK_INTERVAL_IN_SECONDS = 10;  // Check every 30 seconds to adapt quickly
    public static final int APPLICATION_UPDATE_CHECK_INTERVAL_IN_SECONDS = 10;
    public static final int APPLICATION_SESSION_EXTENSION_TIME_IN_SECONDS = 50;
    public static final int STS_TOKEN_MULTIPLIER = 50;
    public static final int APP_TOKEN_MULTIPLIER = 50;
    private static AppConfig appConfig = new AppConfig();
    private static String mySTSApplicationTokenId = "";
    private static ApplicationToken mySTSApplicationToken;
    private static ApplicationToken uasApplicationToken;
    private static ApplicationToken uibApplicationToken;
    private static ObjectMapper mapper = new ObjectMapper();

    private static final Map<String, ApplicationToken> applicationTokenMap;
    private static final Map<String, String> applicationCryptoKeyMap;
    private static final Base64.Decoder decoder = Base64.getDecoder();
    final static HazelcastInstance hazelcastInstance;

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
                log.error("Error - not able to load hazelcast.xml configuration.  Using embedded configuration as fallback");
            }
        }
        //hazelcastConfig.getGroupConfig().setName("STS_HAZELCAST");
        hazelcastConfig.setProperty("hazelcast.logging.type", "slf4j");
        hazelcastInstance = HazelcastUtils.initWithConfigWithFallbackToDefault(hazelcastConfig);
        applicationTokenMap = hazelcastInstance.getMap(appConfig.getProperty("gridprefix") + "_authenticated_applicationtokens");
        log.info("Connecting to map {} - map size: {}", appConfig.getProperty("gridprefix") + "_authenticated_applicationtokens", getMapSize());

        applicationCryptoKeyMap = hazelcastInstance.getMap(appConfig.getProperty("gridprefix") + "_applicationkeys");
        log.info("Connecting to map {} - map size: {}", appConfig.getProperty("gridprefix") + "_applicationkeys", getKeyMapSize());


        String applicationDefaultTimeout = appConfig.getProperty("application.session.timeout");
        log.info("Read DEFAULT_APPLICATION_SESSION_EXTENSION_TIME_IN_SECONDS from properties " + applicationDefaultTimeout);
        if (applicationDefaultTimeout != null && (Integer.parseInt(applicationDefaultTimeout) > 0)) {
            if (Integer.parseInt(applicationDefaultTimeout) < APPLICATION_SESSION_EXTENSION_TIME_IN_SECONDS * 10) {
                log.warn("Attempt to set application.session.timeout to low, overriding with WhydahApplicationSession.APPLICATION_SESSION_CHECK_INTERVAL_IN_SECONDS*10: {} ", APPLICATION_SESSION_CHECK_INTERVAL_IN_SECONDS * 10);
                DEFAULT_APPLICATION_SESSION_EXTENSION_TIME_IN_SECONDS = APPLICATION_SESSION_CHECK_INTERVAL_IN_SECONDS * 10;
            } else {
                DEFAULT_APPLICATION_SESSION_EXTENSION_TIME_IN_SECONDS = Integer.parseInt(applicationDefaultTimeout);
            }
        } else {
            DEFAULT_APPLICATION_SESSION_EXTENSION_TIME_IN_SECONDS = APPLICATION_SESSION_CHECK_INTERVAL_IN_SECONDS * 10; //One minute = 60 seconds //2400;
        }
        log.info("Set DEFAULT_APPLICATION_SESSION_EXTENSION_TIME_IN_SECONDS to " + DEFAULT_APPLICATION_SESSION_EXTENSION_TIME_IN_SECONDS);

    }

    public static void updateApplicationToken(ApplicationToken applicationToken) {
        applicationTokenMap.put(applicationToken.getApplicationTokenId(), applicationToken);
    }

    public static ApplicationToken getUASApplicationToken() {
        return uasApplicationToken;
    }

    public static ApplicationToken getUIBApplicationToken() {
        return uibApplicationToken;
    }

    public static void addApplicationToken(ApplicationToken applicationToken) {

        long remainingSecs = (Long.parseLong(applicationToken.getExpires()) - System.currentTimeMillis()) / 1000;
        log.info("Added {} applicationID:{} applicationTokenId:{} - expires in {} seconds", applicationToken.getApplicationName(), applicationToken.getApplicationID(), applicationToken.getApplicationTokenId(), remainingSecs);
        applicationTokenMap.put(applicationToken.getApplicationTokenId(), applicationToken);
        if (applicationToken.getApplicationID().equalsIgnoreCase("2212")) {
            uasApplicationToken = applicationToken;
        } else if (applicationToken.getApplicationID().equalsIgnoreCase("2210")) {
            uibApplicationToken = applicationToken;
        }


        if (applicationCryptoKeyMap.containsKey(applicationToken.getApplicationTokenId())) {
            // Maybe update key here...
            log.debug("updating cryptokey for applicationId: {} with applicationTokenId:{}", applicationToken.getApplicationID(), applicationToken.getApplicationTokenId());
            try {
                String iv = "MDEyMzQ1Njc4OTBBQkNERQ==";
                ExchangeableKey applicationKey = new ExchangeableKey();
                applicationKey.setEncryptionSecret(System.currentTimeMillis() + applicationToken.getApplicationTokenId());
                applicationKey.setIv(new IvParameterSpec(decoder.decode(iv)));
                applicationCryptoKeyMap.put(applicationToken.getApplicationTokenId(), applicationKey.toJsonEncoded());
            } catch (Exception e) {
                log.warn("Unable to create cryotokey for applicationIs: {}", applicationToken.getApplicationID());
            }
        } else {
            // Bootstrap key initialization
            log.debug("adding new cryptokey for applicationId: {} with applicationTokenId:{}", applicationToken.getApplicationID(), applicationToken.getApplicationTokenId());
            try {
                String iv = "MDEyMzQ1Njc4OTBBQkNERQ==";
                ExchangeableKey applicationKey = new ExchangeableKey();
                applicationKey.setEncryptionSecret(applicationToken.getApplicationTokenId());
                applicationKey.setIv(new IvParameterSpec(decoder.decode(iv)));
                applicationCryptoKeyMap.put(applicationToken.getApplicationTokenId(), applicationKey.toJsonEncoded());
            } catch (Exception e) {
                log.warn("Unable to create cryotokey for applicationIs: {}", applicationToken.getApplicationID());
            }
        }
    }

    public static boolean updateApplicationTokenWithDetailsFromApplication(ApplicationToken applicationToken, Application application) {
        if (applicationToken == null) {
            return false;
        }
        if (application == null) {
            return false;
        }
        boolean tokenChanged = false;
        {
            // update application name if changed
            if (!applicationToken.getApplicationName().equals(application.getName())) {
                applicationToken.setApplicationName(application.getName());
                tokenChanged = true;
            }
        }
        {
            // update application secret if changed
            if (!applicationToken.getApplicationSecret().equals(application.getSecurity().getSecret())) {
                applicationToken.setApplicationSecret(application.getSecurity().getSecret());
                tokenChanged = true;
            }
        }
        {
            // update tags if changed
            Set<Tag> tokenTagSet = new LinkedHashSet<>(applicationToken.getTags());
            List<Tag> applicationTagList = ApplicationTagMapper.getTagList(application.getTags());
            Set<Tag> applicationTagSet = new LinkedHashSet<>(applicationTagList);
            if (!tokenTagSet.equals(applicationTagSet)) {
                applicationToken.setTags(applicationTagList);
                tokenChanged = true;
            }
        }
        return tokenChanged;
    }

    public static ApplicationToken getApplicationToken(String applicationtokenid) {
        ApplicationToken applicationToken = applicationTokenMap.get(applicationtokenid);
        if (applicationToken == null) {
            return null;
        }
        if (isApplicationTokenExpired(applicationToken)) {
            //log.warn("Attempting to get an expired applicationtoken.  ApplicationId:{} ApplicationTokenId:{}", applicationToken.getApplicationID(), applicationtokenid);
            applicationTokenMap.remove(applicationToken.getApplicationTokenId());
            applicationCryptoKeyMap.remove(applicationToken.getApplicationTokenId());
            return null;
        }
        Application application = ApplicationModelFacade.getApplication(applicationToken.getApplicationID());
        if (application!= null && updateApplicationTokenWithDetailsFromApplication(applicationToken, application)) {
            updateApplicationToken(applicationToken);
        }
        return applicationToken;
    }

    public static ExchangeableKey getExchangeableKeyForApplicationToken(ApplicationToken applicationToken) {
        ExchangeableKey exchangeableKey = new ExchangeableKey(applicationCryptoKeyMap.get(applicationToken.getApplicationTokenId()));
        if (exchangeableKey.getIv() == null) {
            log.warn("Attempt fo find key for applicationID:{} with applicationTokenId:[} failed", applicationToken.getApplicationID(), applicationToken.getApplicationTokenId());
            return null;
        }
        return exchangeableKey;
    }

    public static boolean verifyApplicationToken(ApplicationToken applicationToken) {
        if (applicationToken == null) {
            return false;
        }
        ApplicationToken applicationTokenFromMap = applicationTokenMap.get(applicationToken.getApplicationTokenId());
        if (applicationTokenFromMap == null) {
            return false;
        }
        if (isApplicationTokenExpired(applicationTokenFromMap)) {
            applicationTokenMap.remove(applicationTokenFromMap.getApplicationTokenId());
            applicationCryptoKeyMap.remove(applicationTokenFromMap.getApplicationTokenId());

            return false;
        }
        return true;
    }

    public static boolean verifyApplicationTokenId(String applicationtokenid) {
        return applicationTokenMap.get(applicationtokenid) != null;
    }

    public static ApplicationToken renewApplicationTokenId(String applicationtokenid) {
        ApplicationToken renewApplicationToken = applicationTokenMap.get(applicationtokenid);  // Can't remove as verify check in map
        String exchangeableKey = applicationCryptoKeyMap.get(applicationtokenid);  // Can't remove as verify check in map
        if (verifyApplicationToken(renewApplicationToken)) {
            //applicationTokenMap.remove(applicationtokenid);
            //applicationCryptoKeyMap.remove(applicationtokenid); //HUYDO: BUG HERE, don't remove. It should be there as we are extending the expiry date
            String oldExpires = renewApplicationToken.getExpiresFormatted();
            renewApplicationToken.setExpires(String.valueOf(new ApplicationTokenExpires(DEFAULT_APPLICATION_SESSION_EXTENSION_TIME_IN_SECONDS * 1000).getValueAsAbsoluteTimeInMilliseconds()));
            log.info("Updated expiry for applicationId:{} applicationtokenid:{} oldExpiry:{}, newExpiry: {}", renewApplicationToken.getApplicationID(), applicationtokenid, oldExpires, renewApplicationToken.getExpiresFormatted());
            applicationTokenMap.put(renewApplicationToken.getApplicationTokenId(), renewApplicationToken);
            if (renewApplicationToken.getApplicationID().equalsIgnoreCase("2212")) {
                uasApplicationToken = renewApplicationToken;
            } else if (renewApplicationToken.getApplicationID().equalsIgnoreCase("2210")) {
                uibApplicationToken = renewApplicationToken;
            }

            log.debug("updating cryptokey for applicationId: {} with applicationTokenId:{}", renewApplicationToken.getApplicationID(), renewApplicationToken.getApplicationTokenId());
            try {
                String iv = "MDEyMzQ1Njc4OTBBQkNERQ==";
                ExchangeableKey applicationKey = new ExchangeableKey();
                applicationKey.setEncryptionSecret(System.currentTimeMillis() + renewApplicationToken.getApplicationTokenId());
                applicationKey.setIv(new IvParameterSpec(decoder.decode(iv)));
                applicationCryptoKeyMap.put(renewApplicationToken.getApplicationTokenId(), applicationKey.toJsonEncoded());
            } catch (Exception e) {
                log.warn("Unable to create cryotokey for applicationId: {}, re-using old", renewApplicationToken.getApplicationID());
                applicationCryptoKeyMap.put(renewApplicationToken.getApplicationTokenId(), exchangeableKey);
            }
            Application application = ApplicationModelFacade.getApplication(renewApplicationToken.getApplicationID());
            if (updateApplicationTokenWithDetailsFromApplication(renewApplicationToken, application)) {
                updateApplicationToken(renewApplicationToken);
            }
            return renewApplicationToken;
        }
        return null;
    }


    public static String getApplicationIdFromApplicationTokenID(String applicationtokenid) {
        ApplicationToken applicationToken = applicationTokenMap.get(applicationtokenid);
        if (applicationToken != null) {
            return applicationToken.getApplicationID();
        }
        log.error("getApplicationIdFromApplicationTokenId - Unable to find applicationId for applicationTokenId=" + applicationtokenid);
        return "";
    }

    public static String getApplicationNameFromApplicationTokenID(String applicationtokenid) {
        ApplicationToken applicationToken = getApplicationToken(applicationtokenid);
        if (applicationToken != null) {
            return applicationToken.getApplicationName();
        }
        log.error("getApplicationNameFromApplicationTokenId - Unable to find applicationName for applicationTokenId=" + applicationtokenid);
        SlackNotifications.sendAlarm("getApplicationNameFromApplicationTokenId - Unable to find applicationName for applicationTokenId=" + applicationtokenid);
        return "";
    }

    public static String getApplicationCryptoKeyFromApplicationTokenID(String applicationtokenid) {
        String applicationKey = applicationCryptoKeyMap.get(applicationtokenid);
        return applicationKey;
    }


    public static boolean isApplicationTokenExpired(ApplicationToken applicationtoken) {

        Long expires = Long.parseLong(applicationtoken.getExpires());
        //log.info("Checking {} for timeout, ",applicationtoken.getApplicationID());
        Long now = System.currentTimeMillis();
        if (expires > now) {
            log.trace("Checking applicationId {} for timeout, result false", applicationtoken.getApplicationID());
            return false;
        }
        log.trace("Checking applicationId {} for timeout, result: false", applicationtoken.getApplicationID());
        return true;
    }


    public static int getMapSize() {
        return applicationTokenMap.size();
    }

    public static int getKeyMapSize() {
        return applicationCryptoKeyMap.size();
    }

    public static ObjectNode getActiveApplications() {
        Map<String, Integer> applicationMap = new HashMap<>();
        for (Map.Entry<String, ApplicationToken> entry : applicationTokenMap.entrySet()) {
            // Let us use ApplicationID to identify applications without applicationName
            String applicationIdentifier;
            if (entry.getValue().getApplicationName() == null || entry.getValue().getApplicationName().length() < 2) {
                applicationIdentifier = entry.getValue().getApplicationID();
            } else {
                applicationIdentifier = entry.getValue().getApplicationName() + "[" + entry.getValue().getApplicationID() + "]";
            }
            if (applicationMap.get(applicationIdentifier) != null) {
                applicationMap.put(applicationIdentifier, 1 + applicationMap.get(applicationIdentifier));
            } else {
                applicationMap.put(applicationIdentifier, 1);
            }
        }
        logActiveApplicationTokenIDs();
        ObjectNode result = mapper.convertValue(applicationMap, ObjectNode.class);
        return result;
    }


    public static void logActiveApplicationTokenIDs() {

        // Do not mess up the log completely...  :)
        if (ThreadLocalRandom.current().nextInt(1, 100 + 1) < 2) {
            String lognString = "";
            for (Map.Entry<String, ApplicationToken> entry : applicationTokenMap.entrySet()) {
                lognString = lognString + entry.getValue().getApplicationTokenId() + "(" + entry.getValue().getApplicationName() + "), ";
            }
            log.trace("Active applicationTokenIds: " + lognString);

        }
    }

    /**
     * @return a singleton STSApplicationToken
     */
    public static ApplicationToken getSTSApplicationToken() {
        String applicationName = appConfig.getProperty("applicationname");
        String applicationId = appConfig.getProperty("applicationid");
        String applicationsecret = appConfig.getProperty("applicationsecret");
        // Do not create duplicate sts sessions
        if (mySTSApplicationTokenId.equals("") || !applicationTokenMap.containsKey(mySTSApplicationTokenId)) {  // First time
            ApplicationCredential ac = new ApplicationCredential(applicationId, applicationName, applicationsecret);
            mySTSApplicationToken = ApplicationTokenMapper.fromApplicationCredentialXML(ApplicationCredentialMapper.toXML(ac));
            mySTSApplicationToken.setExpires(String.valueOf(new ApplicationTokenExpires(DEFAULT_APPLICATION_SESSION_EXTENSION_TIME_IN_SECONDS * 1000L * STS_TOKEN_MULTIPLIER).getValue()));  // 100 times the default
            mySTSApplicationTokenId = mySTSApplicationToken.getApplicationTokenId();
            Application application = ApplicationModelFacade.getApplication(applicationId);
            updateApplicationTokenWithDetailsFromApplication(mySTSApplicationToken, application);
            addApplicationToken(mySTSApplicationToken);
        } else {  // update expires
            mySTSApplicationToken = applicationTokenMap.get(mySTSApplicationTokenId);
            mySTSApplicationToken.setExpires(String.valueOf(new ApplicationTokenExpires(DEFAULT_APPLICATION_SESSION_EXTENSION_TIME_IN_SECONDS * 1000L * STS_TOKEN_MULTIPLIER).getValue()));  // 100 times the default
            Application application = ApplicationModelFacade.getApplication(applicationId);
            updateApplicationTokenWithDetailsFromApplication(mySTSApplicationToken, application);
            //very costly to generate key every time, just update
            //addApplicationToken(mySTSApplicationToken);
            updateApplicationToken(mySTSApplicationToken);
        }
        return mySTSApplicationToken;

    }

    public static void cleanApplicationTokenMap() {
        // OK... let us obfucscate/filter sessionsid's in signalEmitter field
        for (Map.Entry<String, ApplicationToken> entry : applicationTokenMap.entrySet()) {
            ApplicationToken applicationToken = entry.getValue();
            if (isApplicationTokenExpired(applicationToken)) {
                applicationTokenMap.remove(applicationToken.getApplicationTokenId());
                applicationCryptoKeyMap.remove(applicationToken.getApplicationTokenId());
            }
        }
    }

}
