package net.whydah.sts.health;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hazelcast.config.Config;
import com.hazelcast.config.XmlConfigBuilder;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.ringbuffer.OverflowPolicy;
import com.hazelcast.ringbuffer.ReadResultSet;
import com.hazelcast.ringbuffer.Ringbuffer;
import net.whydah.sso.util.WhydahUtil;
import net.whydah.sso.whydah.ThreatSignal;
import net.whydah.sts.application.ApplicationModelFacade;
import net.whydah.sts.application.AuthenticatedApplicationTokenRepository;
import net.whydah.sts.config.AppConfig;
import net.whydah.sts.threat.ThreatResource;
import net.whydah.sts.user.AuthenticatedUserTokenRepository;
import net.whydah.sts.user.authentication.ActivePinRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.TemporalUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public class AsyncHealthService implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(AsyncHealthService.class);

    static final int MAX_THREAT_SIGNALS_IN_HEALTH = 1000;

    /*
     * Thread-safe state
     */
    private final AtomicInteger serviceSequence = new AtomicInteger();
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<String> currentHealthSerialized;
    private final Instant timeAtStart = Instant.now();
    private final Thread healthUpdateThread;
    private final AtomicBoolean shouldRun = new AtomicBoolean(true);
    private final long updateInterval;
    private final TemporalUnit updateIntervalUnit;
    private final AppConfig appConfig;
    private final String applicationInstanceName;
    private final String version;
    private final AtomicLong healthComputeTimeMs = new AtomicLong(-1);
    private final boolean isExtendedInfoEnabled;
    private final AtomicReference<Ringbuffer<String>> threatSignalRingbufferRef = new AtomicReference<>();
    private final CountDownLatch readyLatch = new CountDownLatch(1);
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    /*
     * State only that is only read and written by the healthUpdateThread, so no need for synchronization
     */
    ObjectNode currentHealth;
    long nextThreatSignalSequenceToRead;
    long nextThreatSignalSequenceInCurrentHealth;
    final List<ThreatSignal> obfuscatedThreatSignalList = new ArrayList<>();

    public AsyncHealthService(long updateInterval, TemporalUnit updateIntervalUnit) {
        this.updateInterval = updateInterval;
        this.updateIntervalUnit = updateIntervalUnit;
        this.appConfig = new AppConfig();
        this.applicationInstanceName = appConfig.getProperty("applicationname");
        this.version = readVersion();
        {
            ObjectNode health = mapper.createObjectNode();
            health.put("Status", "false");
            health.put("version", version);
            health.put("running since", timeAtStart.toString());
            this.currentHealthSerialized = new AtomicReference<>(health.toPrettyString());
        }
        this.isExtendedInfoEnabled = (appConfig.getProperty("testpage").equalsIgnoreCase("enabled"));
        this.healthUpdateThread = new Thread(this, "health-updater-" + serviceSequence.incrementAndGet());
        this.healthUpdateThread.start();
    }

    public boolean isActivelyUpdatingCurrentHealth() {
        return shouldRun.get() && healthUpdateThread.isAlive();
    }

    public boolean waitUntilReady(long timeout, TimeUnit unit) throws InterruptedException {
        return readyLatch.await(timeout, unit);
    }

    public boolean waitUntilShutdown(long timeout, TimeUnit unit) throws InterruptedException {
        return shutdownLatch.await(timeout, unit);
    }

    public String getHealthJson() {
        String currentHealthJsonWithoutTimestamp = currentHealthSerialized.get();
        ObjectNode health;
        try {
            health = (ObjectNode) mapper.readTree(currentHealthJsonWithoutTimestamp);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        long healthComputeTimeMs = getHealthComputeTimeMs();
        boolean activelyUpdatingCurrentHealth = isActivelyUpdatingCurrentHealth();
        if (!activelyUpdatingCurrentHealth) {
            health.put("Status", "FAIL");
            health.put("errorMessage", "health-updater-thread is dead.");
        }
        health.put("now", Instant.now().toString());
        health.put("health-compute-time-ms", String.valueOf(healthComputeTimeMs));
        health.put("health-updater-thread-alive", String.valueOf(activelyUpdatingCurrentHealth));
        String healthJson = health.toPrettyString();
        return healthJson;
    }

    public long getHealthComputeTimeMs() {
        return healthComputeTimeMs.get();
    }

    public void shutdown() {
        shouldRun.set(false);
    }

    @Override
    public void run() {
        try {
            log.info("Initializing hazelcast threat-signal ringbuffer...");
            String xmlFileName = System.getProperty("hazelcast.config");
            if (xmlFileName == null || xmlFileName.trim().isEmpty()) {
                xmlFileName = appConfig.getProperty("hazelcast.config");
            }
            log.info("Loading hazelcast configuration from :" + xmlFileName);
            Config hazelcastConfig = new Config();
            if (xmlFileName != null && xmlFileName.length() > 10) {
                try {
                    hazelcastConfig = new XmlConfigBuilder(xmlFileName).build();
                    log.info("Loading hazelcast configuration from :" + xmlFileName);
                } catch (FileNotFoundException notFound) {
                    log.error("Error - not able to load hazelcast.xml configuration.  Using embedded as fallback");
                }
            }
            //hazelcastConfig.getGroupConfig().setName("STS_HAZELCAST");
            hazelcastConfig.setProperty("hazelcast.logging.type", "slf4j");

            HazelcastInstance hazelcastInstance=null;
            try {
                hazelcastInstance = Hazelcast.newHazelcastInstance(hazelcastConfig);
            } catch (Exception ex) {
                log.error("Trouble with hz initialization",ex);
                try {
                    hazelcastInstance = Hazelcast.newHazelcastInstance();

                } catch (Exception exx){
                    log.error("Trouble-exx with hz initialization",exx);

                }
            }
            log.info("Connecting to threatSignalRingbuffer {}", appConfig.getProperty("gridprefix") + "threatSignalRingbuffer");
            if (hazelcastInstance!=null) {
                Ringbuffer<String> threatSignalRingbuffer = hazelcastInstance.getRingbuffer(appConfig.getProperty("gridprefix") + "threatSignalRingbuffer");
                threatSignalRingbufferRef.set(threatSignalRingbuffer);
                nextThreatSignalSequenceToRead = threatSignalRingbuffer.headSequence();
                nextThreatSignalSequenceInCurrentHealth = nextThreatSignalSequenceToRead;
                log.info("Loaded threatSignalRingbuffer size={}, capacity={}", threatSignalRingbuffer.size(), threatSignalRingbuffer.capacity());
            }
            // Initializing
            log.info("Health initializing first full health...");
            try {
                currentHealth = mapper.createObjectNode();
                currentHealth.put("Status", "false");
                currentHealth.put("version", version);
                currentHealth.put("running since", timeAtStart.toString());
                // first health-update can be very slow, have to wait for Hazelcast init
                updateHealth(currentHealth);
                currentHealthSerialized.set(currentHealth.toPrettyString());
            } catch (Throwable t) {
                log.warn("While setting health initialization message", t);
            }
//            while (shouldRun.get() && SessionDao.instance.getServiceClient() == null) {
//                try {
//                    serviceClient = new WhydahServiceClient();
//                } catch (Throwable t) {
//                    log.warn("Unable to create WhydahServiceClient", t);
//                    try {
//                        Thread.sleep(2000); // wait at least a very few seconds before trying again
//                    } catch (InterruptedException e) {
//                        log.warn("Interrupted while waiting before trying to create serviceClient again", e);
//                    }
//                }
//            }

            readyLatch.countDown();
            log.info("Health initialized.");

            // health-update loop
            while (shouldRun.get()) {
                try {
                    boolean changed = updateHealth(currentHealth);
                    if (changed) {
                        currentHealthSerialized.set(currentHealth.toPrettyString());
                    }
                    Thread.sleep(Duration.of(updateInterval, updateIntervalUnit).toMillis());
                } catch (Throwable t) {
                    log.error("While updating health", t);
                    {
                        ObjectNode health = mapper.createObjectNode();
                        health.put("Status", "FAIL");
                        health.put("errorMessage", "Exception while updating health");
                        StringWriter strWriter = new StringWriter();
                        t.printStackTrace(new PrintWriter(strWriter));
                        health.put("errorCause", strWriter.toString());
                        currentHealthSerialized.set(health.toPrettyString());
                    }
                    Thread.sleep(Duration.of(updateInterval, updateIntervalUnit).toMillis());
                }
            }
        } catch (Throwable t) {
            log.error("Update thread died!");
            {
                ObjectNode health = mapper.createObjectNode();
                health.put("Status", "FAIL");
                health.put("errorMessage", "Health updater thread died with an unexpected error");
                StringWriter strWriter = new StringWriter();
                t.printStackTrace(new PrintWriter(strWriter));
                health.put("errorCause", strWriter.toString());
                currentHealthSerialized.set(health.toPrettyString());
            }
        } finally {
            shutdownLatch.countDown();
        }
    }

    private boolean updateHealth(ObjectNode health) {
        long start = System.currentTimeMillis();
        int applicationMapSize = ApplicationModelFacade.getApplicationList().size(); // TODO this causes 1 second delay for unit-tests and an ugly stacktrace from failed request in http-client
        boolean changed = false;
        changed |= updateField(health, "Status", "OK");
        changed |= updateField(health, "DEFCON", ThreatResource::getDEFCON);
        changed |= updateField(health, "max application session time", String.valueOf(AuthenticatedApplicationTokenRepository.DEFAULT_APPLICATION_SESSION_EXTENSION_TIME_IN_SECONDS));
        changed |= updateField(health, "max user session fallback time", String.valueOf(AuthenticatedUserTokenRepository.DEFAULT_USER_SESSION_EXTENSION_TIME_IN_MILLISECONDS));
        changed |= updateField(health, "ClusterSize", AuthenticatedUserTokenRepository::getNoOfClusterMembers);
        changed |= updateField(health, "UserLastSeenMapSize", AuthenticatedUserTokenRepository::getLastSeenMapSize);
        changed |= updateField(health, "UserPinMapSize", () -> ActivePinRepository.getPinMap().size());
        changed |= updateField(health, "AuthenticatedUserTokenMapSize", AuthenticatedUserTokenRepository::getMapSize);
        changed |= updateField(health, "AuthenticatedApplicationRepositoryMapSize", AuthenticatedApplicationTokenRepository::getMapSize);
        changed |= updateField(health, "ConfiguredApplications", () -> applicationMapSize);
        changed |= updateField(health, "ThreatSignalRingbufferSize", () -> threatSignalRingbufferRef.get().size());
        try {
            if (isExtendedInfoEnabled) {
                changed |= updateField(health, "AuthenticatedApplicationKeyMapSize", AuthenticatedApplicationTokenRepository::getKeyMapSize);
                changed |= updateField(health, "ActiveApplications", AuthenticatedApplicationTokenRepository::getActiveApplications);
                checkForNewTheatSignalsAndUpdateObfuscatedList();
                if (nextThreatSignalSequenceToRead != nextThreatSignalSequenceInCurrentHealth) {
                    // nextThreatSignalSequenceToRead has changed from the last time we checked,
                    // this means there are new items in the obfuscatedThreatSignalList
                    ArrayNode threatSignalsHealthValue = mapper.convertValue(obfuscatedThreatSignalList, ArrayNode.class);
                    changed |= updateField(health, "threat_signals", () -> threatSignalsHealthValue);
                    nextThreatSignalSequenceInCurrentHealth = nextThreatSignalSequenceToRead;
                }
            }
        } catch (Exception e){
            log.warn("Issues:",e);
        }
        long end = System.currentTimeMillis();
        healthComputeTimeMs.set(end - start);
        return changed;
    }

    private void checkForNewTheatSignalsAndUpdateObfuscatedList() {
        Ringbuffer<String> ringbuffer = threatSignalRingbufferRef.get();
        if (ringbuffer.size() > 0 && ringbuffer.tailSequence() >= nextThreatSignalSequenceToRead) {
            ReadResultSet<String> rs = ringbuffer.readManyAsync(nextThreatSignalSequenceToRead, 0, 100, null).toCompletableFuture().join();
            for (int i = 0; i < rs.size(); i++) {
                String theatSignalJson = rs.get(i);
                ThreatSignal theatSignal;
                try {
                    theatSignal = mapper.readValue(theatSignalJson, ThreatSignal.class);
                } catch (JsonProcessingException e) {
                    log.error("Unable to deserialize threat-signal, json: %s".formatted(theatSignalJson), e);
                    continue;
                }
                obfuscateThreatSignal(theatSignal);
                obfuscatedThreatSignalList.add(theatSignal);
                if (obfuscatedThreatSignalList.size() > MAX_THREAT_SIGNALS_IN_HEALTH) {
                    obfuscatedThreatSignalList.remove(0);
                }
            }
            if (rs.getNextSequenceToReadFrom() != -1) {
                nextThreatSignalSequenceToRead = rs.getNextSequenceToReadFrom();
            }
        }
    }

    private void obfuscateThreatSignal(ThreatSignal threatSignal) {
        threatSignal.setSignalEmitter(threatSignal.getSignalEmitter().replace("a", "*").replace("b", "*").replace("c", "*"));
        Map<String, Object> additionalProperties = threatSignal.getAdditionalProperties();

        List<String> obfuscateList = Arrays.asList("usertokenid", "apptokenid", "appName");
        for (String pro : additionalProperties.keySet()) {
            if (obfuscateList.contains(pro)) {
                threatSignal.getAdditionalProperties().put(pro, threatSignal.getAdditionalProperties().get(pro).toString().replace("a", "*").replace("b", "*").replace("c", "*"));
            }
        }
    }

    public void addThreatSignal(ThreatSignal signal) {
        String signalJson;
        try {
            signalJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(signal);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        Ringbuffer<String> ringbuffer = threatSignalRingbufferRef.get();
        if (ringbuffer != null) {
            ringbuffer.addAsync(signalJson, OverflowPolicy.OVERWRITE);
        }
    }

    private boolean updateField(ObjectNode health, String key, Supplier<Object> valueConsumer) {
        Object value = null;
        try {
            value = valueConsumer.get();
        } catch (Throwable t) {
            log.warn("Ignoring health field, error while attempting to compute field: '%s'".formatted(key), t);
        }
        if (value == null) {
            return updateField(health, key, (String) null);
        }
        if (value instanceof String string) {
            return updateField(health, key, string);
        }
        if (value instanceof JsonNode node) {
            health.set(key, node);
            return true;
        }
        return updateField(health, key, String.valueOf(value));
    }

    private boolean updateField(ObjectNode health, String key, String value) {
        JsonNode field = health.get(key);
        if (value == null) {
            if (field == null) {
                return false;
            }
            health.remove(key);
            return true;
        }
        if (field != null && !field.isNull() && field.isTextual() && field.textValue().equals(value)) {
            return false;
        }
        health.put(key, value);
        return true;
    }

    private String readVersion() {
        Properties mavenProperties = new Properties();
        String resourcePath = "/META-INF/maven/net.whydah.token/SecurityTokenService/pom.properties";
        URL mavenVersionResource = HealthResource.class.getResource(resourcePath);
        if (mavenVersionResource != null) {
            try {
                mavenProperties.load(mavenVersionResource.openStream());
                return mavenProperties.getProperty("version", "missing version info in " + resourcePath) + " [" + applicationInstanceName + " - " + WhydahUtil.getMyIPAddresssesString() + "]";
            } catch (IOException e) {
                log.warn("Problem reading version resource from classpath: ", e);
            }
        }
        return "(DEV VERSION)" + " [" + applicationInstanceName + " - " + WhydahUtil.getMyIPAddresssesString() + "]";
    }

    Instant getRunningSince() {
        return timeAtStart;
    }
}
