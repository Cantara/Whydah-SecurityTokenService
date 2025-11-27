package net.whydah.sts.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.exoreaction.notification.SlackNotificationService;
import com.exoreaction.notification.config.SlackConfig;
import com.exoreaction.notification.config.SlackConfigBuilder;
import com.exoreaction.notification.helper.SlackNotificationHelperFactory;

import net.whydah.sso.config.ApplicationMode;
import net.whydah.sts.smsgw.LoggingDLRHandler;
import net.whydah.sts.smsgw.Target365DLRHandler;
import net.whydah.sts.user.authentication.DummyUserAuthenticator;
import net.whydah.sts.user.authentication.UserAuthenticator;
import net.whydah.sts.user.authentication.UserAuthenticatorImpl;

public class AppBinder extends AbstractBinder {
    
    private final static Logger log = LoggerFactory.getLogger(AppBinder.class);
    private final String applicationmode;
    private SlackNotificationService slackService;
    private boolean slackEnabled = false;

    public AppBinder(String applicationmode) {
        this.applicationmode = applicationmode;
    }
    
    @Override
    protected void configure() {
       
        bind(Target365DLRHandler.class).to(LoggingDLRHandler.class);
        
        // Bind UserAuthenticator
        if (ApplicationMode.DEV.equals(applicationmode)) {
            log.info("Using TestUserAuthenticator to handle usercredentials");
            bind(DummyUserAuthenticator.class).to(UserAuthenticator.class);
        } else {
            bind(UserAuthenticatorImpl.class).to(UserAuthenticator.class);
        }
    }

    private boolean initSlackNotifier() {
        Path slackPropertiesPath = Paths.get("slack.properties");
        
        if (!Files.exists(slackPropertiesPath)) {
            log.warn("slack.properties file not found at: {}. Slack notifications disabled.", 
                    slackPropertiesPath.toAbsolutePath());
            return false;
        }
        
        try {
            SlackConfig config = SlackConfigBuilder.fromPropertiesFile(slackPropertiesPath);
            
            if (!config.isEnabled()) {
                log.info("Slack notifications are disabled in configuration");
                return false;
            }

            slackService = new SlackNotificationService(config);
            slackService.initialize();
            
            log.info("SlackNotificationService initialized - enabled: {}, channels: {}", 
                    config.isEnabled(), config.getChannels().size());
            
            SlackNotificationHelperFactory helperFactory = 
                new SlackNotificationHelperFactory(slackService, applicationmode, "STS");
            
            // Bind the services
            bind(slackService).to(SlackNotificationService.class);
            bind(helperFactory).to(SlackNotificationHelperFactory.class);
            
            log.info("Slack notification services bound successfully");
            return true;
            
        } catch (IOException e) {
            log.error("Failed to initialize Slack notification service: {}", e.getMessage(), e);
            return false;
        } catch (Exception e) {
            log.error("Unexpected error initializing Slack notification service", e);
            return false;
        }
    }
    
    public void shutdown() {
        if (slackService != null) {
            try {
                log.info("Shutting down Slack notification service");
                slackService.clearService("STS");
            } catch (Exception e) {
                log.error("Error during Slack service shutdown", e);
            }
        }
    }
}