package net.whydah.sts.user;

import com.hazelcast.cluster.Member;
import com.hazelcast.config.Config;
import com.hazelcast.config.XmlConfigBuilder;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import net.whydah.sso.ddd.model.application.ApplicationTokenID;
import net.whydah.sso.ddd.model.base.BaseExpires;
import net.whydah.sso.ddd.model.sso.UserTokenLifespan;
import net.whydah.sso.ddd.model.user.LastSeen;
import net.whydah.sso.ddd.model.user.UserTokenId;
import net.whydah.sso.user.mappers.UserTokenMapper;
import net.whydah.sso.user.types.UserToken;
import net.whydah.sts.ServiceStarter;
import net.whydah.sts.config.AppConfig;
import net.whydah.sts.threat.ThreatResource;
import net.whydah.sts.user.statistics.UserSessionObservedActivity;
import net.whydah.sts.util.ApplicationModelHelper;
import net.whydah.sts.util.LogonTimeRepoter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.valuereporter.activity.ObservedActivity;
import org.valuereporter.client.MonitorReporter;

import java.io.FileNotFoundException;
import java.util.*;

public class AuthenticatedUserTokenRepository {
    private final static Logger log = LoggerFactory.getLogger(AuthenticatedUserTokenRepository.class);
    private final static Map<String, UserToken> activeusertokensmap;
    private final static Map<String, String> active_username_usertokenids_map;
    private final static Map<String, Date> lastSeenMap;
    private final static HazelcastInstance hazelcastInstance;
    public final static long DEFAULT_USER_SESSION_EXTENSION_TIME_IN_MILLISECONDS;
    //public static final long DEFAULT_USER_SESSION_TIME_IN_SECONDS;
    public final static LogonTimeRepoter logonReporter; 

    static {
        AppConfig appConfig = new AppConfig();
        logonReporter = new LogonTimeRepoter(appConfig.getProperty("uss.url"),
        		appConfig.getProperty("uss.accesstoken"));
        
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
        HazelcastInstance tmpInstance;
        try {
            tmpInstance = Hazelcast.newHazelcastInstance(hazelcastConfig);
        } catch(Exception ex) {
            tmpInstance = Hazelcast.newHazelcastInstance();
        }
        hazelcastInstance = tmpInstance;
        activeusertokensmap = hazelcastInstance.getMap(appConfig.getProperty("gridprefix") + "activeusertokensmap");
        active_username_usertokenids_map = hazelcastInstance.getMap(appConfig.getProperty("gridprefix") + "active_username_usertokenids_map");

        log.info("Connecting to map {} - size: {}", appConfig.getProperty("gridprefix") + "activeusertokensmap", getMapSize());
        log.info("Connecting to map {} - size: {}", appConfig.getProperty("gridprefix") + "active_username_usertokenids_map", getActiveUsernameMapSize());

        lastSeenMap = hazelcastInstance.getMap(appConfig.getProperty("gridprefix") + "lastSeenMap");
        log.info("Connecting to map {} - size: {}", appConfig.getProperty("gridprefix") + "lastSeenMap", getLastSeenMapSize());
        //Set<Member> clusterMembers = hazelcastInstance.getCluster().getMembers();
        DEFAULT_USER_SESSION_EXTENSION_TIME_IN_MILLISECONDS = updateDefaultUserSessionExtensionTime(appConfig);

    }

    protected static long updateDefaultUserSessionExtensionTime(AppConfig appConfig) {
        long userSessionExtensionTime = 0;
        try{
            if (appConfig.getProperty("user.session.timeout") != null && UserTokenLifespan.isValid(appConfig.getProperty("user.session.timeout"))) {
                userSessionExtensionTime = new UserTokenLifespan(Long.parseLong(appConfig.getProperty("user.session.timeout"))).getTimeoutInterval();
            } else {
                userSessionExtensionTime = BaseExpires.addPeriod(Calendar.MONTH, 6);
            }
        } catch(Exception ex){
            userSessionExtensionTime = BaseExpires.addPeriod(Calendar.MONTH, 6);
        }
        log.info("DEFAULT_USER_SESSION is set to " + userSessionExtensionTime);
        return userSessionExtensionTime;
    }

