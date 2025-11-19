package net.whydah.sts;

import java.io.IOException;
import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.util.logging.Level;
import java.util.logging.LogManager;

import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.NetworkListener;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.glassfish.grizzly.strategies.WorkerThreadIOStrategy;
import org.glassfish.grizzly.threadpool.ThreadPoolConfig;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.moxy.xml.MoxyXmlFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.mvc.freemarker.FreemarkerMvcFeature;
import org.glassfish.jersey.servlet.ServletProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;
import org.valuereporter.client.activity.ObservedActivityDistributer;

import net.whydah.sso.config.ApplicationMode;
import net.whydah.sts.application.ApplicationResource;
import net.whydah.sts.config.AppBinder;
import net.whydah.sts.config.AppConfig;
import net.whydah.sts.config.ServiceLocatorInitializationFilter;
import net.whydah.sts.health.HealthResource;
import net.whydah.sts.slack.AppLifecycleNotifier;
import net.whydah.sts.smsgw.LoggingDLRHandler;
import net.whydah.sts.smsgw.Target365DLRResource;
import net.whydah.sts.threat.ThreatResource;
import net.whydah.sts.user.AuthenticatedUserTokenRepository;
import net.whydah.sts.user.UserTokenResource;

public class ServiceStarter {
    private static final Logger log = LoggerFactory.getLogger(ServiceStarter.class);
    private HttpServer httpServer;
    private AppBinder appBinder;
    private int webappPort;
    private static final String CONTEXTPATH = "/tokenservice";
    private static final String SERVICE_NAME = "STS";
    public static final String IMPLEMENTATION_VERSION = ServiceStarter.class.getPackage().getImplementationVersion();

    private static KeyPair publicKeyPair;
    private AppLifecycleNotifier lifecycleNotifier;

