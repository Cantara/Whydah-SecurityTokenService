package net.whydah.sts.user;

import net.whydah.sso.application.mappers.ApplicationCredentialMapper;
import net.whydah.sso.application.mappers.ApplicationTokenMapper;
import net.whydah.sso.application.types.ApplicationCredential;
import net.whydah.sso.application.types.ApplicationToken;
import net.whydah.sso.config.ApplicationMode;
import net.whydah.sso.ddd.model.application.ApplicationTokenID;
import net.whydah.sso.user.mappers.UserTokenMapper;
import net.whydah.sso.user.types.UserApplicationRoleEntry;
import net.whydah.sso.user.types.UserToken;
import net.whydah.sts.application.AuthenticatedApplicationTokenRepository;
import net.whydah.sts.config.AppConfig;
import net.whydah.sts.file.FreemarkerProcessor;
import net.whydah.sts.threat.ThreatResource;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.StringReader;
import java.util.*;

import static org.custommonkey.xmlunit.XMLAssert.assertXMLEqual;
import static org.junit.Assert.*;

public class UserTokenTest {
    private FreemarkerProcessor freemarkerProcessor = new FreemarkerProcessor();
    private final static Logger log = LoggerFactory.getLogger(UserTokenTest.class);
    private final static DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();


    @BeforeClass
    public static void init() {
        System.setProperty(ApplicationMode.IAM_MODE_KEY, ApplicationMode.DEV);
        System.setProperty(AppConfig.IAM_CONFIG_KEY, "src/test/testconfig.properties");
    }


    @Test
    public void testCreateAnonymousToken(){
    	UserToken userToken = new UserToken();
    	userToken.setUserName("anonymous");
        userToken.setEmail(null);
        userToken.setFirstName(null);
        userToken.setCellPhone(null);
        userToken.setLastName("Demographics Oslo");
        List<UserApplicationRoleEntry> roleList = new ArrayList<>();
        userToken.setRoleList(roleList);
    }
    @Test
    @Ignore
    public void testCreateUserToken() throws Exception {
        UserToken userToken = new UserToken();
        userToken.setUid("MyUUIDValue");
        userToken.setFirstName("Ola");
        userToken.setEmail("test@whydah.net");
        userToken.setLastName("Nordmann");
        userToken.setTimestamp(String.valueOf(System.currentTimeMillis()));
        userToken.setLifespan("3000");
        userToken.setPersonRef("73637276722376");
        userToken.setDefcon(ThreatResource.getDEFCON());
        userToken.setUserTokenId(UUID.randomUUID().toString());
        String xml = freemarkerProcessor.toXml(userToken);

        UserToken copyToken = UserTokenMapper.fromUserTokenXml(xml);
        String copyxml = freemarkerProcessor.toXml(copyToken);
        //assertTrue("The generated user sts is wrong.", xml.equalsIgnoreCase(copyxml));

        assertXMLEqual(xml, copyxml);
    }

    @Test
    public void testActiveUserTokenRepository() {
        UserToken userToken = new UserToken();
        userToken.setUserTokenId(UUID.randomUUID().toString());
        userToken.setUserName("myusername");
        userToken.setFirstName("Ola");
        userToken.setLastName("Nordmann");
        userToken.setEmail("test@whydah.net");
        userToken.setTimestamp(String.valueOf(System.currentTimeMillis()));
        userToken.setLifespan(String.valueOf(2 * 60 * 60 * new Random().nextInt(100)));

        userToken.setUserTokenId(UUID.randomUUID().toString());
        userToken.setPersonRef("78125637812638");

        String apptokenId = UUID.randomUUID().toString();
        AuthenticatedUserTokenRepository.addUserToken(userToken, apptokenId, "");
        assertTrue("Verification of valid userToken failed", AuthenticatedUserTokenRepository.verifyUserToken(userToken, apptokenId));

        userToken.setFirstName("Pelle");
        String usertokenfromfreemarkertransformation = freemarkerProcessor.toXml(userToken);
        assertTrue("UserToken not updated", usertokenfromfreemarkertransformation.indexOf("Pelle") > 0);
        assertFalse("Verification of changed usertoken fail as it should", AuthenticatedUserTokenRepository.verifyUserToken(userToken, "2012xxxx"));
    }

