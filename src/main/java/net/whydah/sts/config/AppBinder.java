package net.whydah.sts.config;

import java.net.URI;

import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.whydah.sso.config.ApplicationMode;
import net.whydah.sts.user.authentication.DummyUserAuthenticator;
import net.whydah.sts.user.authentication.UserAuthenticator;
import net.whydah.sts.user.authentication.UserAuthenticatorImpl;
import org.glassfish.hk2.api.Immediate;

public class AppBinder extends AbstractBinder {
	
	private final static Logger log = LoggerFactory.getLogger(AppBinder.class);

	 
    private final String applicationmode;

    public AppBinder(String applicationmode) {
      
        this.applicationmode = applicationmode;
    }
    
    @Override
    protected void configure() {
    	
    	if (applicationmode.equals(ApplicationMode.DEV)) {
            log.info("Using TestUserAuthenticator to handle usercredentials");
            bind(DummyUserAuthenticator.class).to(UserAuthenticator.class);
        } else {
        	bind(UserAuthenticatorImpl.class).to(UserAuthenticator.class);
        	//bind(UserAuthenticatorImpl.class).to(UserAuthenticator.class);
        }
    	
    	
        
    }
}