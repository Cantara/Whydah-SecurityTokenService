package net.whydah.sts.threat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import jakarta.ws.rs.core.Response;
import net.whydah.sso.config.ApplicationMode;
import net.whydah.sso.whydah.ThreatSignal;
import net.whydah.sts.application.AuthenticatedApplicationTokenRepository;
import net.whydah.sts.errorhandling.AppException;
import net.whydah.sts.user.EnvHelper;

class ThreatResourceTest {

    private static final String APPTOKENID = "1d58b70dc0fdc98b5cdce4745fb086c4";

    @BeforeAll
    static void shared() {
        Map<String, String> envs = new HashMap<>();
        envs.put(ApplicationMode.IAM_MODE_KEY, ApplicationMode.DEV);
        EnvHelper.setEnv(envs);
    }

    private static MockedStatic<AuthenticatedApplicationTokenRepository> authenticatedApplication() {
        MockedStatic<AuthenticatedApplicationTokenRepository> repository =
                Mockito.mockStatic(AuthenticatedApplicationTokenRepository.class);
        repository.when(() -> AuthenticatedApplicationTokenRepository.verifyApplicationTokenId(APPTOKENID))
                .thenReturn(true);
        repository.when(() -> AuthenticatedApplicationTokenRepository.getApplicationIdFromApplicationTokenID(APPTOKENID))
                .thenReturn("101");
        repository.when(() -> AuthenticatedApplicationTokenRepository.getApplicationNameFromApplicationTokenID(APPTOKENID))
                .thenReturn("Whydah-SystemTests");
        return repository;
    }

    @Test
    void rejectsUnverifiedApplicationTokenId() {
        try (MockedStatic<AuthenticatedApplicationTokenRepository> repository =
                     Mockito.mockStatic(AuthenticatedApplicationTokenRepository.class)) {
            repository.when(() -> AuthenticatedApplicationTokenRepository.verifyApplicationTokenId("not-a-real-token"))
                    .thenReturn(false);

            assertThrows(AppException.class,
                    () -> new ThreatResource().logSignal("not-a-real-token", "{}"),
                    "an unverified applicationtokenid must be rejected, not accepted on length alone");
        }
    }

    @Test
    void rejectsNullApplicationTokenId() {
        assertThrows(AppException.class, () -> new ThreatResource().logSignal(null, "{}"));
    }

    @Test
    void rejectsEmptyApplicationTokenId() {
        assertThrows(AppException.class, () -> new ThreatResource().logSignal("", "{}"));
    }

    @Test
    void acceptsVerifiedApplicationTokenId() throws Exception {
        try (MockedStatic<AuthenticatedApplicationTokenRepository> repository = authenticatedApplication()) {
            Response response = new ThreatResource()
                    .logSignal(APPTOKENID, "{\"signalEmitter\":\"SSOLoginWebApplication\"}");

            assertEquals(200, response.getStatus());
        }
    }

    @Test
    void acceptsSignalWithoutEmitter() throws Exception {
        // The emitter null-check used to be inverted (!= null || length() < 5), so a signal
        // carrying no signalEmitter threw NPE and the parsed signal was silently replaced.
        try (MockedStatic<AuthenticatedApplicationTokenRepository> repository = authenticatedApplication()) {
            Response response = new ThreatResource().logSignal(APPTOKENID, "{}");

            assertEquals(200, response.getStatus());
        }
    }

    @Test
    void acceptsMalformedSignalAsRawText() throws Exception {
        try (MockedStatic<AuthenticatedApplicationTokenRepository> repository = authenticatedApplication()) {
            Response response = new ThreatResource().logSignal(APPTOKENID, "this is not json");

            assertEquals(200, response.getStatus());
        }
    }

    @Test
    void isInvalidPINToleratesNullText() {
        assertFalse(ThreatResource.isInvalidPIN(new ThreatSignal()),
                "a signal with no text must not throw when checked for PIN noise");
    }

    @Test
    void isInvalidPINDetectsSuppressedSignals() {
        ThreatSignal pinFailure = new ThreatSignal();
        pinFailure.setText("Pin verification failed for 4711");
        assertTrue(ThreatResource.isInvalidPIN(pinFailure));

        ThreatSignal registrationFailure = new ThreatSignal();
        registrationFailure.setText("Registration failed. Illegal form data");
        assertTrue(ThreatResource.isInvalidPIN(registrationFailure));
    }

    @Test
    void isInvalidPINLeavesRealThreatsAlone() {
        ThreatSignal realThreat = new ThreatSignal();
        realThreat.setText("Brute force detected from 10.0.0.1");
        assertFalse(ThreatResource.isInvalidPIN(realThreat));
    }

    @Test
    void forLoggingStripsLineBreaks() {
        assertEquals("harmless_INFO forged log line",
                ThreatResource.forLogging("harmless\nINFO forged log line"));
        assertEquals("carriage_return", ThreatResource.forLogging("carriage\rreturn"));
    }

    @Test
    void forLoggingTruncatesLongValues() {
        String logged = ThreatResource.forLogging("x".repeat(5000));

        assertTrue(logged.endsWith("...(truncated)"));
        assertTrue(logged.length() < 5000);
    }

    @Test
    void forLoggingToleratesNull() {
        assertNull(ThreatResource.forLogging(null));
    }
}