    @Test
    public void testTimedOutActiveUserTokenRepository() {
        UserToken userToken = new UserToken();
        userToken.setUserName(UUID.randomUUID().toString());
        userToken.setUserTokenId(UUID.randomUUID().toString());
        userToken.setFirstName("Ola");
        userToken.setLastName("Nordmann");
        userToken.setEmail("test@whydah.net");
        userToken.setPersonRef("78125637812638");
        userToken.setTimestamp(String.valueOf(System.currentTimeMillis() - 1000));
        userToken.setLifespan("0");
        AuthenticatedUserTokenRepository.addUserToken(userToken, "", "");
        assertFalse("Verification of timed-out userToken successful", AuthenticatedUserTokenRepository.verifyUserToken(userToken, ""));
    }

    @Test
    @Ignore
    public void testCreateUserTokenWithRolesFreemarkerCopy() {
        UserToken userToken = new UserToken();
        userToken.setUid(UUID.randomUUID().toString());
        userToken.setFirstName("Olav");
        userToken.setLastName("Nordmann");
        userToken.setEmail("test2@whydah.net");
        userToken.setUserTokenId(UUID.randomUUID().toString());
        userToken.addApplicationRoleEntry(new UserApplicationRoleEntry(userToken.getUid(), "2349785543", "Whydah.net", "Kunde 1", "Boardmember", "Diktator"));
        userToken.addApplicationRoleEntry(new UserApplicationRoleEntry(userToken.getUid(), "2349785543", "Whydah.net", "Kunde 2", "tester", "ansatt"));
        userToken.addApplicationRoleEntry(new UserApplicationRoleEntry(userToken.getUid(), "2349785543", "Whydah.net", "Kunde 3", "Boardmember", ""));
        userToken.addApplicationRoleEntry(new UserApplicationRoleEntry(userToken.getUid(), "appa", "whydag.org", "Kunde 1", "President", "Valla"));
        String tokenxml = freemarkerProcessor.toXml(userToken);

        UserToken copyToken = UserTokenMapper.fromUserTokenXml(tokenxml);
        String copyxml = freemarkerProcessor.toXml(copyToken);
        //System.out.println("FROM: " + tokenxml);
        //System.out.println("TO: " + copyxml);
        assertEquals(tokenxml, copyxml);
        UserToken copyToken2 = UserTokenMapper.fromUserTokenXml(tokenxml);
        String copyxml2 = freemarkerProcessor.toXml(copyToken2);
        //System.out.println("FILTERED: " + copyxml2);
        // assertFalse("Should not be equal as result is applicationfiltered ", tokenxml.equals(copyxml2));
    }

    @Test
    @Ignore
    public void testCreateUserTokenWithRolesUserTokenCopy() {
        UserToken userToken = new UserToken();
        userToken.setUid(UUID.randomUUID().toString());
        userToken.setFirstName("Olav");
        userToken.setLastName("Nordmann");
        userToken.setEmail("test2@whydah.net");
        userToken.setUserTokenId(UUID.randomUUID().toString());
        userToken.addApplicationRoleEntry(new UserApplicationRoleEntry(userToken.getUid(), "2349785543", "Whydah.net", "Kunde 1", "Boardmember", "Diktator"));
        userToken.addApplicationRoleEntry(new UserApplicationRoleEntry(userToken.getUid(), "2349785543", "Whydah.net", "Kunde 2", "tester", "ansatt"));
        userToken.addApplicationRoleEntry(new UserApplicationRoleEntry(userToken.getUid(), "2349785543", "Whydah.net", "Kunde 3", "Boardmember", ""));
        userToken.addApplicationRoleEntry(new UserApplicationRoleEntry(userToken.getUid(), "appa", "whydag.org", "Kunde 1", "President", "Valla"));
        String tokenxml = freemarkerProcessor.toXml(userToken);

        UserToken copyToken = UserTokenMapper.fromUserTokenXml(tokenxml);
        UserToken copy2Token = copyToken.copy();
        assertTrue(copy2Token.toString().equalsIgnoreCase(copyToken.toString()));
    }

