package net.whydah.sts.user;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.exoreaction.notification.SlackNotificationFacade;
import com.exoreaction.notification.util.ContextMapBuilder;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;

import jakarta.inject.Singleton;
import net.minidev.json.JSONArray;
import net.whydah.sso.application.types.Application;
import net.whydah.sso.commands.extensions.crmapi.CommandGetCRMCustomer;
import net.whydah.sso.user.mappers.UserTokenMapper;
import net.whydah.sso.user.types.UserApplicationRoleEntry;
import net.whydah.sso.user.types.UserToken;
import net.whydah.sts.application.ApplicationModelFacade;
import net.whydah.sts.application.AuthenticatedApplicationTokenRepository;
import net.whydah.sts.config.AppConfig;

/**
 * @author <a href="mailto:erik-dev@fjas.no">Erik Drolshammer</a> 03.11.14
 */
@Singleton
public class UserTokenFactory {
    static final String TOKEN_ISSUER = "/sts/TOKEN_ISSUER/tokenverifier";

    private static final Logger log = LoggerFactory.getLogger(UserTokenFactory.class);
    private static String defcon;

    private static AppConfig appConfig = new AppConfig();
    
    @Deprecated
    public UserTokenFactory() {
        this(appConfig.getProperty("DEFCON"));
    }

    public UserTokenFactory(String defcon) {
        UserTokenFactory.defcon = defcon;
    }

    private static String generateID() {
        return UUID.randomUUID().toString();
    }

    public static void setDefcon(String defcon) {
        UserTokenFactory.defcon = defcon;
    }

    public static boolean shouldReturnFullUserToken(String applicationID) {
        if(applicationID==null || applicationID.length()==0){
            return false;
        }
        for (String app : AppConfig.getPredefinedFullTokenApplications()){
            if (app.equalsIgnoreCase(applicationID)) {
                log.info("shouldReturnFullUserToken from properties=true");
                return true;
            }
        }
        //check if the application has been configured without filtering
        Application app = ApplicationModelFacade.getApplication(applicationID);
        if(app!=null){
            return app.isFullTokenApplication();
        }
        return false;
    }

    public static boolean shouldReturnAnonymousUserToken(String applicationID, UserToken userToken) {
        if (shouldReturnFullUserToken(applicationID)) {
            return false;
        }
        if ("true".equalsIgnoreCase(appConfig.getProperty("ANONYMOUSTOKEN"))) {
            List<UserApplicationRoleEntry> origRoleList = userToken.getRoleList();
            log.info("shouldReturnAnonymousUserToken - ANONYMOUSTOKEN active, checking for role with ApplicationID: {}", applicationID);

            for (UserApplicationRoleEntry are : origRoleList) {
                if (are.getApplicationId().equalsIgnoreCase(applicationID)) {
                    // Role found, no ANONYMOUSTOKEN filtering
                    log.info("shouldReturnAnonymousUserToken - found expected role, returning false");
                    return false;
                }
            }
            log.info("shouldReturnAnonymousUserToken - no expected role, returning true");
            // Role not found,ANONYMOUSTOKEN flag set - we should ANONYMOUSTOKEN
            return true;

        } else {
            // No ANONYMOUSTOKEN configured
            return false;
        }
    }

    public static UserToken fromUserIdentityJson(String userIdentityJSON) {
        UserToken userToken = UserTokenMapper.fromUserIdentityJson(userIdentityJSON);
        userToken.setUserTokenId(generateID());
        userToken.setTimestamp(String.valueOf(System.currentTimeMillis()));
        String securityLevel = "1";
        userToken.setSecurityLevel(securityLevel);
        return userToken;
    }

