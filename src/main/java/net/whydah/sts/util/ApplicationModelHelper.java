package net.whydah.sts.util;

import net.whydah.sso.application.types.Application;
import net.whydah.sso.application.types.ApplicationToken;
import net.whydah.sso.ddd.model.application.ApplicationId;
import net.whydah.sso.ddd.model.application.ApplicationTokenID;
import net.whydah.sts.application.ApplicationModelFacade;
import net.whydah.sts.application.AuthenticatedApplicationTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.whydah.sts.user.AuthenticatedUserTokenRepository.DEFAULT_USER_SESSION_EXTENSION_TIME_IN_MILLISECONDS;

public class ApplicationModelHelper {

    private static final Logger log = LoggerFactory.getLogger(ApplicationModelHelper.class);

    public static long getUserTokenLifeSpanInMilliSecondsFromApplicationTokenId(String applicationtokenid) {
        ApplicationToken appToken = AuthenticatedApplicationTokenRepository.getApplicationToken(applicationtokenid);
        if(appToken!=null){
			Application app = ApplicationModelFacade.getApplication(appToken.getApplicationID());
			//set the correct timeout depends on the application's security
			if(app!=null){
				long lifespan = getUserTokenLifeSpanInMilliSecondsFromApplication(app);
                log.debug("Returning ApplicationToken applicationlifespanseconds:{} for applicationtokenid:{}", lifespan, applicationtokenid);
                return lifespan;
            }
		}
        log.debug("Returning ApplicationToken defaultlifespanseconds:{} for applicationtokenid:{}", DEFAULT_USER_SESSION_EXTENSION_TIME_IN_MILLISECONDS, applicationtokenid);
        return DEFAULT_USER_SESSION_EXTENSION_TIME_IN_MILLISECONDS;
    }

    public static long getUserTokenLifeSpanInMilliSecondsFromApplication(Application app) {
        if (app.getSecurity() != null) {
            long maxUserSessionFromApplication = Long.valueOf(app.getSecurity().getMaxSessionTimeoutSeconds()); //in seconds
            log.debug("Found configured aplication MaxUserSessionTimeoutSeconds:{} for Application:{}", maxUserSessionFromApplication, app.getName());
            return maxUserSessionFromApplication * 1000;
        }
        return DEFAULT_USER_SESSION_EXTENSION_TIME_IN_MILLISECONDS;
    }

    public static ApplicationId getApplicationId(ApplicationTokenID applicationtokenid) {
        ApplicationToken appToken = AuthenticatedApplicationTokenRepository.getApplicationToken(applicationtokenid.getId());
        if (appToken != null) {
            Application app = ApplicationModelFacade.getApplication(appToken.getApplicationID());

            //set the correct timeout depends on the application's security
            if (app != null) {
                return new ApplicationId(app.getId());
            }
        }
        log.warn("ApplicationId not for for ApplicationTokenId:{}", applicationtokenid);
        return null;
    }

}
