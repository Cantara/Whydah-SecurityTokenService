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

public class ApplicationTokenTest {
    private final static Logger log = LoggerFactory.getLogger(ApplicationTokenTest.class);

    @BeforeAll
    public static void init() throws Exception {
        System.setProperty(ApplicationMode.IAM_MODE_KEY, ApplicationMode.DEV);
    }

    @Test
    public void testCreateApplicationCredential() {
        ApplicationCredential cred = new ApplicationCredential("1212", "testapp", "dummysecret");
        ApplicationToken imp = ApplicationTokenMapper.fromApplicationCredentialXML(ApplicationCredentialMapper.toXML(cred));
        //System.out.println(imp.toXML());
        assertEquals("The generated application userToken is wrong.", cred.getApplicationID(), imp.getApplicationID());
        assertTrue(imp.getApplicationTokenId().length() > 12);
    }

    @Test
    public void testCreateApplicationCredential2() {
        ApplicationCredential cred = new ApplicationCredential("1212", "testapp", "dummysecret");
        ApplicationToken imp = ApplicationTokenMapper.fromApplicationCredentialXML(ApplicationCredentialMapper.toXML(cred));
        //System.out.println(imp.toXML());
        assertEquals("The generated application userToken is wrong.", cred.getApplicationID(), imp.getApplicationID());
        assertTrue(imp.getApplicationTokenId().length() > 12);
    }

    @Test
    public void testCreateApplicationToken() throws Exception {
        ApplicationCredential cred = new ApplicationCredential("1212", "testapp", "dummysecret");
        ApplicationToken imp = ApplicationTokenMapper.fromApplicationCredentialXML(ApplicationCredentialMapper.toXML(cred));
        imp.setExpires(String.valueOf(System.currentTimeMillis() + 1000));
        AuthenticatedApplicationTokenRepository.addApplicationToken(imp);
        Thread.sleep(1000);

        // First attempt - with expires = now...
        ApplicationToken imp3 = AuthenticatedApplicationTokenRepository.getApplicationToken(imp.getApplicationTokenId());
        assertTrue(imp3 == null);

        imp.setExpires(String.valueOf(System.currentTimeMillis() + DEFAULT_APPLICATION_SESSION_EXTENSION_TIME_IN_SECONDS * 1000));
        AuthenticatedApplicationTokenRepository.addApplicationToken(imp);
        // Second attempt - with sensible expires
        ApplicationToken imp2 = AuthenticatedApplicationTokenRepository.getApplicationToken(imp.getApplicationTokenId());
        //System.out.println(imp.toXML());
        assertEquals("The generated application userToken is wrong.", cred.getApplicationID(), imp2.getApplicationID());
        assertTrue(imp2.getApplicationTokenId().length() > 12);
    }

    @Test
    public void testIsApplicationTokenExpired() throws Exception {
        ApplicationCredential cred = new ApplicationCredential("1212", "testapp", "dummysecret");
        ApplicationToken imp = ApplicationTokenMapper.fromApplicationCredentialXML(ApplicationCredentialMapper.toXML(cred));
        imp.setExpires(String.valueOf(System.currentTimeMillis() + DEFAULT_APPLICATION_SESSION_EXTENSION_TIME_IN_SECONDS * 1000));
        AuthenticatedApplicationTokenRepository.addApplicationToken(imp);

        ApplicationToken imp2 = AuthenticatedApplicationTokenRepository.getApplicationToken(imp.getApplicationTokenId());

        assertFalse(AuthenticatedApplicationTokenRepository.isApplicationTokenExpired(imp2));
        imp2.setExpires(String.valueOf(System.currentTimeMillis() + 20));
        AuthenticatedApplicationTokenRepository.addApplicationToken(imp2);
        Thread.sleep(300);
        ApplicationToken imp3 = AuthenticatedApplicationTokenRepository.getApplicationToken(imp.getApplicationTokenId());

        assertTrue(imp3 == null);

    }

    @Test
    public void testSomeTimecalculations() throws Exception {
        long l1 = Instant.now().getEpochSecond();
        long l2 = HealthResource.getRunningSince().getEpochSecond();
        if (l1 - l2 < 0) {
            fail();
        }
    }

    @Test
    public void testAuthenticatedApplicationTokenRepositoryCleanup() throws Exception {
        int applications = AuthenticatedApplicationTokenRepository.getMapSize();
        log.debug("Applications:" + applications);
        ApplicationCredential cred = new ApplicationCredential("1212", "testapp", "dummysecret");
        ApplicationToken imp = ApplicationTokenMapper.fromApplicationCredentialXML(ApplicationCredentialMapper.toXML(cred));
        imp.setExpires(String.valueOf(System.currentTimeMillis() + DEFAULT_APPLICATION_SESSION_EXTENSION_TIME_IN_SECONDS * 1000));
        AuthenticatedApplicationTokenRepository.addApplicationToken(imp);

        ApplicationToken imp2 = ApplicationTokenMapper.fromApplicationCredentialXML(ApplicationCredentialMapper.toXML(cred));
        imp2.setExpires(String.valueOf(System.currentTimeMillis() + 1 * 300));  // Only one second here
        AuthenticatedApplicationTokenRepository.addApplicationToken(imp2);
        int applicationsNow = AuthenticatedApplicationTokenRepository.getMapSize();
        log.debug("ApplicationsNow:" + applicationsNow);
        assertTrue(applications <= applicationsNow - 2);  // Need to handle tests in parallell
        Thread.sleep(1500);
        AuthenticatedApplicationTokenRepository.cleanApplicationTokenMap();
        int applicationsNow2 = AuthenticatedApplicationTokenRepository.getMapSize();
        log.debug("Applications:" + applications);
        log.debug("ApplicationsNow2:" + applicationsNow2);
        assertTrue(applicationsNow2 < applicationsNow);  // Need to handle tests in parallell

    }
}
