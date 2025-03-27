package net.whydah.sts.user;

import net.whydah.sso.config.ApplicationMode;
import net.whydah.sts.config.AppConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthenticatedUserTokenRepositoryTest {

    @Mock
    private AppConfig appConfig;

    @BeforeAll
    static void shared() {
        Map<String, String> envs = new HashMap<>();
        envs.put(ApplicationMode.IAM_MODE_KEY, ApplicationMode.DEV);
        EnvHelper.setEnv(envs);
    }

    @BeforeEach
    void setUp() {
        appConfig = mock(AppConfig.class);
    }

    @Test
    void updateDefaultUserSessionExtensionTime() {
        // Use the public method instead of the private one
        when(appConfig.getProperty("user.session.timeout")).thenReturn("240000");
        long extensionSeconds = AuthenticatedUserTokenRepository.updateDefaultUserSessionExtensionTime(appConfig);
        assertEquals(240000L, extensionSeconds);
    }
}