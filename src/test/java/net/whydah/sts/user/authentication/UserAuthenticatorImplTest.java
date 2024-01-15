package net.whydah.sts.user.authentication;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import net.whydah.sso.application.mappers.ApplicationTokenMapper;
import net.whydah.sso.application.types.ApplicationToken;
import net.whydah.sso.user.mappers.UserAggregateMapper;
import net.whydah.sso.user.types.UserAggregate;
import net.whydah.sso.user.types.UserCredential;
import net.whydah.sso.user.types.UserToken;
import net.whydah.sts.errorhandling.AuthenticationFailedException;
import org.junit.Test;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

public class UserAuthenticatorImplTest {

    @Test
    public void logonUser() throws IOException {
        WireMockServer wireMockServer = new WireMockServer(options().dynamicPort());
        wireMockServer.start();
        try {
            int port = wireMockServer.port();
            UserAggregate userAggregate = new UserAggregate("uid1", "john", "John", "Smith", UUID.randomUUID().toString(), "john@smith.bs", "+4712345678");
            userAggregate.setRoleList(Collections.emptyList());
            String userAggregateXml = UserAggregateMapper.toXML(userAggregate);

            WireMock wiremock = create()
                    .http()
                    .port(port)
                    .host("localhost")
                    .build();

            String userCredentialXml = new UserCredential("john", "s3cr3t").toXML();
            String userCredentialXmlWrongPassword = new UserCredential("john", "wrong-password").toXML();

            ApplicationToken applicationToken = new ApplicationToken();
            applicationToken.setApplicationName("A1");
            applicationToken.setApplicationID("A1");
            applicationToken.setApplicationSecret("very-secret");
            applicationToken.setApplicationTokenId(UUID.randomUUID().toString());

            wiremock.register(post(urlEqualTo("/" + applicationToken.getApplicationTokenId() + "/auth/logon/user"))
                    .withRequestBody(equalToXml(userCredentialXml))
                    .willReturn(aResponse()
                            .withHeader("Content-Type", "application/xml")
                            .withBody(userAggregateXml)));

            wiremock.register(post(urlEqualTo("/" + applicationToken.getApplicationTokenId() + "/auth/logon/user"))
                    .withRequestBody(equalToXml(userCredentialXmlWrongPassword))
                    .willReturn(aResponse()
                            .withHeader("Content-Type", "application/xml")
                            .withBody("<xml>Error: logonFailed, this is a test</xml>")));

            UserAuthenticator userAuthenticator = new UserAuthenticatorImpl("http://localhost:" + port + "/", null);
            String appTokenXml = ApplicationTokenMapper.toXML(applicationToken);
            UserToken userToken = userAuthenticator.logonUser(applicationToken.getApplicationTokenId(), appTokenXml, userCredentialXml);
            assertNotNull(userToken);
            try {
                // This logon with a wrong password should never be allowed. The mock should return a response that contains logonFailed
                UserToken userToken2 = userAuthenticator.logonUser(applicationToken.getApplicationTokenId(), appTokenXml, userCredentialXmlWrongPassword);
                // the logon completed, even when password is wrong! This will happen if credentials check is never done.
                fail("Expected AuthenticationFailedException");
            } catch (AuthenticationFailedException e) {
                // the logon was successfully denied because of wrong password
            }
        } finally {
            wireMockServer.stop();
        }
    }
}