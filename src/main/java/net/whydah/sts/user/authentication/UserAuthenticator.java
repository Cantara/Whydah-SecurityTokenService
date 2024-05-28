package net.whydah.sts.user.authentication;

import org.jvnet.hk2.annotations.Contract;

import net.whydah.sso.user.types.UserToken;

@Contract
public interface UserAuthenticator {
    UserToken logonUser(String applicationTokenId, String appTokenXml, String userCredentialXml);

    UserToken logonPinUser(String applicationtokenid, String appTokenXml, String adminUserTokenIdparam, String cellPhone, String pin);

    UserToken createAndLogonUser(String applicationtokenid, String appTokenXml, String userCredentialXml, String fbUserXml);
    
    UserToken createAndLogonPinUser(String applicationtokenid, String appTokenXml, String userCredentialXml, String cellPhone, String pin, String userJson);

    UserToken getRefreshedUserToken(String uid);
    
    UserToken logonPinUserForTrustedUser(String applicationtokenid, String appTokenXml, String adminUserTokenIdparam, String cellPhone, String clientId, String pin);

    UserToken logonWithTrustedUser(String applicationtokenid, String appTokenXml, String adminUserTokenIdparam, String cellPhone, String clientid);
    
    UserToken logonUserUsingSharedSTSSecret(String applicationtokenid, String appTokenXml, String adminUserTokenIdparam, String cellPhone, String secret);
}
