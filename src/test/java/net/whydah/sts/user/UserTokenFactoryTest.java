package net.whydah.sts.user;

import net.whydah.sso.config.ApplicationMode;
import net.whydah.sso.user.mappers.UserTokenMapper;
import net.whydah.sso.user.types.UserApplicationRoleEntry;
import net.whydah.sso.user.types.UserToken;
import net.whydah.sso.whydah.DEFCON;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;


/**
 * @author <a href="mailto:erik-dev@fjas.no">Erik Drolshammer</a> 2014-11-04
 */
public class UserTokenFactoryTest {
    private final String userTokenXml1 = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"+
            "<usertoken xmlns:ns2=\"http://www.w3.org/1999/xhtml\" id=\"6f04e78e-bb75-4214-a786-2be7657bb38a\">\n"+
            "    <uid>uid1</uid>\n"+
            "    <timestamp>" + String.valueOf(System.currentTimeMillis() + (300 * 1000)) + "</timestamp>\n" +
            "    <lifespan>82800</lifespan>\n"+
            "    <issuer>https://sso.whydah.no/tokenservice/user/ac627ab1ccbdb62ec96e702f07f6425b/validate_usertokenid/02c8c7d2-08e0-4bbc-9852-c2afec342e06</issuer>\n"+
            "    <securitylevel>0</securitylevel>\n"+
            "    <DEFCON>DEFCON5</DEFCON>\n"+
            "    <username>username1</username>\n"+
            "    <firstname>Olav</firstname>\n"+
            "    <lastname>Nordmann</lastname>\n"+
            "    <email>notworking@email.com</email>\n"+
            "    <personRef></personRef>\n"+
            "    <application ID=\"2349785543\">\n"+
            "        <applicationName>Whydah.net</applicationName>\n"+
            "        <organizationName>Kunde 3</organizationName>\n"+
            "        <role name=\"Boardmember\" value=\"\"/>\n"+
            "    </application>\n"+
            "    <application ID=\"appa\">\n"+
            "        <applicationName>whydag.org</applicationName>\n"+
            "        <organizationName>Kunde 1</organizationName>\n"+
            "        <role name=\"President\" value=\"Valla\"/>\n"+
            "    </application>\n"+
            "\n"+
            "    <ns2:link type=\"application/xml\" href=\"https://sso.whydah.no/tokenservice/user/ac627ab1ccbdb62ec96e702f07f6425b/validate_usertokenid/02c8c7d2-08e0-4bbc-9852-c2afec342e06\" rel=\"self\"/>\n"+
            "    <hash type=\"MD5\">509eec7aef07357e4660f99f74255390</hash>\n"+
            "</usertoken>\n"+
            "\n";

    private final String userTokenXmlWithFourRoles = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<usertoken xmlns:ns2=\"http://www.w3.org/1999/xhtml\" id=\"8e4020b6-ea61-44f1-8b31-ecdd84869784\">\n" +
            "    <uid>8d563960-7b4f-4c44-a241-1ac359999b63</uid>\n" +
            "    <timestamp>" + String.valueOf(System.currentTimeMillis() + (300 * 1000)) + "</timestamp>\n" +
            "    <lifespan>3600000</lifespan>\n" +
            "    <issuer>https://sso.whydah.no/tokenservice/user/ac627ab1ccbdb62ec96e702f07f6425b/validate_usertokenid/02c8c7d2-08e0-4bbc-9852-c2afec342e06</issuer>\n" +
            "    <securitylevel>0</securitylevel>\n" +
            "    <DEFCON>DEFCON5</DEFCON>\n" +
            "    <username>anders.norman@company.com</username>\n" +
            "    <firstname>Anders</firstname>\n" +
            "    <lastname>Norman</lastname>\n" +
            "    <email>anders.norman@company.com</email>\n" +
            "    <personRef>Anders Norman</personRef>\n" +
            "    <application ID=\"199\">\n" +
            "        <applicationName>WhydahTestWebApplication</applicationName>\n" +
            "        <organizationName>Whydah</organizationName>\n" +
            "        <role name=\"WhydahDefaultUser\" value=\"anders.norman@company.com\"/>\n" +
            "    </application>\n" +
            "    <application ID=\"100\">\n" +
            "        <applicationName>ACS</applicationName>\n" +
            "        <organizationName>Company</organizationName>\n" +
            "        <role name=\"Employee\" value=\"anders.norman@company.com\"/>\n" +
            "    </application>\n" +
            "    <application ID=\"100\">\n" +
            "        <applicationName>ACS</applicationName>\n" +
            "        <organizationName>AnotherCompany</organizationName>\n" +
            "        <role name=\"BoardMember\" value=\"andersn\"/>\n" +
            "    </application>\n" +
            "    <application ID=\"100\">\n" +
            "        <applicationName>ACS</applicationName>\n" +
            "        <organizationName>AnotherCompany</organizationName>\n" +
            "        <role name=\"Owner\" value=\"Anders Norman\"/>\n" +
            "    </application>\n" +
            "\n" +
            "    <ns2:link type=\"application/xml\" href=\"https://sso.whydah.no/tokenservice/user/ac627ab1ccbdb62ec96e702f07f6425b/validate_usertokenid/02c8c7d2-08e0-4bbc-9852-c2afec342e06\" rel=\"self\"/>\n" +
            "    <hash type=\"MD5\">88e4a2db17733f371e8f78e123108d13</hash>\n" +
            "</usertoken>";

