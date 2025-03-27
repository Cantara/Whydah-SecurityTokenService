package net.whydah.sts.application;

import net.whydah.sso.application.mappers.ApplicationCredentialMapper;
import net.whydah.sso.application.mappers.ApplicationTokenMapper;
import net.whydah.sso.application.types.ApplicationCredential;
import net.whydah.sso.application.types.ApplicationToken;
import net.whydah.sso.config.ApplicationMode;
import net.whydah.sts.health.HealthResource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

import static net.whydah.sts.application.AuthenticatedApplicationTokenRepository.DEFAULT_APPLICATION_SESSION_EXTENSION_TIME_IN_SECONDS;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationTokenTest {
    private final static Logger log = LoggerFactory.getLogger(ApplicationTokenTest.class);

    @BeforeAll
    static void init() throws Exception {
        System.setProperty(ApplicationMode.IAM_MODE_KEY, ApplicationMode.DEV);
    }

    @Test
    void testCreateApplicationCredential() {
        ApplicationCredential cred = new ApplicationCredential("1212", "testapp", "dummysecret");
        ApplicationToken imp = ApplicationTokenMapper.fromApplicationCredentialXML(ApplicationCredentialMapper.toXML(cred));

        assertEquals(imp.getApplicationID(), cred.getApplicationID(),
                "ApplicationID should match the credential");
        assertTrue(imp.getApplicationTokenId().length() > 12,
                "ApplicationTokenId should be longer than 12 characters");
    }

    @Test
    void testCreateApplicationCredential2() {
        ApplicationCredential cred = new ApplicationCredential("1212", "testapp", "dummysecret");
        ApplicationToken imp = ApplicationTokenMapper.fromApplicationCredentialXML(ApplicationCredentialMapper.toXML(cred));

        assertEquals(imp.getApplicationID(), cred.getApplicationID(),
                "ApplicationID should match the credential");
        assertTrue(imp.getApplicationTokenId().length() > 12,
                "ApplicationTokenId should be longer than 12 characters");
    }

    @Test
    void testCreateApplicationToken() throws Exception {
        ApplicationCredential cred = new ApplicationCredential("1212", "testapp", "dummysecret");
        ApplicationToken imp = ApplicationTokenMapper.fromApplicationCredentialXML(ApplicationCredentialMapper.toXML(cred));
        imp.setExpires(String.valueOf(System.currentTimeMillis() + 1000));
        AuthenticatedApplicationTokenRepository.addApplicationToken(imp);
        Thread.sleep(1000);

        // First attempt - with expires = now...
        ApplicationToken imp3 = AuthenticatedApplicationTokenRepository.getApplicationToken(imp.getApplicationTokenId());
        assertNull(imp3, "Token should be null after expiration");

        imp.setExpires(String.valueOf(System.currentTimeMillis() + DEFAULT_APPLICATION_SESSION_EXTENSION_TIME_IN_SECONDS * 1000));
        AuthenticatedApplicationTokenRepository.addApplicationToken(imp);

        // Second attempt - with sensible expires
        ApplicationToken imp2 = AuthenticatedApplicationTokenRepository.getApplicationToken(imp.getApplicationTokenId());
        assertNotNull(imp2, "Token should not be null before expiration");
        assertEquals(imp2.getApplicationID(), cred.getApplicationID(),
                "ApplicationID should match the credential");
        assertTrue(imp2.getApplicationTokenId().length() > 12,
                "ApplicationTokenId should be longer than 12 characters");
    }

    @Test
    void testIsApplicationTokenExpired() throws Exception {
        ApplicationCredential cred = new ApplicationCredential("1212", "testapp", "dummysecret");
        ApplicationToken imp = ApplicationTokenMapper.fromApplicationCredentialXML(ApplicationCredentialMapper.toXML(cred));
        imp.setExpires(String.valueOf(System.currentTimeMillis() + DEFAULT_APPLICATION_SESSION_EXTENSION_TIME_IN_SECONDS * 1000));
        AuthenticatedApplicationTokenRepository.addApplicationToken(imp);

        ApplicationToken imp2 = AuthenticatedApplicationTokenRepository.getApplicationToken(imp.getApplicationTokenId());
        assertFalse(AuthenticatedApplicationTokenRepository.isApplicationTokenExpired(imp2),
                "Token should not be expired with default expiration time");

        imp2.setExpires(String.valueOf(System.currentTimeMillis() + 20));
        AuthenticatedApplicationTokenRepository.addApplicationToken(imp2);
        Thread.sleep(300);

        ApplicationToken imp3 = AuthenticatedApplicationTokenRepository.getApplicationToken(imp.getApplicationTokenId());
        assertNull(imp3, "Token should be null after short expiration");
    }

    @Test
    void testSomeTimecalculations() {
        long l1 = Instant.now().getEpochSecond();
        long l2 = HealthResource.getRunningSince().getEpochSecond();
        assertTrue(l1 - l2 >= 0,
                "Current time should not be less than start time");
    }

    @Test
    void testAuthenticatedApplicationTokenRepositoryCleanup() throws Exception {
        int applications = AuthenticatedApplicationTokenRepository.getMapSize();
        log.debug("Initial applications count: {}", applications);

        ApplicationCredential cred = new ApplicationCredential("1212", "testapp", "dummysecret");
        ApplicationToken imp = ApplicationTokenMapper.fromApplicationCredentialXML(ApplicationCredentialMapper.toXML(cred));
        imp.setExpires(String.valueOf(System.currentTimeMillis() + DEFAULT_APPLICATION_SESSION_EXTENSION_TIME_IN_SECONDS * 1000));
        AuthenticatedApplicationTokenRepository.addApplicationToken(imp);

        ApplicationToken imp2 = ApplicationTokenMapper.fromApplicationCredentialXML(ApplicationCredentialMapper.toXML(cred));
        imp2.setExpires(String.valueOf(System.currentTimeMillis() + 300)); // Short expiration
        AuthenticatedApplicationTokenRepository.addApplicationToken(imp2);

        int applicationsNow = AuthenticatedApplicationTokenRepository.getMapSize();
        log.debug("Applications after adding tokens: {}", applicationsNow);

        assertTrue(applicationsNow >= applications + 2,
                "Application count should increase by at least 2");

        Thread.sleep(1500);
        AuthenticatedApplicationTokenRepository.cleanApplicationTokenMap();

        int applicationsNow2 = AuthenticatedApplicationTokenRepository.getMapSize();
        log.debug("Applications after cleanup: {}", applicationsNow2);

        assertTrue(applicationsNow2 < applicationsNow,
                "Application count should decrease after cleanup");
    }
}