    public static void setLastSeen(UserToken userToken) {
        if (userToken != null && userToken.getEmail() != null) {
            lastSeenMap.put(userToken.getEmail(), new Date());
        }
    }


    public static String getLastSeen(UserToken userToken) {
        if (userToken != null) {
            Date d = lastSeenMap.get(userToken.getEmail());
            if (d != null) {
                return d.toString();
            }
        }
        return LastSeen.WHITE_LIST[0];
    }

    public static String getLastSeenByEmail(String email) {
        if (email != null) {
            Date d = lastSeenMap.get(email);
            if (d != null) {
                return d.toString();
            }
        }
        return LastSeen.WHITE_LIST[0];
    }

    /**
     * Get UserToken from UserTokenRepository. If sts is not found or is not valid/timed out, null is returned.
     *
     * @param usertokenId userTokenId
     * @return UserToken if found and valid, null if not.
     */
    public static UserToken getUserToken(String usertokenId, String applicationTokenId) {
        log.debug("getUserToken with userTokenid=" + usertokenId);
        if (usertokenId == null) {
            return null;
        }
        UserToken resToken = activeusertokensmap.get(usertokenId);
        if (resToken != null && verifyUserToken(resToken, applicationTokenId)) {
            resToken.setLastSeen(AuthenticatedUserTokenRepository.getLastSeen(resToken));
            lastSeenMap.put(resToken.getEmail(), new Date());
            log.info("Valid userToken found: " + resToken);
            log.debug("userToken=" + resToken);

            ObservedActivity observedActivity = new UserSessionObservedActivity(resToken.getUid(), "userSessionAccess", applicationTokenId);
            MonitorReporter.reportActivity(observedActivity);
            log.trace("Adding activity to statistics cache {}", observedActivity);

            return resToken;
        }
        log.debug("No usertoken found for usertokenId=" + usertokenId);
        return null;
    }

    public static UserToken getUserTokenByUserName(String userName, String applicationTokenId) {
        log.debug("getUserToken with userName=" + userName);
        if (userName == null) {
            return null;
        }
        if (active_username_usertokenids_map.containsKey(userName)) {
            String usertokenid = active_username_usertokenids_map.get(userName);
            UserToken resToken = activeusertokensmap.get(usertokenid);
            if (resToken != null && verifyUserToken(resToken, applicationTokenId)) {
                resToken.setLastSeen(AuthenticatedUserTokenRepository.getLastSeen(resToken));
                lastSeenMap.put(resToken.getEmail(), new Date());
                log.info("Valid userToken found: " + resToken);
                log.debug("userToken=" + resToken);

                ObservedActivity observedActivity = new UserSessionObservedActivity(resToken.getUid(), "userSessionAccess", applicationTokenId);
                MonitorReporter.reportActivity(observedActivity);
                log.trace("Adding activity to statistics cache {}", observedActivity);

                return resToken;
            }
        }

        log.debug("No usertoken found for username=" + userName);
        return null;
    }

