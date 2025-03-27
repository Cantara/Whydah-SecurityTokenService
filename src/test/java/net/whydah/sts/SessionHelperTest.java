package net.whydah.sts;

import net.whydah.sso.application.helpers.ApplicationHelper;
import net.whydah.sso.application.mappers.ApplicationMapper;
import net.whydah.sso.application.types.Application;
import net.whydah.sso.config.ApplicationMode;
import net.whydah.sts.config.AppConfig;
import net.whydah.sts.util.ApplicationModelHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class SessionHelperTest {
	

    @BeforeAll
    public static void init() {
        System.setProperty(ApplicationMode.IAM_MODE_KEY, ApplicationMode.TEST);
        System.setProperty(AppConfig.IAM_CONFIG_KEY, "src/test/testconfig.properties");
    }

   
	@Test
    public void testUserTokenLifespanSeconds() {
        //TODO: test should be updated when implementing the ApplicationModelHelper
        List<Application> applications = ApplicationMapper.fromJsonList(ApplicationHelper.getDummyAppllicationListJson());
        assertTrue(ApplicationModelHelper.getUserTokenLifeSpan(applications.getFirst()) == 86400);

		
	}

}
