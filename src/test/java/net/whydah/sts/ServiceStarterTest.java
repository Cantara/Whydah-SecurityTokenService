package net.whydah.sts;

import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import net.whydah.sso.config.ApplicationMode;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServiceStarterTest {
    private static ServiceStarter serviceStarter;
    private static URI baseUri;
    private CloseableHttpClient restClient;
    private Client client;

    @BeforeAll
    public static void init() throws Exception {
        System.setProperty(ApplicationMode.IAM_MODE_KEY, ApplicationMode.DEV);
        serviceStarter = new ServiceStarter();
        serviceStarter.startServer();
        baseUri = UriBuilder.fromUri("http://localhost/tokenservice/").port(serviceStarter.getPort()).build();
    }

    @BeforeEach
    public void initRun() throws Exception {
        restClient = HttpClients.createDefault();
        client = ClientBuilder.newClient();
    }

    @AfterAll
    public static void teardown() throws Exception {
        serviceStarter.stop();
    }

    @Test
    public void getLegalRemark() {
        HttpGet request = new HttpGet(baseUri);
        request.setHeader(HttpHeaders.ACCEPT, MediaType.TEXT_HTML);
        try (CloseableHttpResponse response = restClient.execute(request)) {
            HttpEntity entity = response.getEntity();
            if (entity != null) {
                String responseMsg = EntityUtils.toString(entity);
                assertTrue(responseMsg.contains("Any misuse will be prosecuted."));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to execute request", e);
        }
    }

    @Test
    public void getLegalRemark2() {
        WebTarget webResource = client.target(baseUri);
        Invocation.Builder invocationBuilder =
                webResource.request(MediaType.TEXT_HTML);
        Response response = invocationBuilder.get();
        String responseMsg = response.readEntity(String.class);
        assertTrue(responseMsg.contains("Any misuse will be prosecuted."));
    }

    @Test
    public void getApplicationTokenTemplate() {
        HttpGet request = new HttpGet(baseUri + "/applicationtokentemplate");
        try (CloseableHttpResponse response = restClient.execute(request)) {
            HttpEntity entity = response.getEntity();
            if (entity != null) {
                String responseMsg = EntityUtils.toString(entity);
                assertTrue(responseMsg.contains("<applicationtokenID>"));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to execute request", e);
        }
    }

    @Test
    public void getApplicationCredentialTemplate() {
        WebTarget webResource = client.target(baseUri).path("/applicationcredentialtemplate");
        Invocation.Builder invocationBuilder =
                webResource.request(MediaType.APPLICATION_XML);
        Response response = invocationBuilder.get();
        String responseMsg = response.readEntity(String.class);
        assertTrue(responseMsg.contains("<applicationcredential>"));
    }

    @Test
    public void getUserCredentialTemplate() {
        WebTarget webResource = client.target(baseUri).path("/usercredentialtemplate");
        Invocation.Builder invocationBuilder =
                webResource.request(MediaType.APPLICATION_XML);
        Response response = invocationBuilder.get();
        String responseMsg = response.readEntity(String.class);
        assertTrue(responseMsg.contains("<usercredential>"));
    }

    @Test
    public void getHealthResource() {
        WebTarget webResource = client.target(baseUri).path("/health");
        Invocation.Builder invocationBuilder =
                webResource.request(MediaType.APPLICATION_JSON);
        Response response = invocationBuilder.get();
        String responseMsg = response.readEntity(String.class);
        assertTrue(responseMsg.contains("SecurityTokenService") || responseMsg.contains("127.0.0.1"));
    }

    /**
     * Test if a WADL document is available at the relative path
     * "application.wadl".
     */
    @Test
    public void testApplicationWadl() {
        WebTarget webResource = client.target(baseUri).path("application.wadl");
        Invocation.Builder invocationBuilder =
                webResource.request(MediaType.APPLICATION_XML);
        Response response = invocationBuilder.get();
        String responseMsg = response.readEntity(String.class);
        assertTrue(responseMsg.contains("logonApplication"));
    }
}