    /**
     * Check if sts exists in UserTokenRepository, and is valid and not timed out.
     *
     * @param userToken UserToken
     * @return true if sts is valid.
     */
    public static boolean verifyUserToken(UserToken userToken, String applicationTokenId) {
        if (userToken.getUserTokenId() == null) {
            log.info("UserToken not valid, missing UserTokenId");
            return false;
        }
        if (userToken.getEmail() != null) {
            lastSeenMap.put(userToken.getEmail(), new Date());

        }
        if (!ApplicationTokenID.isValid(applicationTokenId)) {
            log.debug("Matching against invalid ApplicationTokenId: {} - returning false", applicationTokenId);
            return false;
        }
        UserToken resToken = activeusertokensmap.get(userToken.getUserTokenId());
        if (resToken == null) {
            log.info("UserToken not found in repo.");
            return false;
        }
        log.debug("UserToken from repo: {}", resToken);
        if (!resToken.isValid()) {
            log.debug("resToken is not valid");
            activeusertokensmap.remove(userToken.getUserTokenId());
            active_username_usertokenids_map.remove(userToken.getUserName());

            return false;
        }
        if (!userToken.toString().equals(resToken.toString())) {
            log.info("UserToken not valid: not the same as in repo. userToken: {}  repoToken: {}", userToken, resToken);
            return false;
        }

        ObservedActivity observedActivity = new UserSessionObservedActivity(resToken.getUid(), "userSessionVerification", applicationTokenId);
        MonitorReporter.reportActivity(observedActivity);

        return true;
    }

    public static void renewUserToken(String usertokenid, String applicationTokenId) {
        UserToken userToken = activeusertokensmap.remove(usertokenid);
        active_username_usertokenids_map.remove(userToken.getUserName());
        userToken.setDefcon(ThreatResource.getDEFCON());
        userToken.setTimestamp(String.valueOf(System.currentTimeMillis()));

        addUserToken(userToken, applicationTokenId, "renew", false);
        ObservedActivity observedActivity = new UserSessionObservedActivity(userToken.getUid(), "userSessionRenewal", applicationTokenId);
        MonitorReporter.reportActivity(observedActivity);

    }

	private static void applyUserLifespan(UserToken userToken, long applicationUserTokenLifespan) {
		//userToken.setLifespan(String.valueOf(applicationUserTokenLifespan));
		if (applicationUserTokenLifespan < DEFAULT_USER_SESSION_EXTENSION_TIME_IN_MILLISECONDS) {
            userToken.setLifespan(String.valueOf(applicationUserTokenLifespan * 1000));
        } else {
            userToken.setLifespan(String.valueOf(DEFAULT_USER_SESSION_EXTENSION_TIME_IN_MILLISECONDS));
        }
	}

    public static UserToken refreshUserToken(String applicationTokenId, UserToken refreshedUserToken) {
        //UserToken oldusertoken = activeusertokensmap.remove(usertokenid);
        return addUserToken(refreshedUserToken, applicationTokenId, "refresh", false);

    }

    private static String generateID() {
        return UUID.randomUUID().toString();
    }
    
    public static UserToken addUserToken(UserToken userToken, String applicationTokenId, String authType) {
    	return addUserToken(userToken, applicationTokenId, authType, true);
    }

