package net.whydah.token.application;

import net.whydah.sso.application.types.Application;
import net.whydah.sso.application.types.ApplicationToken;
import net.whydah.token.config.ApplicationModelHelper;

public class SessionHelper {
	
	public static int defaultlifespan = 245000;
	public static int getApplicationLifeSpan(String applicationtokenid){

		ApplicationToken appToken = AuthenticatedApplicationRepository.getApplicationToken(applicationtokenid);
		
		if(appToken!=null){
			Application app = ApplicationModelHelper.getApplication(appToken.getApplicationID());

			//set the correct timeout depends on the application's security
			if(app!=null){
			
				return getApplicationLifeSpan(app);
			}
		}
		
		return defaultlifespan;
	}
	
	public static int getApplicationLifeSpan(Application app) {
		//TODO: a correlation between securityLevel and lifespan?
		//return Integer.valueOf(app.getSecurity().getMaxSessionTimeoutSeconds());
		
		return defaultlifespan;
	}

}