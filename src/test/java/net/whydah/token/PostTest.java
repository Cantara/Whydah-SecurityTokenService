package net.whydah.token;

import net.whydah.sso.application.helpers.ApplicationTokenXpathHelper;
import net.whydah.sso.application.mappers.ApplicationCredentialMapper;
import net.whydah.sso.application.types.ApplicationCredential;
import net.whydah.token.config.ApplicationMode;
import net.whydah.token.user.UserCredential;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.*;
import java.net.URI;

import static org.junit.Assert.assertTrue;

public class PostTest {
    private static URI baseUri;
    Client restClient;
    private static ServiceStarter serviceStarter;

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
    public void testLogonApplication() {
        String appCredential = "<?xml version='1.0' encoding='UTF-8' standalone='yes'?><applicationcredential><appid>app123</appid><appsecret>123123</appsecret></applicationcredential>";
        String responseXML = logonApplication(appCredential);
        assertTrue(responseXML.contains("applicationtoken"));
        assertTrue(responseXML.contains("applicationid"));
        assertTrue(responseXML.contains("expires"));
        assertTrue(responseXML.contains("Url"));
    }

    @Test
    public void testPostToGetUserToken() {
        String apptokenxml = getAppToken();
        String applicationtokenid = getApplicationTokenIdFromAppToken(apptokenxml);
        UserCredential user = new UserCredential("nalle", "puh");


        WebTarget userTokenResource = restClient.target(baseUri).path("user").path(applicationtokenid).path("/usertoken");
        MultivaluedMap<String,String> formData = new MultivaluedHashMap<>();
        formData.add("apptoken", apptokenxml);
        formData.add("usercredential", user.toXML());
        Response response = userTokenResource.request(MediaType.APPLICATION_FORM_URLENCODED_TYPE).post(Entity.form(formData));
        System.out.println("Calling:"+userTokenResource.getUri());
        String responseXML = response.readEntity(String.class);
        System.out.println("responseXML:\n"+responseXML);
        assertTrue(responseXML.contains("usertoken"));
        assertTrue(responseXML.contains("DEFCON"));
        assertTrue(responseXML.contains("applicationName"));
        assertTrue(responseXML.contains("hash"));
    }

    private String getAppToken() {
        ApplicationCredential acred = new ApplicationCredential("21356253","ine app","dummy");
        return logonApplication(ApplicationCredentialMapper.toXML(acred));
    }

    private String logonApplication(String appCredential) {
        WebTarget logonResource = restClient.target(baseUri).path("logon");
        MultivaluedMap<String,String> formData = new MultivaluedHashMap<>();
        formData.add("applicationcredential", appCredential);
        Response response = logonResource.request(MediaType.APPLICATION_FORM_URLENCODED_TYPE).post(Entity.form(formData));
        return response.readEntity(String.class);
    }

    private String getApplicationTokenIdFromAppToken(String appTokenXML) {
        return  ApplicationTokenXpathHelper.getApplicationTokenIDFromApplicationToken(appTokenXML);
    }
}