    private final String usersJsonWithOne = """
            {
            "rows":"1",
            "result":
            [{
             "personRef":"1",
             "uid":"andersflaten",
             "username":"91609953",
             "firstName":"Anders",
             "lastName":"Flaten",
             "email":"anders.flaten@gmail.com",
             "cellPhone":"+4791609953",
             "uri":"http://localhost:9995/uib/useradmin/users/anders.flaten\\/"
            }]
            }\
            """;

    private final String usersJsonWithTwo = """
            {
            "rows":"2",
            "result":
            [{
             "personRef":"",
             "uid":"a31004eb-6348-436c-a365-d5d8c181aa3b",
             "username":"bruker1",
             "firstName":"Bob",
             "lastName":"Scott",
             "email":"bob@gmail.com",
             "cellPhone":"55555501",
             "uri":"http://localhost:9995/uib/useradmin/users/a31004eb-6348-436c-a365-d5d8c181aa3b\\/"
            },{
             "personRef":"",
             "uid":"fc5c8097-e778-4ba4-9a4a-d08347616b51",
             "username":"bruker2",
             "firstName":"Ted",
             "lastName":"Scott",
             "email":"ted@gmail.com",
             "cellPhone":"55555502",
             "uri":"http://localhost:9995/uib/useradmin/users/fc5c8097-e778-4ba4-9a4a-d08347616b51\\/"
            }]
            }\
            """;

    private final String usersJsonWithZeroUsers = """
            {
            "rows":"0",
            "result":
            []
            }\
            """;

    private final String userAggregateJsonNoRoles = """
            {
              "uid": "dd313",
              "username": "dduck",
              "firstName": "Donald",
              "lastName": "Duck",
              "personRef": "1",
              "email": "donald.duck@gmail.com",
              "cellPhone": "12341234",
              "roles": []
            }\
            """;


    private static UserTokenFactory factory;


    @BeforeAll
    public static void shared() {
        Map<String, String> envs = new HashMap<>();
        envs.put(ApplicationMode.IAM_MODE_KEY, ApplicationMode.DEV);
        EnvHelper.setEnv(envs);

        factory = new UserTokenFactory("1");
    }


    @Test
    public void testFromXml1() {
        UserToken userToken = UserTokenMapper.fromUserTokenXml(userTokenXml1);
        assertEquals(userToken.getUid(), "uid1");
        assertEquals(userToken.getUserName(), "username1");
        assertEquals(userToken.getIssuer(), "https://sso.whydah.no/tokenservice/user/ac627ab1ccbdb62ec96e702f07f6425b/validate_usertokenid/02c8c7d2-08e0-4bbc-9852-c2afec342e06");
        assertEquals(userToken.getDefcon(), DEFCON.DEFCON5.toString());
//        assertEquals(userToken.getTimestamp(), "1415091487335");
//        assertEquals(userToken.getLifespan(), "82800");
        assertEquals(userToken.getRoleList().size(), 2);
    }

