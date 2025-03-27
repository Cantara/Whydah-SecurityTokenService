package net.whydah.sts.application;

import net.whydah.sso.application.helpers.ApplicationXpathHelper;
import net.whydah.sso.application.mappers.ApplicationMapper;
import net.whydah.sso.application.types.Application;
import net.whydah.sso.commands.appauth.CommandLogonApplication;
import net.whydah.sso.commands.appauth.CommandVerifyUASAccessByApplicationTokenId;
import net.whydah.sso.commands.application.CommandListApplications;
import net.whydah.sso.commands.systemtestbase.SystemTestBaseConfig;
import net.whydah.sso.commands.userauth.CommandLogonUserByUserCredential;
import net.whydah.sso.user.helpers.UserXpathHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


public class ApplicationFullTokenTest {

	private static final Logger log = LoggerFactory.getLogger(ApplicationFullTokenTest.class);

	static SystemTestBaseConfig config;

	@BeforeAll
	public static void setup() throws Exception {
		config = new SystemTestBaseConfig();
	}

	@Test
    @Disabled
    public void testValidFullTokenApplications() {
		//config.setLocalTest();
		if (config.isSystemTestEnabled()) {

			String myAppTokenXml = new CommandLogonApplication(config.tokenServiceUri, config.appCredential).execute();
			String myApplicationTokenID = ApplicationXpathHelper.getAppTokenIdFromAppTokenXml(myAppTokenXml);
			assertTrue(myApplicationTokenID != null && myApplicationTokenID.length() > 5);
			String userticket = UUID.randomUUID().toString();
			String userToken = new CommandLogonUserByUserCredential(config.tokenServiceUri, myApplicationTokenID, myAppTokenXml, config.userCredential, userticket).execute();
			String userTokenId = UserXpathHelper.getUserTokenId(userToken);
			assertTrue(userTokenId != null && userTokenId.length() > 5);

			boolean hasAccess = new CommandVerifyUASAccessByApplicationTokenId(config.userAdminServiceUri.toString(), myApplicationTokenID).execute();
			if(hasAccess){

				//update full sts application list, but we do need to set whydahUASAccess=true
				String appjson = new CommandListApplications(config.userAdminServiceUri, myApplicationTokenID).execute();
				if(appjson!=null){
					List<Application> updateList = ApplicationMapper.fromJsonList(appjson);
					//2210,2211,2212,2215,2219 should have userTokenFilter=false by default
					//100 has userTokenFilter=true by default
					//101 has whydahUASAccess=true b/c it executed CommandListApplications successfully
					for (Application application : updateList) {
						if(application.getId().equals("101")){
							assertTrue(application.getSecurity().isWhydahUASAccess());
						}
						if(application.getId().equals("2210")){
							assertFalse(application.getSecurity().getUserTokenFilter());
						}
						if(application.getId().equals("2211")){
							assertFalse(application.getSecurity().getUserTokenFilter());
						}
						if(application.getId().equals("2212")){
							assertFalse(application.getSecurity().getUserTokenFilter());
						}
						if(application.getId().equals("2215")){
							assertFalse(application.getSecurity().getUserTokenFilter());
						}
						if(application.getId().equals("2219")){
							assertFalse(application.getSecurity().getUserTokenFilter());
						}
						if(application.getId().equals("100")){
							assertTrue(application.getSecurity().getUserTokenFilter());
						}
					}
				}
			}




		}
	}





}
