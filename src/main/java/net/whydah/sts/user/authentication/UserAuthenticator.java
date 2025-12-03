package net.whydah.sts.user.authentication;

import org.jvnet.hk2.annotations.Contract;

import net.whydah.sso.user.types.UserToken;

@Contract
public interface UserAuthenticator {
    default UserToken logonUser(String applicationTokenId, String appTokenXml, String userCredentialXml) {
    		return logonUser(applicationTokenId, appTokenXml, userCredentialXml, 0);
    }
    
    UserToken logonUser(String applicationTokenId, String appTokenXml, String userCredentialXml, long userTokenLifespan);

    default UserToken logonPinUser(String applicationtokenid, String appTokenXml, String adminUserTokenIdparam, String cellPhone, String pin) {
    		return logonPinUser(applicationtokenid, appTokenXml, adminUserTokenIdparam, cellPhone, pin, 0);
    }
    
    UserToken logonPinUser(String applicationtokenid, String appTokenXml, String adminUserTokenIdparam, String cellPhone, String pin, long userTokenLifespan);

    default UserToken createAndLogonUser(String applicationtokenid, String appTokenXml, String adminUserTokenIdparam, String fbUserXml) {
    		return createAndLogonUser(applicationtokenid, appTokenXml, adminUserTokenIdparam, fbUserXml, 0);
    }
    
    UserToken createAndLogonUser(String applicationtokenid, String appTokenXml, String useradminTokenId, String fbUserXml, long userTokenLifespan);
    
    default UserToken createAndLogonPinUser(String applicationtokenid, String appTokenXml, String useradminTokenId, String cellPhone, String pin, String userJson) {
    		return createAndLogonPinUser(applicationtokenid, appTokenXml, useradminTokenId, cellPhone, pin, userJson, 0);
    }
    
    UserToken createAndLogonPinUser(String applicationtokenid, String appTokenXml, String useradminTokenId, String cellPhone, String pin, String userJson, long userTokenLifespan);

    UserToken getRefreshedUserToken(String uid);
 
    default UserToken logonUserUsingSharedSTSSecret(String applicationtokenid, String appTokenXml, String adminUserTokenIdparam, String cellPhone, String secret) {
    		return logonUserUsingSharedSTSSecret(applicationtokenid, appTokenXml, adminUserTokenIdparam, cellPhone, secret, 0);
    }
    
    UserToken logonUserUsingSharedSTSSecret(String applicationtokenid, String appTokenXml, String adminUserTokenIdparam, String cellPhone, String secret, long userTokenLifespan);
}