    @Test
    public void createFromUserIdentityXML() {
        String identityXML = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <whydahuser>
                    <identity>
                        <username>admin</username>
                        <cellPhone>+1555406789</cellPhone>
                        <email>useradmin@getwhydah.com</email>
                        <firstname>User</firstname>
                        <lastname>Admin</lastname>
                        <personref>0</personref>
                        <UID>useradmin</UID>
                    </identity>
                    <applications>
                        <application>
                            <appId>19</appId>
                            <applicationName>UserAdminWebApplication</applicationName>
                            <orgName>Support</orgName>
                            <roleName>WhydahUserAdmin</roleName>
                            <roleValue>1</roleValue>
                        </application>
                        <application>
                            <appId>19</appId>
                            <applicationName>UserAdminWebApplication</applicationName>
                            <orgName>Support</orgName>
                            <roleName>TEST</roleName>
                            <roleValue>13</roleValue>
                        </application>
                        <application>
                            <appId>19</appId>
                            <applicationName>UserAdminWebApplication</applicationName>
                            <orgName>ACS</orgName>
                            <roleName>TULL</roleName>
                            <roleValue>1</roleValue>
                        </application>
                        <application>
                            <appId>199</appId>
                            <applicationName>UserAdminWebApplication</applicationName>
                            <orgName>Support</orgName>
                            <roleName>WhydahUserAdmin</roleName>
                            <roleValue>1</roleValue>
                        </application>
                        <application>
                            <appId>19</appId>
                            <applicationName>UserAdminWebApplication</applicationName>
                            <orgName>Support</orgName>
                            <roleName>UserAdmin</roleName>
                            <roleValue>100</roleValue>
                        </application>
                    </applications>
                </whydahuser>
                """;


        String appXML = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>\s
                 \
                 <token>
                     <params>
                         <applicationtoken>123123123123</applicationtoken>
                         <applicationid>123</applicationid>
                         <applicationname>ACS</applicationname>
                         <expires>3213213212</expires>
                     </params>\s
                 </token>
                """;
        //UserToken2 userToken = UserToken2.createUserTokenFromUserAggregate(appXML, identityXML);

        UserToken userToken = UserTokenMapper.fromUserAggregateXml(identityXML);

        //System.out.printf(userToken.toString());
        //String xml = freemarkerProcessor.toXml(userToken);
        //System.out.println(freemarkerProcessor.toXml(userToken));

        assertEquals("0", userToken.getPersonRef());
        assertEquals("User", userToken.getFirstName());
        assertEquals("Admin", userToken.getLastName());
        assertEquals("useradmin@getwhydah.com", userToken.getEmail());

        assertTrue(freemarkerProcessor.toXml(userToken).indexOf("UserAdmin") > 0);
        assertTrue(freemarkerProcessor.toXml(userToken).indexOf("WhydahUserAdmin") > 0);
    }

