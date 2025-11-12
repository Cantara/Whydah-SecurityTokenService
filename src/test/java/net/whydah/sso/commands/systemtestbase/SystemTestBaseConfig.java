package net.whydah.sso.commands.systemtestbase;

import net.whydah.sso.application.helpers.ApplicationXpathHelper;
import net.whydah.sso.application.mappers.ApplicationTokenMapper;
import net.whydah.sso.application.types.ApplicationCredential;
import net.whydah.sso.application.types.ApplicationToken;
import net.whydah.sso.commands.appauth.CommandLogonApplication;
import net.whydah.sso.commands.userauth.CommandLogonUserByUserCredential;
import net.whydah.sso.user.helpers.UserXpathHelper;
import net.whydah.sso.user.mappers.UserTokenMapper;
import net.whydah.sso.user.types.UserCredential;
import net.whydah.sso.user.types.UserToken;
import net.whydah.sso.util.SSLTool;

import java.net.URI;
import java.util.UUID;

import static junit.framework.TestCase.assertNotNull;
import static junit.framework.TestCase.assertTrue;

public class SystemTestBaseConfig {

    public static final boolean SYSTEST_PROPERTY_ANONYMOUSTOKEN = true;
    public static final boolean SYSTEST_PROPERTY_fulltokenapplications = true;
    // Run the Whydah SystemTests?
    public boolean systemTest = false;

    // Run SystemTests for Whydah Extensions?
    public boolean statisticsExtensionSystemTest = true && systemTest;
    public boolean CRMCustomerExtensionSystemTest = true && systemTest;
    public int TIME_WAIT_BETWEEN_SYSTEMTEST = 15;  // 15 ms
    //
    public static String SYSTEMTEST_USER_CELLPHONE = "59441023";
    public static String SYSTEMTEST_USER_EMAIL = "totto@totto.org";

    public static String TEMPORARY_APPLICATION_ID = "9999";//"11";
    public static String TEMPORARY_APPLICATION_NAME = "Whydah-Jenkins";//"Funny APp";//"11";
    private String TEMPORARY_APPLICATION_SECRET = "9ju592A4t8dzz8mz788QQJ7Px";//"LLNmHsQDCerVWx5d6aCjug9fyPE";
    public String userName = "systest";
    public String password = "systest42";
    public String userEmail = "whydahadmin@getwhydah.com";
    public URI tokenServiceUri;
    public URI userAdminServiceUri;
    public ApplicationCredential appCredential;
    public UserCredential userCredential;
    public URI statisticsServiceUri;
    public URI crmServiceUri;
    public ApplicationToken myApplicationToken;
    public String myAppTokenXml;
    public String myApplicationTokenID;

    public SystemTestBaseConfig() {
        appCredential = new ApplicationCredential(TEMPORARY_APPLICATION_ID, TEMPORARY_APPLICATION_NAME, TEMPORARY_APPLICATION_SECRET);
        userCredential = new UserCredential(userName, password);
        
        // Set IAM_MODE using system property instead of environment variable
        // This is Java 9+ compatible
        setIAMMode("TEST");

        SSLTool.disableCertificateValidation();
        setRemoteTest();
    }

    /**
     * Set IAM_MODE using system property (Java 9+ compatible)
     */
    private void setIAMMode(String mode) {
        // First check if it's already set as environment variable
        String existingEnvMode = System.getenv("IAM_MODE");
        if (existingEnvMode != null && !existingEnvMode.isEmpty()) {
            System.out.println("IAM_MODE already set via environment: " + existingEnvMode);
            return;
        }
        
        // Set as system property
        System.setProperty("IAM_MODE", mode);
        System.out.println("IAM_MODE set to: " + mode);
    }

    public void setRemoteTest(){
        tokenServiceUri = URI.create("https://whydahdev.cantara.no/tokenservice/");
        userAdminServiceUri = URI.create("https://whydahdev.cantara.no/useradminservice/");
        crmServiceUri = URI.create("https://whydahdev.cantara.no/crmservice/");
        statisticsServiceUri = URI.create("https://whydahdev.cantara.no/reporter/");
    }
    
    public void setLocalTest(){
        tokenServiceUri = URI.create("http://localhost:9998/tokenservice/");
        userAdminServiceUri = URI.create("http://localhost:9992/useradminservice/");
        crmServiceUri = URI.create("http://localhost:12121/crmservice/");
        statisticsServiceUri = URI.create("http://localhost:4901/reporter/");
    }

