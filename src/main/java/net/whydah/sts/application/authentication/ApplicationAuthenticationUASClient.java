package net.whydah.sts.application.authentication;

import static net.whydah.sso.session.WhydahApplicationSession.createThreat;
import static net.whydah.sts.health.HealthResource.getRunningSince;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.exoreaction.notification.util.ContextMapBuilder;

import net.whydah.sso.application.mappers.ApplicationMapper;
import net.whydah.sso.application.mappers.ApplicationTagMapper;
import net.whydah.sso.application.types.Application;
import net.whydah.sso.application.types.ApplicationCredential;
import net.whydah.sso.application.types.ApplicationToken;
import net.whydah.sso.commands.adminapi.application.CommandGetApplication;
import net.whydah.sso.user.types.UserToken;
import net.whydah.sts.application.AuthenticatedApplicationTokenRepository;
import net.whydah.sts.application.authentication.commands.CommandCheckApplicationCredentialInUAS;
import net.whydah.sts.config.AppConfig;
import net.whydah.sts.health.HealthResource;
import net.whydah.sts.slack.SlackNotifications;



public class ApplicationAuthenticationUASClient {

    private final static Logger log = LoggerFactory.getLogger(ApplicationAuthenticationUASClient.class);

    private static AppConfig appConfig = new AppConfig();
    
    public static long lastChecked = 0;
    
    public static long WAITING_TIME_TO_CHECK_IN_SECONDS = 60;


    public static boolean checkAppsecretFromUAS(ApplicationCredential applicationCredential) {

        //if(System.currentTimeMillis() - lastChecked < WAITING_TIME_TO_CHECK_IN_SECONDS*1000) {
        //	return true;
        //}
        //lastChecked = System.currentTimeMillis();
        String useradminservice = appConfig.getProperty("useradminservice");

        //HUY fix the start-up problem here
        //should bypass request from UAS/UIB if the credential is found correct
        String secret = appConfig.getProperty(applicationCredential.getApplicationID());
        if (secret != null && applicationCredential.getApplicationSecret().equals(secret)) {
            return true;
        }

        /**
         * Command version of UAS auth call
         */
        try {
        	ApplicationToken stsToken = AuthenticatedApplicationTokenRepository.getSTSApplicationToken();
            boolean isOKinUAS = new CommandCheckApplicationCredentialInUAS(URI.create(useradminservice), stsToken.getApplicationTokenId(), applicationCredential).execute();
            log.debug("CommandCheckApplicationCredentialInUAS returned: {}", isOKinUAS);
            if (isOKinUAS) {
                return true;
            }
        } catch (Exception e) {
            log.info("Unable to access UAS by Command", e);
            SlackNotifications.handleException(e);
        }

        // Avoid bootstrap signalling the first 5 seconds
        if (Instant.now().getEpochSecond() - getRunningSince().getEpochSecond() > 5000) {
            HealthResource.addThreatSignal(createThreat("Illegal application tried to access whydah. ApplicationID: " + applicationCredential.getApplicationID() + ", Response from UAS: "));
        }
        return false;
    }


    public static ApplicationToken addApplicationTagsFromUAS(ApplicationToken applicationToken, UserToken adminUserToken) {
        Objects.requireNonNull(applicationToken);
        Objects.requireNonNull(adminUserToken);


        String useradminservice = appConfig.getProperty("useradminservice");

        try {
            ApplicationToken stsToken = AuthenticatedApplicationTokenRepository.getSTSApplicationToken();
            
            String json = new CommandGetApplication(URI.create(useradminservice), stsToken.getApplicationTokenId(), adminUserToken.getUserTokenId(), applicationToken.getApplicationID()).execute();
            if(json!=null) {
            	Application application = ApplicationMapper.fromJson(json);
            	log.debug("CommandGetApplication returned: {}", application);
            	if (application != null) {
            		applicationToken.setTags(ApplicationTagMapper.getTagList(application.getTags()));
            		return applicationToken;
            	}
            } else {
            	log.warn("Cannot set tags for apptoken {}, appid {}, appname {}. CommandGetApplication failed to execute", applicationToken.getApplicationTokenId(), applicationToken.getApplicationID(), applicationToken.getApplicationName());
            	SlackNotifications.sendAlarm("addApplicationTagsFromUAS failed", 
            			ContextMapBuilder.of("apptoken",  applicationToken.getApplicationTokenId(),
            					"appid", applicationToken.getApplicationID(),
            					"appname", applicationToken.getApplicationName()
            					));
            }
        } catch (Exception e) {
            log.info("Unable to access UAS by Command", e);
            SlackNotifications.handleException(e);
        }

        return applicationToken;
    }

}
