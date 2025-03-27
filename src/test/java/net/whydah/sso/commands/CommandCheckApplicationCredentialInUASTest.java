package net.whydah.sso.commands;

import net.whydah.sso.application.types.ApplicationToken;
import net.whydah.sso.commands.systemtestbase.SystemTestBaseConfig;
import net.whydah.sts.application.AuthenticatedApplicationTokenRepository;
import net.whydah.sts.application.authentication.commands.CommandCheckApplicationCredentialInUAS;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.slf4j.LoggerFactory.getLogger;

public class CommandCheckApplicationCredentialInUASTest {
    private static final Logger log = getLogger(CommandCheckApplicationCredentialInUASTest.class);


    static SystemTestBaseConfig config;

    @BeforeAll
    public static void setup() throws Exception {
        config = new SystemTestBaseConfig();
    }


    @Test
    @Disabled
    public void testCommandCheckApplicationCredentialInUAS() throws Exception {
        if (config.isSystemTestEnabled()) {
            //     WhydahApplicationSession applicationSession = WhydahApplicationSession.getInstance(config.tokenServiceUri.toString(), config.appCredential);

            ApplicationToken stsToken = AuthenticatedApplicationTokenRepository.getSTSApplicationToken();

            boolean isOk = new CommandCheckApplicationCredentialInUAS(config.userAdminServiceUri, "1bedfc858da713936ebd925e7cf4f776", config.appCredential).execute();
            assertTrue(isOk);
        }

    }
}