    public boolean isSystemTestEnabled() {
        try {
            if (systemTest) {
                Thread.sleep(TIME_WAIT_BETWEEN_SYSTEMTEST);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        return systemTest;
    }

    public boolean isStatisticsExtensionSystemtestEnabled() {
        return statisticsExtensionSystemTest;
    }

    public boolean isCRMCustomerExtensionSystemTestEnabled() {
        try {
            if (CRMCustomerExtensionSystemTest) {
                Thread.sleep(TIME_WAIT_BETWEEN_SYSTEMTEST);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        return CRMCustomerExtensionSystemTest;
    }

    public ApplicationToken logOnSystemTestApplication() {
        if (isCRMCustomerExtensionSystemTestEnabled()) {
            SSLTool.disableCertificateValidation();
            ApplicationCredential appCredential = new ApplicationCredential(TEMPORARY_APPLICATION_ID, TEMPORARY_APPLICATION_NAME, TEMPORARY_APPLICATION_SECRET);
            myAppTokenXml = new CommandLogonApplication(tokenServiceUri, appCredential).execute();
            myApplicationTokenID = ApplicationXpathHelper.getAppTokenIdFromAppTokenXml(myAppTokenXml);
            assertTrue("Unable to log on application ", myApplicationTokenID.length() > 10);
            ApplicationToken appToken = ApplicationTokenMapper.fromXml(myAppTokenXml);
            assertNotNull(appToken);
            myApplicationToken = appToken;
            return appToken;
        }
        return null;
    }

    public UserToken logOnSystemTestApplicationAndSystemTestUser() {
        if (isCRMCustomerExtensionSystemTestEnabled()) {
            String myApplicationTokenID = "";
            SSLTool.disableCertificateValidation();
            ApplicationCredential appCredential = new ApplicationCredential(TEMPORARY_APPLICATION_ID, TEMPORARY_APPLICATION_NAME, TEMPORARY_APPLICATION_SECRET);
            myAppTokenXml = new CommandLogonApplication(tokenServiceUri, appCredential).execute();
            myApplicationTokenID = ApplicationXpathHelper.getAppTokenIdFromAppTokenXml(myAppTokenXml);
            assertTrue("Unable to log on application ", myApplicationTokenID.length() > 10);

            ApplicationToken appToken = ApplicationTokenMapper.fromXml(myAppTokenXml);
            assertNotNull(appToken);
            myApplicationToken = appToken;

            String userticket = UUID.randomUUID().toString();
            String userTokenXML = new CommandLogonUserByUserCredential(tokenServiceUri, myApplicationTokenID, myAppTokenXml, userCredential, userticket).execute();
            String userTokenId = UserXpathHelper.getUserTokenId(userTokenXML);
            assertTrue("Unable to log on user", userTokenId.length() > 10);
            UserToken userToken = UserTokenMapper.fromUserTokenXml(userTokenXML);
            assertNotNull(userToken);
            return userToken;
        }
        return null;
    }
    
    public String logOnSystemTestApplicationAndSystemTestUser_getTokenXML() {
        if (isCRMCustomerExtensionSystemTestEnabled()) {
            String myApplicationTokenID = "";
            SSLTool.disableCertificateValidation();
            ApplicationCredential appCredential = new ApplicationCredential(TEMPORARY_APPLICATION_ID, TEMPORARY_APPLICATION_NAME, TEMPORARY_APPLICATION_SECRET);
            myAppTokenXml = new CommandLogonApplication(tokenServiceUri, appCredential).execute();
            myApplicationTokenID = ApplicationXpathHelper.getAppTokenIdFromAppTokenXml(myAppTokenXml);
            assertTrue("Unable to log on application ", myApplicationTokenID.length() > 10);

            ApplicationToken appToken = ApplicationTokenMapper.fromXml(myAppTokenXml);
            assertNotNull(appToken);
            myApplicationToken = appToken;

            String userticket = UUID.randomUUID().toString();
            String userTokenXML = new CommandLogonUserByUserCredential(tokenServiceUri, myApplicationTokenID, myAppTokenXml, userCredential, userticket).execute();
            return userTokenXML;
        }
        return null;
    }
    
    public String generatePin() {
        java.util.Random generator = new java.util.Random();
        generator.setSeed(System.currentTimeMillis());
        int i = generator.nextInt(10000) % 10000;
        java.text.DecimalFormat f = new java.text.DecimalFormat("0000");
        return f.format(i);
    }
}