    @Test
    public void testUserAggregateParsing() throws Exception {
        String identityXML = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <whydahuser>
                    <identity>
                        <username>admin</username>
                        <cellPhone>+1555406789</cellPhone>
                        <email>useradmin@getwhydah.com</email>
                        <firstname>User</firstname>
                        <lastname>Admin</lastname>
                        <personref>0</personref>
                        <UID>useradmin</UID>
                    </identity>
                    <applications>
                        <application>
                            <appId>193</appId>
                            <applicationName>UserAdminWebApplication</applicationName>
                            <orgName>Support</orgName>
                            <roleName>WhydahUserAdmin</roleName>
                            <roleValue>1</roleValue>
                        </application>
                        <application>
                            <appId>193</appId>
                            <applicationName>UserAdminWebApplication</applicationName>
                            <orgName>Support</orgName>
                            <roleName>UserAdmin</roleName>
                            <roleValue>100</roleValue>
                        </application>
                        <application>
                            <appId>121</appId>
                            <applicationName>UserAdminWebApplication</applicationName>
                            <orgName>Support</orgName>
                            <roleName>UserAdmin</roleName>
                            <roleValue>100</roleValue>
                        </application>
                        <application>
                            <appId>193</appId>
                            <applicationName>UserAdminWebApplication</applicationName>
                            <orgName>Support</orgName>
                            <roleName>TEST</roleName>
                            <roleValue>3</roleValue>
                        </application>
                    </applications>
                </whydahuser>
                """;
        List<UserApplicationRoleEntry> roleList = new LinkedList<>();

        DocumentBuilder documentBuilder = dbf.newDocumentBuilder();
        Document doc = documentBuilder.parse(new InputSource(new StringReader(identityXML)));
        XPath xPath = XPathFactory.newInstance().newXPath();
        NodeList applicationNodes = (NodeList) xPath.evaluate("/whydahuser/applications/application/appId", doc, XPathConstants.NODESET);
        for (int i = 1; i < applicationNodes.getLength() + 1; i++) {
            UserApplicationRoleEntry role = new UserApplicationRoleEntry();
            role.setApplicationId((String) xPath.evaluate("/whydahuser/applications/application[" + i + "]/appId", doc, XPathConstants.STRING));
            role.setOrgName((String) xPath.evaluate("/whydahuser/applications/application[" + i + "]/orgName", doc, XPathConstants.STRING));
            role.setRoleName((String) xPath.evaluate("/whydahuser/applications/application[" + i + "]/roleName", doc, XPathConstants.STRING));
            role.setRoleValue((String) xPath.evaluate("/whydahuser/applications/application[" + i + "]/roleValue", doc, XPathConstants.STRING));
            //System.out.println(role);
            roleList.add(role);
        }
        //System.out.println(roleList);
        assertTrue(roleList.size() == 4);

    }

    /**
     * Need to rewrite this to test the complete flow
     *
     * @throws Exception
     */
    @Test
    @Ignore
    public void testUserTokenFullUserToken() throws Exception {
        assertTrue(UserTokenFactory.shouldReturnFullUserToken("2211"));
        assertFalse(UserTokenFactory.shouldReturnFullUserToken("22121"));

    }

    @Test
    public void testUserTokenFiltering() throws Exception {
        String identityXML = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <whydahuser>
                    <identity>
                        <username>admin</username>
                        <cellPhone>+1555406789</cellPhone>
                        <email>useradmin@getwhydah.com</email>
                        <firstname>User</firstname>
                        <lastname>Admin</lastname>
                        <personref>0</personref>
                        <UID>useradmin</UID>
                    </identity>
                    <applications>
                        <application>
                            <appId>193</appId>
                            <applicationName>UserAdminWebApplication</applicationName>
                            <orgName>Support</orgName>
                            <roleName>WhydahUserAdmin</roleName>
                            <roleValue>1</roleValue>
                        </application>
                        <application>
                            <appId>193</appId>
                            <applicationName>UserAdminWebApplication</applicationName>
                            <orgName>Support</orgName>
                            <roleName>UserAdmin</roleName>
                            <roleValue>100</roleValue>
                        </application>
                        <application>
                            <appId>121</appId>
                            <applicationName>UserAdminWebApplication</applicationName>
                            <orgName>Support</orgName>
                            <roleName>UserAdmin</roleName>
                            <roleValue>100</roleValue>
                        </application>
                        <application>
                            <appId>193</appId>
                            <applicationName>UserAdminWebApplication</applicationName>
                            <orgName>Support</orgName>
                            <roleName>TEST</roleName>
                            <roleValue>3</roleValue>
                        </application>
                    </applications>
                </whydahuser>
                """;


        UserToken userToken = UserTokenMapper.fromUserAggregateXml(identityXML);

        //System.out.printf(userToken.toString());
        //String xml = freemarkerProcessor.toXml(userToken);
        //System.out.println(freemarkerProcessor.toXml(userToken));

        assertEquals("0", userToken.getPersonRef());
        assertEquals("User", userToken.getFirstName());
        assertEquals("Admin", userToken.getLastName());
        assertEquals("useradmin@getwhydah.com", userToken.getEmail());
        String userTokenId = UUID.randomUUID().toString();
        userToken.setUserTokenId(userTokenId);

        assertTrue(freemarkerProcessor.toXml(userToken).indexOf("UserAdmin") > 0);
        assertTrue(freemarkerProcessor.toXml(userToken).indexOf("WhydahUserAdmin") > 0);

        ApplicationCredential cred = new ApplicationCredential("193", "myapp", "dummysecret");
        ApplicationToken imp = ApplicationTokenMapper.fromApplicationCredentialXML(ApplicationCredentialMapper.toXML(cred));
        AuthenticatedApplicationTokenRepository.addApplicationToken(imp);


        List<UserApplicationRoleEntry> origRoleList = userToken.getRoleList();
        List<UserApplicationRoleEntry> roleList = new LinkedList<>();
        String myappid = AuthenticatedApplicationTokenRepository.getApplicationIdFromApplicationTokenID(imp.getApplicationTokenId());
        for (int i = 0; i < origRoleList.size(); i++) {
            UserApplicationRoleEntry are = origRoleList.get(i);
            if (are.getApplicationId().equalsIgnoreCase(myappid)) {
                roleList.add(are);
            }
        }
        assertTrue(roleList.size() == 3);

    }

    @Test
    public void testActiveUserTokenExpiresRepository() {
        UserToken utoken = new UserToken();
        utoken.setUserName(UUID.randomUUID().toString());
        utoken.setFirstName("Ola");
        utoken.setLastName("Nordmann");
        utoken.setEmail("test@whydah.net");
        utoken.setTimestamp(String.valueOf(System.currentTimeMillis()));
        utoken.setLifespan(String.valueOf(2 * 60 * 60 * new Random().nextInt(100)));
        utoken.setUserTokenId(UUID.randomUUID().toString());
        utoken.setPersonRef("78125637812638");

        String applicationTokenId = UUID.randomUUID().toString();
        AuthenticatedUserTokenRepository.addUserToken(utoken, applicationTokenId, "test");
        assertTrue("Verification of valid userToken failed", AuthenticatedUserTokenRepository.verifyUserToken(utoken, applicationTokenId));

        utoken.setFirstName("Pelle");
        String token = freemarkerProcessor.toXml(utoken);
        assertTrue("Token not updated", token.indexOf("Pelle") > 0);
        assertFalse("Verification of in-valid userToken successful", AuthenticatedUserTokenRepository.verifyUserToken(utoken, applicationTokenId));
    }

