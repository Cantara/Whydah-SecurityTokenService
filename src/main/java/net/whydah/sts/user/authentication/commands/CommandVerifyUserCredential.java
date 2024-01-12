package net.whydah.sts.user.authentication.commands;

import com.github.kevinsawicki.http.HttpRequest;
import jakarta.ws.rs.core.MediaType;
import net.whydah.sso.commands.baseclasses.BaseHttpPostHystrixCommand;
import net.whydah.sso.ddd.model.application.ApplicationTokenID;
import net.whydah.sso.user.mappers.UserTokenMapper;
import net.whydah.sso.user.types.UserToken;
import net.whydah.sts.errorhandling.AuthenticationFailedException;
import net.whydah.sts.threat.ThreatResource;

import java.net.URI;
import java.util.UUID;

public class CommandVerifyUserCredential extends BaseHttpPostHystrixCommand<UserToken> {

    private String userCredentialXml;

    public CommandVerifyUserCredential(URI userAdminServiceUri, String appTokenXml, String myAppTokenId, String userCredentialXml) {
        super(userAdminServiceUri, appTokenXml, myAppTokenId, "UASUserAdminGroup", 2000);

        this.userCredentialXml = userCredentialXml;
        if (userAdminServiceUri == null || !ApplicationTokenID.isValid(myAppTokenId) || userCredentialXml == null) {
            log.error(TAG + " initialized with null-values - will fail - userAdminServiceUri:{}, myAppTokenId:{},  userCredentialXml:{}", userAdminServiceUri, myAppTokenId, userCredentialXml);
        }
    }

    @Override
    protected HttpRequest dealWithRequestBeforeSend(HttpRequest request) {
        return request.contentType(MediaType.APPLICATION_XML).send(userCredentialXml);
    }

    @Override
    protected UserToken dealWithResponse(String response) {

        if (response.length() > 32) {
            log.trace("Response from UserAdminService: {}", response);
            if (response.contains("logonFailed")) {
                throw new AuthenticationFailedException("Authentication failed.");
            }

            UserToken userToken = UserTokenMapper.fromUserAggregateXml(response);
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
        return myAppTokenId + "/auth/logon/user";
    }

}


/**
 * UserCredential userCredential = UserCredentialMapper.fromXml(userCredentialXml);
 * if (userCredential != null) {
 * log.trace("logonUser - Calling UserAdminService at " + useradminservice + " appTokenXml:" + appTokenXml + " userCredentialSafeXml:" + userCredential.toSafeXML());
 * } else {
 * log.trace("logonUser - Unable to map userCredentialXML - Calling UserAdminService at " + useradminservice + " appTokenXml:" + appTokenXml + " userCredentialXml:" + appTokenXml);
 * <p>
 * }
 * <p>
 * WebResource webResource = uasResource.path(applicationTokenId).path(USER_AUTHENTICATION_PATH);
 * ClientResponse response = webResource.type(MediaType.APPLICATION_XML).post(ClientResponse.class, userCredentialXml);
 * UserToken userToken = getUserToken(applicationTokenId, appTokenXml, response);
 * <p>
 * <p>
 * <p>
 * <p>
 * private UserToken getUserToken(String applicationtokenid, String appTokenXml, ClientResponse response) {
 * if (response.getStatus() == Response.Status.OK.getStatusCode() || response.getStatus() == Response.Status.NO_CONTENT.getStatusCode()) {
 * String userAggregateJson = response.getEntity(String.class);
 * log.debug("Response from UserAdminService: {}", userAggregateJson);
 * if (userAggregateJson.contains("logonFailed")) {
 * throw new AuthenticationFailedException("Authentication failed.");
 * }
 * <p>
 * UserToken userToken = UserTokenMapper.fromUserAggregateXml(userAggregateJson);
 * userToken.setSecurityLevel("1");  // UserIdentity as source = securitylevel=0
 * userToken.setUserTokenId(generateID());
 * userToken.setDefcon(ThreatResource.getDEFCON());
 * userToken.setTimestamp(String.valueOf(System.currentTimeMillis()));
 * <p>
 * return AuthenticatedUserTokenRepository.addUserToken(userToken, applicationtokenid, "usertokenid");
 * <p>
 * } else {
 * log.error("getUserToken - Response from UAS: {}: {}", response.getStatus(), response.getEntity(String.class));
 * throw new AuthenticationFailedException("Authentication failed. Status code from UAS: " + response.getStatus());
 * }
 * }
 */