    public static List<UserToken> fromUsersIdentityJson(String usersIdentityJSON) {
        List<UserToken> userTokens = new ArrayList<>();
        Object document = Configuration.defaultConfiguration().jsonProvider().parse(usersIdentityJSON);
        JSONArray users  = JsonPath.read(document, "$.result");

        for (Object user1 : users) {
            LinkedHashMap user = (LinkedHashMap) user1;
            UserToken userToken = new UserToken();
            userToken.setUid((String) user.get("uid"));
            userToken.setUserTokenId(generateID());
            userToken.setUserName((String) user.get("username"));
            userToken.setFirstName((String) user.get("firstName"));
            userToken.setLastName((String) user.get("lastName"));
            userToken.setEmail((String) user.get("email"));
            userToken.setPersonRef((String) user.get("personRef"));
            userToken.setCellPhone((String) user.get("cellPhone"));
            userTokens.add(userToken);
        }
        return userTokens;
    }


    /**
     * 1. whydahadmin apps
     * a) anynomous
     * b) fulltoken
     * c) filtered
     */
    public static UserToken getFilteredUserToken(String applicationTokenID, UserToken userToken) {
        String myappid = AuthenticatedApplicationTokenRepository.getApplicationIdFromApplicationTokenID(applicationTokenID);
        log.debug("getFilteredUserToken - found appid={}", myappid);
        Application app = ApplicationModelFacade.getApplication(myappid);
        if (app!=null && app.getSecurity()!=null && app.getSecurity().isWhydahAdmin()) {
            log.debug("Is Whydahadmin shouldReturnFullUserToken({})=true - no filtering", myappid);
            return userToken;
        } else if (shouldReturnAnonymousUserToken(myappid, userToken)) {
            log.debug("a) shouldReturnAnonymousUserToken({})=true", myappid);
            userToken.setUserName("anonymous");
            userToken.setEmail(null);
            userToken.setFirstName("Anonymous");
            userToken.setCellPhone(null);

            try {
                URI crmServiceUri = URI.create(appConfig.getProperty("crmservice"));
                String result = new CommandGetCRMCustomer(crmServiceUri, applicationTokenID, userToken.getUserTokenId(), userToken.getPersonRef()).execute();
                String postalCode = extractPostalCode(result);
                userToken.setLastName("Demographics " + postalCode);
            } catch (Exception e){
                userToken.setLastName("Demographics Oslo");
            }

            List<UserApplicationRoleEntry> roleList = new ArrayList<>();
            userToken.setRoleList(roleList);
            log.debug("getFilteredUserToken - returning anonymous userToken {}", userToken);
            return userToken;
        } else if (shouldReturnFullUserToken(myappid)) {
            log.debug("b) shouldReturnFullUserToken({})=true - no filtering", myappid);
            return userToken;
        } else {
            List<UserApplicationRoleEntry> origRoleList = userToken.getRoleList();
            List<UserApplicationRoleEntry> roleList = new LinkedList<>();

            for (UserApplicationRoleEntry are : origRoleList) {
                if (are.getApplicationId().equalsIgnoreCase(myappid)) {
                    roleList.add(are);
                }
            }
            userToken.setRoleList(roleList);
            log.debug("getFilteredUserToken - filtering active for appid:{}", myappid);
            return userToken;
        }
    }

    /**
     * @return  the postal code earliest in the json string (the first address)
     */
    static String extractPostalCode(String crmCustomer) {
        int beginIndex = crmCustomer.indexOf("postalcode") + "postalcode".length();
        int endIndex = crmCustomer.indexOf("postalcity");
        return crmCustomer.substring(beginIndex, endIndex)
                .replace("\"","")
                .replace(",","")
                .replace(",","")
                .replace(":","")
                .trim();
    }

    public static boolean verifyApplicationToken(String applicationtokenid, String applicationtokenXml) {
        // TODO - possibly implement check if apptokenXml is identical from source to repo
        boolean validAppToken = false;
        if (applicationtokenid != null) {
            return AuthenticatedApplicationTokenRepository.verifyApplicationTokenId(applicationtokenid);
        } else {
            log.warn("verifyApplicationToken - not expecting null values applicationtokenid null, applicationtokenXml {}", applicationtokenXml);
            SlackNotificationFacade.sendAlarm("verifyApplicationToken - not expecting null values applicationtokenid null", 
            		ContextMapBuilder.of("applicationTokenXml", applicationtokenXml));
            return false;
        }
    }
    
    


}