    public static void main(String[] args) {
        // http://www.slf4j.org/legacy.html#jul-to-slf4j
        LogManager.getLogManager().reset();
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();
        LogManager.getLogManager().getLogger("").setLevel(Level.INFO);
        
        setupPublicKey();
        
        ServiceStarter serviceStarter = new ServiceStarter();
        
        try {
            serviceStarter.startServer();
            
            // Send startup success notification
            if (serviceStarter.lifecycleNotifier != null) {
                serviceStarter.lifecycleNotifier.notifyStartupSuccess();
            }
            
        } catch (Exception e) {
            log.error("Failed to start SecurityTokenService", e);
            
            // Send startup failure notification
            if (serviceStarter.lifecycleNotifier != null) {
                serviceStarter.lifecycleNotifier.notifyStartupFailure(e);
            }
            
            System.exit(1);
        }
       
        // Add JVM shutdown hook
        final AppLifecycleNotifier finalNotifier = serviceStarter.lifecycleNotifier;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                log.info("Shutting down the application...");
                
                // Send shutdown notification
                if (finalNotifier != null) {
                    finalNotifier.notifyShutdown();
                }
                
                serviceStarter.stop();
                log.info("Application shutdown complete.");
            } catch (Exception e) {
                log.error("Error during application shutdown", e);
            }
        }));

        try {
            Thread.currentThread().join();
        } catch (InterruptedException ie) {
            log.error("Running server interrupted", ie);
            Thread.currentThread().interrupt();
        }
    }

    private static void setupPublicKey() {
        try {
            if (publicKeyPair == null) {
                KeyPairGenerator keyGen = KeyPairGenerator.getInstance("DSA", "SUN");
                SecureRandom random = SecureRandom.getInstance("SHA1PRNG", "SUN");
                keyGen.initialize(1024, random);
                KeyPair pair = keyGen.generateKeyPair();
                publicKeyPair = pair;
            }
        } catch (Exception e) {
            log.error("Unable to create pgp security", e);
        }
    }

    protected void startServer() throws IOException {
        String appMode = ApplicationMode.getApplicationMode();
        AppConfig appConfig = new AppConfig();

        log.info("Starting SecurityTokenService... version:{}", IMPLEMENTATION_VERSION);

        // Start Valuereporter event distributer
        try {
            String reporterHost = appConfig.getProperty("valuereporter.host");
            String reporterPort = appConfig.getProperty("valuereporter.port");
            String prefix = appConfig.getProperty("applicationname").replace(" ", "");
            int cacheSize = Integer.parseInt(appConfig.getProperty("valuereporter.activity.batchsize"));
            int forwardInterval = Integer.parseInt(appConfig.getProperty("valuereporter.activity.postintervalms"));
            new Thread(ObservedActivityDistributer.getInstance(reporterHost, reporterPort, prefix, cacheSize, forwardInterval)).start();
            log.info("Started ObservedActivityDistributer({},{},{},{},{})", reporterHost, reporterPort, prefix, cacheSize, forwardInterval);
        } catch (Exception e) {
            log.warn("Error in valueReporter property configuration - unable to start ObservedActivityDistributer", e);
        }

        try {
            webappPort = Integer.valueOf(appConfig.getProperty("service.port"));
        } catch (Exception e) {
            webappPort = 9990;
            log.warn("Could not read service.port from config, using default: {}", webappPort);
        }

        // Initialize lifecycle notifier
        lifecycleNotifier = AppLifecycleNotifier.builder()
                .serviceName(SERVICE_NAME)
                .version(IMPLEMENTATION_VERSION != null ? IMPLEMENTATION_VERSION : "unknown")
                .applicationMode(appMode)
                .port(webappPort)
                .contextPath(CONTEXTPATH)
                .addContext("hostname", getHostname())
                .build();

        // Create AppBinder instance to manage lifecycle
        appBinder = new AppBinder(appMode);
        
        // Configure Jersey ResourceConfig
        ResourceConfig config = new ResourceConfig()
            .packages("net.whydah")
            .register(MoxyXmlFeature.class)
            .register(FreemarkerMvcFeature.class)
            .property(FreemarkerMvcFeature.TEMPLATE_BASE_PATH, "templates")
            .property(ServletProperties.FILTER_FORWARD_ON_404, true)
            .register(ServiceLocatorInitializationFilter.class)
            .register(new Target365DLRResource())
            .register(new UserTokenResource())
            .register(new ApplicationResource())
            .register(new HealthResource())
            .register(new ThreatResource())
            .register(appBinder);

        String baseUri = "http://0.0.0.0:" + webappPort + CONTEXTPATH + "/";
        log.info("Creating HTTP server at: {}", baseUri);
        
        httpServer = GrizzlyHttpServerFactory.createHttpServer(URI.create(baseUri), config, false);
        
        // Configure custom transport settings
        for (NetworkListener listener : httpServer.getListeners()) {
            TCPNIOTransportBuilder builder = TCPNIOTransportBuilder.newInstance();
            builder.setIOStrategy(WorkerThreadIOStrategy.getInstance());
            builder.setTcpNoDelay(true);
            
            int selectorCorePoolSize = Runtime.getRuntime().availableProcessors();
            builder.setSelectorThreadPoolConfig(ThreadPoolConfig.defaultConfig()
                    .setPoolName("Grizzly-selector")
                    .setCorePoolSize(selectorCorePoolSize)
                    .setMaxPoolSize(selectorCorePoolSize)
                    .setMemoryManager(builder.getMemoryManager())
            );
            
            builder.setWorkerThreadPoolConfig(ThreadPoolConfig.defaultConfig()
                    .setPoolName("Grizzly-worker")
                    .setCorePoolSize(50)
                    .setMaxPoolSize(300)
                    .setMemoryManager(builder.getMemoryManager())
            );
            
            TCPNIOTransport transport = builder.build();
            listener.setTransport(transport);
        }

        httpServer.start();

        AuthenticatedUserTokenRepository.initializeDistributedMap();
        LoggingDLRHandler.initializeMonitor(
                AuthenticatedUserTokenRepository.getHazelcastInstance(),
                AuthenticatedUserTokenRepository.getGridPrefix()
            );
        
        log.info("================================================================================");
        log.info("SecurityTokenService started successfully");
        log.info("================================================================================");
        log.info("Version:        {}", IMPLEMENTATION_VERSION);
        log.info("Port:           {}", webappPort);
        log.info("Mode:           {}", appMode);
        log.info("Context Path:   {}", CONTEXTPATH);
        log.info("================================================================================");
        log.info("Endpoints:");
        log.info("  Status:       http://localhost:{}{}/", webappPort, CONTEXTPATH);
        log.info("  Health:       http://localhost:{}{}/health", webappPort, CONTEXTPATH);
        log.info("  WADL:         http://localhost:{}{}/application.wadl", webappPort, CONTEXTPATH);
        log.info("  Test Page:    http://localhost:{}{}/{}", webappPort, CONTEXTPATH, appConfig.getProperty("testpage"));
        log.info("================================================================================");
    }

    public int getPort() {
        return webappPort;
    }

    public static KeyPair getPublicKeyPair() {
        return publicKeyPair;
    }

    public void stop() {
        log.info("Stopping SecurityTokenService...");
        
        // Stop UserToken map monitor
        try {
            AuthenticatedUserTokenRepository.shutdownMonitor();
        } catch (Exception e) {
            log.error("Error shutting down UserTokenMapMonitor", e);
        }
        
        // Shutdown Slack notification service
        if (appBinder != null) {
            try {
                appBinder.shutdown();
            } catch (Exception e) {
                log.error("Error shutting down AppBinder services", e);
            }
        }
        
        // Shutdown HTTP server
        if (httpServer != null) {
            try {
                httpServer.shutdownNow();
                log.info("HTTP server stopped");
            } catch (Exception e) {
                log.error("Error stopping HTTP server", e);
            }
        }
        
        log.info("SecurityTokenService stopped");
    }
    
    private String getHostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}