    @Test
    public void testAuthenticatedUserTokenRepositoryCleanup() throws Exception {
        UserToken utoken = new UserToken();
        utoken.setUserName(UUID.randomUUID().toString());
        utoken.setFirstName("Ola");
        utoken.setLastName("Nordmann");
        utoken.setEmail("test@whydah.net");
        utoken.setTimestamp(String.valueOf(System.currentTimeMillis()));
        utoken.setUserTokenId(UUID.randomUUID().toString());
        utoken.setPersonRef("78125637812638");
        utoken.setLifespan(String.valueOf(1 * 1000));
        utoken.setTimestamp(String.valueOf(System.currentTimeMillis()));

        int noOfUsers = AuthenticatedUserTokenRepository.getMapSize();
        log.debug("Users:" + noOfUsers);

        AuthenticatedUserTokenRepository.addUserToken(utoken, new ApplicationTokenID(UUID.randomUUID().toString()).getId(), "test");
        int noOfUsersAfter = AuthenticatedUserTokenRepository.getMapSize();
        log.debug("Users (after):" + noOfUsersAfter);
        assertTrue(noOfUsers < noOfUsersAfter);

        Thread.sleep(2 * 1000);
        AuthenticatedUserTokenRepository.cleanUserTokenMap();
        int noOfUsersAfter2 = AuthenticatedUserTokenRepository.getMapSize();
        log.debug("Users (after2):" + noOfUsersAfter2);
        assertTrue(noOfUsersAfter2 < noOfUsersAfter);

    }

    @Test
    public void testUserTokenFreemarker() throws Exception {
        UserToken userToken = new UserToken();
//        userToken.setUserTokenId(UUID.randomUUID().toString());
        userToken.setUid(UUID.randomUUID().toString());
        userToken.setFirstName("Ola");
        userToken.setLastName("Nordmann");
        userToken.setEmail("test@whydah.net");
        userToken.setTimestamp(String.valueOf(System.currentTimeMillis()));
        userToken.setPersonRef("78125637812638");
        userToken.setLifespan(String.valueOf(1 * 1000));
        String userTokenId = userToken.getUserTokenId();
        Map<String, Object> model = new HashMap();
        model.put("it", userToken);
        model.put("DEFCON", userToken.getDefcon());
        log.debug("freemarkerProcessor.toXml(userToken):{}", freemarkerProcessor.toXml(userToken));
        assertTrue(userToken.isValid());
        assertTrue(freemarkerProcessor.toXml(userToken).indexOf(userTokenId) > 0);
        assertTrue(freemarkerProcessor.toXml(userToken).indexOf(userToken.getUid()) > 0);
        assertTrue(freemarkerProcessor.toXml(userToken).indexOf(userToken.getEmail()) > 0);
        assertTrue(freemarkerProcessor.toXml(userToken).indexOf(userToken.getFirstName()) > 0);
        assertTrue(freemarkerProcessor.toXml(userToken).indexOf(userToken.getPersonRef()) > 0);
        assertTrue(freemarkerProcessor.toXml(userToken).indexOf(userToken.getLifespan()) > 0);

    }

    @Test
    public void testAnynomousUserTokenCreation() {
        UserToken userToken = new UserToken();

        userToken.setUserName("anonymous");
        userToken.setEmail(null);
        userToken.setFirstName(null);
        userToken.setCellPhone(null);
        userToken.setLastName("Demographics Oslo");
        List<UserApplicationRoleEntry> roleList = new ArrayList<>();
        userToken.setRoleList(roleList);
        log.debug("getFilteredUserToken - returning anonymous userToken {}", userToken);

        userToken.setLifespan(String.valueOf(1 * 1000));
        String userTokenId = userToken.getUserTokenId();
        Map<String, Object> model = new HashMap();
        model.put("it", userToken);
        model.put("DEFCON", userToken.getDefcon());
        log.debug("freemarkerProcessor.toXml(userToken):{}", freemarkerProcessor.toXml(userToken));
        assertTrue(freemarkerProcessor.toXml(userToken).indexOf(userTokenId) > 0);
        assertTrue(freemarkerProcessor.toXml(userToken).indexOf(userToken.getLifespan()) > 0);
    }
}
