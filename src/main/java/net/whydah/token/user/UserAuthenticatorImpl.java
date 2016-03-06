package net.whydah.token.user;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import net.whydah.token.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.URI;

public class UserAuthenticatorImpl implements UserAuthenticator {
    private static final Logger log = LoggerFactory.getLogger(UserAuthenticatorImpl.class);
    private static final String USER_AUTHENTICATION_PATH = "/auth/logon/user";
    private static final String CREATE_AND_LOGON_OPERATION = "createandlogon";


    private URI useradminservice;
    private final WebTarget uasResource;
    private final UserTokenFactory userTokenFactory;



    @Inject
    public UserAuthenticatorImpl(@Named("useradminservice") URI useradminservice, UserTokenFactory userTokenFactory) {
        this.useradminservice = useradminservice;
        this.uasResource = ClientBuilder.newClient().target(useradminservice);
        this.userTokenFactory = userTokenFactory;
    }

    @Override
    public UserToken logonUser(final String applicationTokenId, final String appTokenXml, final String userCredentialXml) {
        log.trace("logonUser - Calling UserAdminService at " + useradminservice + " appTokenXml:" + appTokenXml + " userCredentialXml:" + userCredentialXml);
        try {
            WebTarget webResource = uasResource.path(applicationTokenId).path(USER_AUTHENTICATION_PATH);
            Response response = webResource.request(MediaType.APPLICATION_XML).post(Entity.xhtml(userCredentialXml));

            UserToken userToken = getUserToken(appTokenXml, response);
            AppConfig.updateApplinks(useradminservice,applicationTokenId,response.toString());

            return userToken;
        } catch (Exception e) {
            log.error("Problems connecting to {}", useradminservice);
            throw e;
        }
    }

    @Override
    public UserToken createAndLogonUser(String applicationtokenid, String appTokenXml, String userCredentialXml, String fbUserXml) {
        log.trace("createAndLogonUser - Calling UserAdminService at with appTokenXml:\n" + appTokenXml + "userCredentialXml:\n" + userCredentialXml + "fbUserXml:\n" + fbUserXml);
        WebTarget webResource = uasResource.path(applicationtokenid).path(USER_AUTHENTICATION_PATH).path(CREATE_AND_LOGON_OPERATION);
        log.debug("createAndLogonUser - Calling createandlogon " + webResource.toString());
        Response response = webResource.request(MediaType.APPLICATION_XML).post(Entity.xml(fbUserXml));

        UserToken token = getUserToken(appTokenXml, response);
        token.setSecurityLevel("0");  // 3rd party token as source = securitylevel=0
        return token;
    }


    private UserToken getUserToken(String appTokenXml, Response response) {
        if (response.getStatus() == Response.Status.OK.getStatusCode() || response.getStatus() == Response.Status.NO_CONTENT.getStatusCode()){
            String userAggregateXML = response.readEntity(String.class);
            log.debug("Response from UserAdminService: {}", userAggregateXML);
            if (userAggregateXML.contains("logonFailed")) {
                throw new AuthenticationFailedException("Authentication failed.");
            }

            UserToken userToken = userTokenFactory.fromUserAggregate(userAggregateXML);
            userToken.setSecurityLevel("1");  // UserIdentity as source = securitylevel=0
            ActiveUserTokenRepository.addUserToken(userToken);
            return userToken;

        } else  {
            log.error("Response from UAS: {}: {}", response.getStatus(), response.readEntity(String.class));
            throw new AuthenticationFailedException("Authentication failed. Status code " + response.getStatus());
        }
    }

}