    private static UserToken addUserToken(UserToken userToken, String applicationTokenId, String authType, boolean useExistingLifespan) {

    	 if (!UserTokenId.isValid(userToken.getUserTokenId())) {
             log.error("Error: UserToken has no valid usertokenid, generating new userTokenId");
             userToken.setUserTokenId(generateID());
         }
    	 if(authType.equalsIgnoreCase("pin")) {
    		 userToken.setSecurityLevel("0");
    	 }
    	 if(!useExistingLifespan) {
    		 try {

    			 long applicationUserTokenLifespan = ApplicationModelHelper.getUserTokenLifeSpan(applicationTokenId);
    			 applyUserLifespan(userToken, applicationUserTokenLifespan);
    			 log.debug("addUserToken: found applicationUserTokenLifespan {} for application token Id {} - apply the lifespan (ms) to {}/{}", applicationUserTokenLifespan, applicationTokenId, userToken.getLifespan(), new UserTokenLifespan(userToken.getLifespan()).getDateFormatted());
    		 } catch (Exception e) {
    			 log.warn("addUserToken called without resolveable aplicationTokenId:{}", applicationTokenId);
    		 }

         }
        userToken.setTimestamp(String.valueOf(System.currentTimeMillis()));


        if (userToken.getEmail() != null) {
            String lastSeenString = AuthenticatedUserTokenRepository.getLastSeen(userToken);
            userToken.setLastSeen(lastSeenString);
            lastSeenMap.put(userToken.getEmail(), new Date());

        }
        if (ServiceStarter.getPublicKeyPair() != null) {
            String encryptedSignature = userToken.getEncryptedSignature(ServiceStarter.getPublicKeyPair());
        }

        activeusertokensmap.put(userToken.getUserTokenId(), userToken);
        logonReporter.update(userToken);
        log.info("Added userToken with id {}", userToken.getUserTokenId());

        if (userToken.getUserName() != null) {
            active_username_usertokenids_map.put(userToken.getUserName(), userToken.getUserTokenId());
        }
        if ("renew".equalsIgnoreCase(authType)) {
            return userToken;  // already reported
        }
         if ("refresh".equalsIgnoreCase(authType)) {
             return userToken;  // already reported
         }
         ObservedActivity observedActivity = new UserSessionObservedActivity(userToken.getUid(), "userSessionCreatedByPassword", applicationTokenId);
         if ("pin".equalsIgnoreCase(authType)) {
             observedActivity = new UserSessionObservedActivity(userToken.getUid(), "userSessionCreatedByPin", applicationTokenId);
         }
         MonitorReporter.reportActivity(observedActivity);
         return userToken;
    }

   
    public static void removeUserToken(String userTokenId, String applicationTokenId) {
        UserToken removedToken = activeusertokensmap.remove(userTokenId);
        active_username_usertokenids_map.remove(removedToken.getUserName());

        ObservedActivity observedActivity = new UserSessionObservedActivity(removedToken.getUid(), "userSessionRemoved", applicationTokenId);
        MonitorReporter.reportActivity(observedActivity);

    }

    public static void initializeDistributedMap() {
    }

    public static int getMapSize() {
        logUserTokenMap();
        return activeusertokensmap.size();
    }

    public static int getActiveUsernameMapSize() {
        logUserTokenMap();
        return active_username_usertokenids_map.size();
    }

    public static void logUserTokenMap() {
//        String logString = "";
//        for (Map.Entry<String, UserToken> entry : activeusertokensmap.entrySet()) {
//            logString = logString + entry.getValue().getUserTokenId() + "(" + entry.getValue().getUserName() + "), ";
//        }
        log.debug("UserToken mapsize :{} - content:{}", activeusertokensmap.size(), activeusertokensmap);
    }

    public static int getLastSeenMapSize() {
        return lastSeenMap.size();
    }

    public static int getNoOfClusterMembers() {
        Set<Member> clusterMembers = hazelcastInstance.getCluster().getMembers();
        return clusterMembers.size();
    }


    public static void cleanUserTokenMap() {
        // OK... let us obfucscate/filter sessionsid's in signalEmitter field
        for (Map.Entry<String, UserToken> entry : activeusertokensmap.entrySet()) {
            UserToken userToken = entry.getValue();
            if (!userToken.isValid()) {
                log.debug("Removed userTokenID {} - marked as invalid, userName: {}, getLastName: {}, getLifespanFormatted: {}  UserTokenXML:  {}", userToken.getUserTokenId(),userToken.getUserName(),userToken.getLastName(),userToken.getLifespanFormatted(), UserTokenMapper.toXML(userToken));
                activeusertokensmap.remove(userToken.getUserTokenId());
            } else {
               // log.debug("Checked userTokenID {} - marked as valid, userName: {}, getLastName: {}, getLifespanFormatted: {}  UserTokenXML:  {}", userToken.getUserTokenId(),userToken.getUserName(),userToken.getLastName(),userToken.getLifespanFormatted(), UserTokenMapper.toXML(userToken));

            }
//            if (new UserTokenLifespan(userToken.getLifespan()).getValueAsAbsoluteTimeInMilliseconds() < System.currentTimeMillis()) {
//                log.debug("Removed userTokenID {} - marked as timeout", userToken.getUserTokenId());
//                activeusertokensmap.remove(userToken.getUserTokenId());
//            }
        }
    }

}
