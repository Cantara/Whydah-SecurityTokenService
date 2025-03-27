package net.whydah.sts.health;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.whydah.sso.config.ApplicationMode;
import net.whydah.sso.whydah.ThreatSignal;
import net.whydah.sts.config.AppConfig;
import net.whydah.sts.user.AuthenticatedUserTokenRepository;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;


import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static junit.framework.TestCase.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


public class AsyncHealthServiceTest {

	//TODO: examine later
	@Disabled
    @Test
    public void testTheatSignalRingbuffer() throws InterruptedException, JsonProcessingException {
        System.setProperty(ApplicationMode.IAM_MODE_KEY, ApplicationMode.DEV);
        System.setProperty(AppConfig.IAM_CONFIG_KEY, "src/test/testconfig.properties");
        AuthenticatedUserTokenRepository.getLastSeenMapSize();
        AsyncHealthService asyncHealthService = new AsyncHealthService(100, ChronoUnit.MILLIS);
        asyncHealthService.waitUntilReady(60, TimeUnit.SECONDS);
        assertTrue(asyncHealthService.isActivelyUpdatingCurrentHealth());
        System.out.printf("JSON before any theat-signals: %s%n", asyncHealthService.getHealthJson());
        asyncHealthService.addThreatSignal(createTestThreatSignal(1));
        Thread.sleep(10);
        asyncHealthService.addThreatSignal(createTestThreatSignal(2));
        Thread.sleep(1500);
        System.out.printf("JSON after 2 threat-signals: %s%n", asyncHealthService.getHealthJson());
        asyncHealthService.addThreatSignal(createTestThreatSignal(3));
        Thread.sleep(10);
        asyncHealthService.addThreatSignal(createTestThreatSignal(4));
        Thread.sleep(10);
        asyncHealthService.addThreatSignal(createTestThreatSignal(5));
        Thread.sleep(10);
        asyncHealthService.addThreatSignal(createTestThreatSignal(6));
        Thread.sleep(1500);
        {
            String healthJson = asyncHealthService.getHealthJson();
            System.out.printf("JSON after 6 threat-signals: %s%n", healthJson);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode healthNode = mapper.readTree(healthJson);
            JsonNode threatSignalsNode = healthNode.get("threat_signals");
            List<ThreatSignal> threatSignals = mapper.convertValue(threatSignalsNode, mapper.getTypeFactory().constructCollectionType(List.class, ThreatSignal.class));
            assertEquals(6, threatSignals.size());
            assertEquals("test-text-1", threatSignals.getFirst().getText());
            assertEquals("test-text-2", threatSignals.get(1).getText());
            assertEquals("test-text-3", threatSignals.get(2).getText());
            assertEquals("test-text-4", threatSignals.get(3).getText());
            assertEquals("test-text-5", threatSignals.get(4).getText());
            assertEquals("test-text-6", threatSignals.get(5).getText());
        }
        asyncHealthService.shutdown();
        assertTrue(asyncHealthService.waitUntilShutdown(5, TimeUnit.SECONDS));
        Thread.sleep(100);
        assertFalse(asyncHealthService.isActivelyUpdatingCurrentHealth());
    }

    private ThreatSignal createTestThreatSignal(int i) {
        return new ThreatSignal("test-source", "test-emitter", "test-instant", "test-severity", "test-text-" + i);
    }
}