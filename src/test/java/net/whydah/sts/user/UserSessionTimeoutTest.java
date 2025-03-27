package net.whydah.sts.user;

import net.whydah.sso.config.ApplicationMode;
import net.whydah.sso.ddd.model.base.BaseExpires;
import net.whydah.sso.ddd.model.sso.UserTokenLifespan;
import net.whydah.sts.config.AppConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;


public class UserSessionTimeoutTest {

    private final static Logger log = LoggerFactory.getLogger(UserTokenTest.class);

    @BeforeAll
    public static void shared() {
        Map<String, String> envs = new HashMap<>();
        envs.put(ApplicationMode.IAM_MODE_KEY, ApplicationMode.DEV);
        envs.put("user.session.timeout", "240000");
        EnvHelper.setEnv(envs);

    }
    
    long DEFAULT_USER_SESSION_EXTENSION_TIME_IN_SECONDS=0;

    @Test
    public void testSettingDefaultUserTokenLifespan() throws Exception {
        try {
            AppConfig appConfig = new AppConfig();
           
            if (UserTokenLifespan.isValid(appConfig.getProperty("user.session.timeout")) && appConfig.getProperty("user.session.timeout") != null) {
                //        if (userTokenDefaultTimeout != null && (Long.parseLong(userTokenDefaultTimeout) > 0)) {
                DEFAULT_USER_SESSION_EXTENSION_TIME_IN_SECONDS = new UserTokenLifespan(Long.parseLong(appConfig.getProperty("user.session.timeout")) * 1000L).getSecondValue();
                log.info("Updated DEFAULT_USER_SESSION_EXTENSION_TIME_IN_SECONDS to " + DEFAULT_USER_SESSION_EXTENSION_TIME_IN_SECONDS);
                //DEFAULT_USER_SESSION_TIME_IN_SECONDS = DEFAULT_USER_SESSION_EXTENSION_TIME_IN_SECONDS / 2;
                //log.info("Updated DEFAULT_USER_SESSION_TIME_IN_SECONDS to " + DEFAULT_USER_SESSION_TIME_IN_SECONDS);
            } else {
                DEFAULT_USER_SESSION_EXTENSION_TIME_IN_SECONDS = BaseExpires.addPeriod(Calendar.MONTH, 6); // 14 * 24 * 60 * 60;  // 14 days
                //DEFAULT_USER_SESSION_TIME_IN_SECONDS = 7 * 24 * 60 * 60;  // 7 days

            }
        } catch (Exception ex) {
            DEFAULT_USER_SESSION_EXTENSION_TIME_IN_SECONDS = BaseExpires.addPeriod(Calendar.MONTH, 6); //14 * 24 * 60 * 60;  // 14 days
        }

        log.info("Updated DEFAULT_USER_SESSION_EXTENSION_TIME_IN_SECONDS to " + DEFAULT_USER_SESSION_EXTENSION_TIME_IN_SECONDS);

        assertTrue(DEFAULT_USER_SESSION_EXTENSION_TIME_IN_SECONDS > 100000);
    }
}