package net.whydah.sts.user.authentication.commands;

import com.github.kevinsawicki.http.HttpRequest;
import jakarta.ws.rs.core.MediaType;
import net.whydah.sso.commands.baseclasses.BaseHttpPostHystrixCommand;
import net.whydah.sso.ddd.model.application.ApplicationTokenID;
import net.whydah.sso.ddd.model.user.UserTokenId;
import net.whydah.sso.user.types.UserToken;
import net.whydah.sts.errorhandling.AuthenticationFailedException;
import net.whydah.sts.threat.ThreatResource;
import net.whydah.sts.user.UserTokenFactory;

import java.net.URI;
import java.util.UUID;

public class CommandCreatePinUser extends BaseHttpPostHystrixCommand<UserToken> {

    private String userJson;
    private String adminUserTokenId;

    public CommandCreatePinUser(URI userAdminServiceUri, String appTokenXml, String myAppTokenId, String adminUserTokenId, String userJson) {
        super(userAdminServiceUri, appTokenXml, myAppTokenId, "UASUserAdminGroup", 2000);

        this.userJson = userJson;
        if (userAdminServiceUri == null || !ApplicationTokenID.isValid(myAppTokenId) || !UserTokenId.isValid(adminUserTokenId) || userJson == null) {
            log.error(TAG + " initialized with null-values - will fail - userAdminServiceUri:{}, myAppTokenId:{},adminUserTokenId:{}, userJson:{}", userAdminServiceUri, myAppTokenId, adminUserTokenId, userJson);
        }
        this.adminUserTokenId = adminUserTokenId;
    }

    @Override
    protected HttpRequest dealWithRequestBeforeSend(HttpRequest request) {
        return request.contentType(MediaType.APPLICATION_JSON).send(userJson);
    }

    @Override
    protected UserToken dealWithResponse(String response) {

        if (response.length() > 32) {
            log.trace("Response from UserAdminService: {}", response);
            if (response.contains("logonFailed")) {
                throw new AuthenticationFailedException("Authentication failed.");
            }

            UserToken userToken = UserTokenFactory.fromUserIdentityJson(response);
            userToken.setSecurityLevel("0");  // UserIdentity as source = securitylevel=0
            userToken.setUserTokenId(UUID.randomUUID().toString());
            userToken.setDefcon(ThreatResource.getDEFCON());
            userToken.setTimestamp(String.valueOf(System.currentTimeMillis()));
            log.debug("Returning UserToken: {}", userToken);


            return userToken;
        }
        return null;
    }

    @Override
    protected String getTargetPath() {
        return myAppTokenId + "/" + adminUserTokenId + "/user";
    }

    /**
     WebResource uasWR = uasResource.path(applicationtokenid).path(adminUserTokenId).path("user");
     ClientResponse uasResponse = uasWR.type(MediaType.APPLICATION_JSON).post(ClientResponse.class, userJson);
     if (uasResponse.getStatus() != ClientResponse.Status.OK.getStatusCode()) {
     String error = uasResponse.getEntity(String.class);
     log.error(error);
     } else {
     String userIdentityJson = uasResponse.getEntity(String.class);
     UserToken userToken = UserTokenFactory.fromUserIdentityJson(userIdentityJson);
     userToken.setSecurityLevel("0");  // 3rd party sts as source = securitylevel=0
     return AuthenticatedUserTokenRepository.addUserToken(userToken, applicationtokenid, "pin");
     }

     */
}