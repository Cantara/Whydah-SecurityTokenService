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

public class CommandCreateFBUser extends BaseHttpPostHystrixCommand<UserToken> {

    private String userXml;

    public CommandCreateFBUser(URI userAdminServiceUri, String appTokenXml, String myAppTokenId, String userXml) {
        super(userAdminServiceUri, appTokenXml, myAppTokenId, "UASUserAdminGroup", 2000);

        this.userXml = userXml;
        if (userAdminServiceUri == null || !ApplicationTokenID.isValid(myAppTokenId) || userXml == null) {
            log.error(TAG + " initialized with null-values - will fail - userAdminServiceUri:{}, myAppTokenId:{},  UserXml:{}", userAdminServiceUri, myAppTokenId, userXml);
        }
    }

    @Override
    protected HttpRequest dealWithRequestBeforeSend(HttpRequest request) {
        return request.contentType(MediaType.APPLICATION_XML).send(userXml);
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
        return myAppTokenId + "/createandlogon";
    }
}