    @Test
    public void testFromXmlWithSeveralRoles() {
        UserToken userToken = UserTokenMapper.fromUserTokenXml(userTokenXmlWithFourRoles);
        assertEquals(userToken.getUid(), "8d563960-7b4f-4c44-a241-1ac359999b63");
        assertEquals(userToken.getUserName(), "anders.norman@company.com");
        assertEquals(userToken.getIssuer(), "https://sso.whydah.no/tokenservice/user/ac627ab1ccbdb62ec96e702f07f6425b/validate_usertokenid/02c8c7d2-08e0-4bbc-9852-c2afec342e06");
        assertEquals(userToken.getDefcon(), DEFCON.DEFCON5.toString());
//        assertEquals(userToken.getTimestamp(), "1415091757670");
//        assertEquals(userToken.getLifespan(), "3600000");
        assertEquals(userToken.getRoleList().size(), 4);

        UserApplicationRoleEntry roleEntry1 = userToken.getRoleList().getFirst();
        assertEquals(roleEntry1.getApplicationId(), "199");
        assertEquals(roleEntry1.getApplicationName(), "WhydahTestWebApplication");
        assertEquals(roleEntry1.getOrgName(), "Whydah");
        assertEquals(roleEntry1.getRoleName(), "WhydahDefaultUser");
        assertEquals(roleEntry1.getRoleValue(), "anders.norman@company.com");

        UserApplicationRoleEntry roleEntry2 = userToken.getRoleList().get(1);
        assertEquals(roleEntry2.getApplicationId(), "100");
        assertEquals(roleEntry2.getApplicationName(), "ACS");
        assertEquals(roleEntry2.getOrgName(), "Company");
        assertEquals(roleEntry2.getRoleName(), "Employee");
        assertEquals(roleEntry2.getRoleValue(), "anders.norman@company.com");

        UserApplicationRoleEntry roleEntry3 = userToken.getRoleList().get(2);
        assertEquals(roleEntry3.getApplicationId(), roleEntry2.getApplicationId());
        assertEquals(roleEntry3.getApplicationName(), roleEntry2.getApplicationName());
        assertEquals(roleEntry3.getOrgName(), "AnotherCompany");
        assertEquals(roleEntry3.getRoleName(), "BoardMember");
        assertEquals(roleEntry3.getRoleValue(), "andersn");

        UserApplicationRoleEntry roleEntry4 = userToken.getRoleList().get(3);
        assertEquals(roleEntry4.getApplicationId(), roleEntry2.getApplicationId());
        assertEquals(roleEntry4.getApplicationName(), roleEntry2.getApplicationName());
        assertEquals(roleEntry4.getOrgName(), roleEntry3.getOrgName());
        assertEquals(roleEntry4.getRoleName(), "Owner");
        assertEquals(roleEntry4.getRoleValue(), "Anders Norman");
    }


    @Test
    public void testFromUsersIdentityJsonWithOneUser() {
        List<UserToken> userTokens = UserTokenFactory.fromUsersIdentityJson(usersJsonWithOne);

        assertEquals(1, userTokens.size());

    }
    @Test
    public void testFromUsersIdentityJsonWithTwoUsers() {
        List<UserToken> userTokens = UserTokenFactory.fromUsersIdentityJson(usersJsonWithTwo);

        assertEquals(2, userTokens.size());

    }
    @Test
    public void testFromUsersIdentityJsonWithZeroUsers() {
        List<UserToken> userTokens = UserTokenFactory.fromUsersIdentityJson(usersJsonWithZeroUsers);

        assertEquals(0, userTokens.size());

    }


    @Test
    @Disabled
    public void testFromUserAggregateJsonNoRoles() throws Exception {
        UserToken userToken = UserTokenMapper.fromUserAggregateJson(userAggregateJsonNoRoles);

        assertEquals("dd313", userToken.getUid());
        assertEquals("dduck", userToken.getUserName());
        assertEquals("Donald", userToken.getFirstName());
        assertEquals("Duck", userToken.getLastName());
        assertEquals("1", userToken.getPersonRef());
        assertEquals("donald.duck@gmail.com", userToken.getEmail());
        assertEquals("12341234", userToken.getCellPhone());
        assertEquals(0, userToken.getRoleList().size());
    }


    @Test
    public void testExtractPostalCode() throws Exception {
        String customerJson = readFile("/UserTokenFactoryTest/INNCRMCustomer.json", Charset.forName("UTF-8"));
        String postalCode = UserTokenFactory.extractPostalCode(customerJson);
        assertEquals("0659", postalCode);
    }

    private String readFile(String inClassPath, Charset encoding) throws Exception {
        URL url = getClass().getResource(inClassPath);
        URI uri = url.toURI();
        Path path = Path.of(uri);
        byte[] encoded = Files.readAllBytes(path);
        return new String(encoded, encoding);
    }

}
