package net.whydah.sts;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.client.apache.ApacheHttpClient;
import net.whydah.sso.config.ApplicationMode;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

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
        restClient = ApacheHttpClient.create();
    }

    @AfterClass
    public static void teardown() throws Exception {
        serviceStarter.stop();
    }

    @Test
    public void getLegalRemark() {
        WebResource webResource = restClient.resource(baseUri);
        String responseMsg = webResource.get(String.class);
        assertTrue(responseMsg.contains("Any misuse will be prosecuted."));
    }

    @Test
    public void getApplicationTokenTemplate() {
        WebResource webResource = restClient.resource(baseUri).path("/applicationtokentemplate");
        String responseMsg = webResource.get(String.class);
        assertTrue(responseMsg.contains("<applicationtokenID>"));
    }

    @Test
    public void getApplicationCredentialTemplate() {
        WebResource webResource = restClient.resource(baseUri).path("/applicationcredentialtemplate");
        String responseMsg = webResource.get(String.class);
        assertTrue(responseMsg.contains("<applicationcredential>"));
    }

    @Test
    public void getUserCredentialTemplate() {
        WebResource webResource = restClient.resource(baseUri).path("/usercredentialtemplate");
        String responseMsg = webResource.get(String.class);
        assertTrue(responseMsg.contains("<usercredential>"));
    }

    @Test
    public void getHealthResource() {
        WebResource webResource = restClient.resource(baseUri).path("/health");
        String responseMsg = webResource.get(String.class);
        assertTrue(responseMsg.contains("SecurityTokenService"));
    }
    /**
     * Test if a WADL document is available at the relative path
     * "application.wadl".
     */
    @Test
    public void testApplicationWadl() {
        WebResource webResource = restClient.resource(baseUri).path("application.wadl");
        String responseMsg = webResource.get(String.class);
        assertTrue(responseMsg.contains("<application"));
        assertTrue(responseMsg.contains("logonApplication"));
    }
}
