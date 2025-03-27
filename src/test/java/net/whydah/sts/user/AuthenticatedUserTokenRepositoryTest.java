package net.whydah.sts.user;

import net.whydah.sso.config.ApplicationMode;
import net.whydah.sts.config.AppConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuthenticatedUserTokenRepositoryTest {

    @BeforeAll
    static void shared() {
        Map<String, String> envs = new HashMap<>();
        envs.put(ApplicationMode.IAM_MODE_KEY, ApplicationMode.DEV);
        EnvHelper.setEnv(envs);
    }

    @Test
    void updateDefaultUserSessionExtensionTime() {
        // Create test properties
        Properties testProperties = new Properties();
        testProperties.setProperty("user.session.timeout", "240000");

        // Using try-with-resources to ensure the static mock is properly closed
        try (MockedStatic<AppConfig> mockedStatic = Mockito.mockStatic(AppConfig.class);
             MockedStatic<ApplicationMode> mockedApplicationMode = Mockito.mockStatic(ApplicationMode.class)) {

            // Mock ApplicationMode.getApplicationMode()
            mockedApplicationMode.when(ApplicationMode::getApplicationMode)
                    .thenReturn("TEST");

            // Mock the static getProperty method
            mockedStatic.when(() -> AppConfig.getProperty("user.session.timeout"))
                    .thenReturn("240000");

            // Create an instance of AppConfig for the test
            AppConfig appConfig = Mockito.mock(AppConfig.class);
            Mockito.when(appConfig.getProperty("user.session.timeout")).thenReturn("240000");

            // Call the method under test
            long extensionSeconds = AuthenticatedUserTokenRepository.updateDefaultUserSessionExtensionTime(appConfig);

            // Verify the result
            assertEquals(240000L, extensionSeconds, "Session extension time should match the configured value");
        }
    }
}