package net.whydah.sts;

import jakarta.ws.rs.core.UriBuilder;
import net.whydah.sso.config.ApplicationMode;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.net.URI;

import static org.junit.Assert.assertTrue;

public class ServiceStarterTest {
    private static ServiceStarter serviceStarter;
    private static URI baseUri;
    CloseableHttpClient restClient;

    @BeforeClass
    public static void init() throws Exception {
        System.setProperty(ApplicationMode.IAM_MODE_KEY, ApplicationMode.DEV);
        serviceStarter = new ServiceStarter();
        serviceStarter.startServer();
        baseUri = UriBuilder.fromUri("http://localhost/tokenservice/").port(serviceStarter.getPort()).build();
    }

    @Before
    public void initRun() throws Exception {
        restClient = HttpClients.createDefault();
    }

    @AfterClass
    public static void teardown() throws Exception {
        serviceStarter.stop();
    }

    @Test
    public void getLegalRemark() {
        HttpGet request = new HttpGet(baseUri);
        CloseableHttpResponse response;
        try {
            response = restClient.execute(request);
            // Get HttpResponse Status
            System.out.println(response.getProtocolVersion());              // HTTP/1.1
            System.out.println(response.getStatusLine().getStatusCode());   // 200
            System.out.println(response.getStatusLine().getReasonPhrase()); // OK
            System.out.println(response.getStatusLine().toString());        // HTTP/1.1 200 OK

            HttpEntity entity = response.getEntity();
            if (entity != null) {
                // return it as a String
                String responseMsg = EntityUtils.toString(entity);
                System.out.println(responseMsg);
                assertTrue(responseMsg.contains("Any misuse will be prosecuted."));
            }
            response.close();
        } catch (Exception e) {

        } finally {

        }
//
//        WebResource webResource = restClient.resource(baseUri);
//        String responseMsg = webResource.get(String.class);
//        assertTrue(responseMsg.contains("Any misuse will be prosecuted."));
    }

    @Test
    public void getApplicationTokenTemplate() {
        HttpGet request = new HttpGet(baseUri + "/applicationtokentemplate");
        CloseableHttpResponse response;
        try {
            response = restClient.execute(request);
            // Get HttpResponse Status
            System.out.println(response.getProtocolVersion());              // HTTP/1.1
            System.out.println(response.getStatusLine().getStatusCode());   // 200
            System.out.println(response.getStatusLine().getReasonPhrase()); // OK
            System.out.println(response.getStatusLine().toString());        // HTTP/1.1 200 OK

            HttpEntity entity = response.getEntity();
            if (entity != null) {
                // return it as a String
                String responseMsg = EntityUtils.toString(entity);
                System.out.println(responseMsg);
                assertTrue(responseMsg.contains("<applicationtokenID>"));
            }
            response.close();
        } catch (Exception e) {

        } finally {


            //  WebResource webResource = restClient.resource(baseUri).path("/applicationtokentemplate");
            // String responseMsg = webResource.get(String.class);
//        assertTrue(responseMsg.contains("<applicationtokenID>"));
        }
    }

    @Test
    public void getApplicationCredentialTemplate() {
//        WebResource webResource = restClient.resource(baseUri).path("/applicationcredentialtemplate");
//        String responseMsg = webResource.get(String.class);
//        assertTrue(responseMsg.contains("<applicationcredential>"));
    }

    @Test
    public void getUserCredentialTemplate() {
//        WebResource webResource = restClient.resource(baseUri).path("/usercredentialtemplate");
//        String responseMsg = webResource.get(String.class);
//        assertTrue(responseMsg.contains("<usercredential>"));
    }

    @Test
    public void getHealthResource() {
//        try {
//            Thread.sleep(1500);
//        } catch (Exception e) {
//
//        }
//        WebResource webResource = restClient.resource(baseUri).path("/health");
//        String responseMsg = webResource.get(String.class);
//        assertTrue(responseMsg.contains("SecurityTokenService") || responseMsg.contains("127.0.0.1"));
    }
    /**
     * Test if a WADL document is available at the relative path
     * "application.wadl".
     */
    @Test
    public void testApplicationWadl() {
//        WebResource webResource = restClient.resource(baseUri).path("application.wadl");
//        String responseMsg = webResource.get(String.class);
//        assertTrue(responseMsg.contains("<application"));
//        assertTrue(responseMsg.contains("logonApplication"));
    }
}
