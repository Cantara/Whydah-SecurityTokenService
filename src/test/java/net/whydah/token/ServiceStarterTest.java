package net.whydah.token;

import net.whydah.token.config.ApplicationMode;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.UriBuilder;
import java.net.URI;

import static org.junit.Assert.assertTrue;

public class ServiceStarterTest {
    private static ServiceStarter serviceStarter;
    private static URI baseUri;
    Client restClient;

    @BeforeClass
    public static void init() throws Exception {
        System.setProperty(ApplicationMode.IAM_MODE_KEY, ApplicationMode.DEV);
        serviceStarter = new ServiceStarter();
        serviceStarter.startServer();
        baseUri = UriBuilder.fromUri("http://localhost/tokenservice/").port(serviceStarter.getPort()).build();
    }

    @Before
    public void initRun() throws Exception {
        restClient = ClientBuilder.newClient();
    }

    @AfterClass
    public static void teardown() throws Exception {
        serviceStarter.stop();
    }

    @Test
    public void getLegalRemark() {
        WebTarget webTarget = restClient.target(baseUri);
        String responseMsg = webTarget.request().get(String.class);
        assertTrue(responseMsg.contains("Any misuse will be prosecuted."));
    }

    @Test
    public void getApplicationTokenTemplate() {
        WebTarget webTarget = restClient.target(baseUri).path("/applicationtokentemplate");
        String responseMsg = webTarget.request().get(String.class);
        assertTrue(responseMsg.contains("<applicationtokenID>"));
    }

    @Test
    public void getApplicationCredentialTemplate() {
        WebTarget webTarget = restClient.target(baseUri).path("/applicationcredentialtemplate");
        String responseMsg = webTarget.request().get(String.class);
        assertTrue(responseMsg.contains("<applicationcredential>"));
    }

    @Test
    public void getUserCredentialTemplate() {
        WebTarget webTarget = restClient.target(baseUri).path("/usercredentialtemplate");
        String responseMsg = webTarget.request().get(String.class);
        assertTrue(responseMsg.contains("<usercredential>"));
    }

    /**
     * Test if a WADL document is available at the relative path
     * "application.wadl".
     */
    @Test
    public void testApplicationWadl() {
        WebTarget webTarget = restClient.target(baseUri).path("application.wadl");
        String responseMsg = webTarget.request().get(String.class);
        assertTrue(responseMsg.contains("<application"));
        assertTrue(responseMsg.contains("logonApplication"));
    }
}
