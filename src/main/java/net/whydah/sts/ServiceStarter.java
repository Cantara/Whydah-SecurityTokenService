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
import org.glassfish.grizzly.servlet.WebappContext;
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

import jakarta.servlet.ServletRegistration;
import jakarta.servlet.http.HttpServletMapping;
import net.whydah.sso.config.ApplicationMode;
import net.whydah.sts.application.ApplicationResource;
import net.whydah.sts.config.AppBinder;
import net.whydah.sts.config.AppConfig;
import net.whydah.sts.health.HealthResource;
import net.whydah.sts.user.AuthenticatedUserTokenRepository;
import net.whydah.sts.user.UserTokenResource;

public class ServiceStarter {
    private static final Logger log = LoggerFactory.getLogger(ServiceStarter.class);
    private HttpServer httpServer;

    private HttpServletMapping httpServletMapping;
    private int webappPort;
    private static final String CONTEXTPATH = "/tokenservice";
    public static final String IMPLEMENTATION_VERSION = ServiceStarter.class.getPackage().getImplementationVersion();

    private static KeyPair publicKeyPair;

    public static void main(String[] args) throws IOException {
        //http://www.slf4j.org/legacy.html#jul-to-slf4j
        LogManager.getLogManager().reset();
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();
        LogManager.getLogManager().getLogger("").setLevel(Level.INFO);
        setupPublicKey();
        ServiceStarter serviceStarter = new ServiceStarter();
        serviceStarter.startServer();
       
        
     // add jvm shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        	try {
        		System.out.println("Shutting down the application...");

        		 serviceStarter.stop();
        		
        		System.out.println("Done, exit.");
        	} catch (Exception e) {
        		 log.error("Running server killed");
        	}
        }));

        try {
            // wait forever...
            Thread.currentThread().join();
        } catch (InterruptedException ie) {
            log.error("Running server killed by interrupt");
        }
    }

    private static void setupPublicKey() {
        try {
            if (publicKeyPair != null) {
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

        //Injector injector = Guice.createInjector(new SecurityTokenServiceModule(appConfig, appMode));

        log.info("Starting SecurityTokenService... version:{}", IMPLEMENTATION_VERSION);


        //Start Valuereporter event distributer.
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



        //final WebappContext context = new WebappContext("grizzly", CONTEXTPATH);
        //GuiceContainer container = new GuiceContainer(injector);
        //final WebappContext context2 = new WebappContext("grizzly", CONTEXTPATH);
        //context2.addServlet("ServletContainer", new SecurityTokenServiceModule(appConfig, appMode));
        //final ServletRegistration servletRegistration = context2.addServlet("ServletContainer", CONTEXTPATH);
        //final ServletRegistration servletRegistration = container.addServlet("ServletContainer", container);

        //servletRegistration.addMapping("/*");
        //servletRegistration.setInitParameter("com.sun.jersey.config.property.packages", "net.whydah");
        //servletRegistration.setInitParameter("com.sun.jersey.api.json.POJOMappingFeature", "true");
        //context2.addFilter("guiceFilter", "GuiceFilter.class");
        //.addFilter("guiceFilter", GuiceFilter.class);

        //final WebappContext context = new WebappContext("grizzly", CONTEXTPATH);
        //GuiceContainer container = new GuiceContainer(injector);
//        final ServletRegistration servletRegistration = context.addServlet("ServletContainer", new SecurityTokenServiceModule(appConfig, appMode));
//        servletRegistration.addMapping("/*");
//        servletRegistration.setInitParameter("com.sun.jersey.config.property.packages", "net.whydah");
//        servletRegistration.setInitParameter("com.sun.jersey.api.json.POJOMappingFeature", "true");
//        context.addFilter("guiceFilter", GuiceFilter.class);


        

        try {
           // SecurityTokenServiceModule securityTokenServiceModule=  new SecurityTokenServiceModule(appConfig, appMode);
            webappPort = Integer.valueOf(appConfig.getProperty("service.port"));

        } catch (Exception e) {
            webappPort = 9990;
        }
        //ResourceConfig config = new ResourceConfig().packages("net.whydah");
        //HK2toGuiceModule hk2Module = new HK2toGuiceModule(injector);
      
        ResourceConfig config = new ResourceConfig()
        	    .packages("net.whydah")
        	    .register(MoxyXmlFeature.class)
        	    .register(FreemarkerMvcFeature.class) // register FreemarkerMVC
        	    .property(FreemarkerMvcFeature.TEMPLATE_BASE_PATH, "templates")
        	    .property(ServletProperties.FILTER_FORWARD_ON_404, true)
        	    .register(new UserTokenResource())
        	    .register(new ApplicationResource())
        	    .register(new HealthResource())
        	    .register(new AppBinder(appMode))
        	    ;
      
        //register(FreemarkerMvcFeature.class);;
        // Create and start a grizzly http server
        //HttpServer httpServer = GrizzlyHttpServerFactory.createHttpServer(URI.create(BASE_URI), config);

        System.out.println("http://0.0.0.0:"+webappPort+""+CONTEXTPATH+"/");
        String BASE_URI= "http://0.0.0.0:"+webappPort+""+CONTEXTPATH+"/";
        httpServer = GrizzlyHttpServerFactory.createHttpServer(URI.create(BASE_URI), config);
        //new HttpServer();
        
        //ServerConfiguration serverconfig = httpServer.getServerConfiguration();
        //serverconfig.addHttpHandler(handler, "/");
        NetworkListener listener = new NetworkListener("grizzly server", NetworkListener.DEFAULT_NETWORK_HOST, webappPort);

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


        httpServer.addListener(listener);


        //context2.deploy(httpServer);

        httpServer.start();


        AuthenticatedUserTokenRepository.initializeDistributedMap();  // Kick-off hazelcast distributed tokensession-map

        log.info("SecurityTokenService started on port {}, IAM_MODE = {}", webappPort, ApplicationMode.getApplicationMode());
        log.info("Version: {}", IMPLEMENTATION_VERSION);
        log.info("Status: http://localhost:{}/{}/", webappPort, CONTEXTPATH);
        log.info("Health: http://localhost:{}/{}/health", webappPort, CONTEXTPATH);
        log.info("WADL:   http://localhost:{}/{}/application.wadl", webappPort, CONTEXTPATH);
        log.info("Testpage = {}, TestDriverWeb:   http://localhost:{}{}/", appConfig.getProperty("testpage"), webappPort, CONTEXTPATH);
    }

    public int getPort() {
        return webappPort;
    }

    public static KeyPair getPublicKeyPair() {
        return publicKeyPair;
    }

    public void stop() {
        if (httpServer != null) {
            httpServer.shutdownNow();
        }
    }
}
