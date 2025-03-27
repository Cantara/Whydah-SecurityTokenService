package net.whydah.sts.config;

import net.whydah.sso.application.helpers.ApplicationHelper;
import net.whydah.sso.application.mappers.ApplicationMapper;
import net.whydah.sso.application.types.Application;
import net.whydah.sso.config.ApplicationMode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.slf4j.LoggerFactory.getLogger;

public class AppConfigTest {

    private static final Logger log = getLogger(AppConfigTest.class);

    @BeforeAll
    public static void init() {
        System.setProperty(ApplicationMode.IAM_MODE_KEY, ApplicationMode.TEST);
        System.setProperty(AppConfig.IAM_CONFIG_KEY, "src/test/testconfig.properties");
    }

    @Test
    public void readProperties() throws IOException {
        AppConfig appConfig = new AppConfig();
        assertEquals("9998", appConfig.getProperty("service.port")); //fra securitytokenservice.TEST.properties
    }

    @Test
    public void testValidFullTokenApplications() {
        List<Application> applications = ApplicationMapper.fromJsonList(ApplicationHelper.getDummyAppllicationListJson());
        String fTokenList="";
        for (Application application : applications) {
            if (application.isFullTokenApplication()){
                fTokenList=fTokenList+application.getId()+",";
                log.debug("is fulltoken {} appid:{}", application.isFullTokenApplication(), application.getId());
            }

        }
        if (fTokenList==null || fTokenList.length()<3){
            return;
        }
        log.debug("Result:"+fTokenList.substring(0,fTokenList.length()-1));
    }

}
