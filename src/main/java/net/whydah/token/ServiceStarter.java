package net.whydah.token;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.servlet.GuiceFilter;
import net.whydah.token.config.AppConfig;
import net.whydah.token.config.ApplicationMode;
import net.whydah.token.config.SecurityTokenServiceModule;
import net.whydah.token.user.ActiveUserTokenRepository;
import net.whydah.token.user.UserTokenResource;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.NetworkListener;
import org.glassfish.grizzly.http.server.ServerConfiguration;
import org.glassfish.grizzly.servlet.ServletHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.valuereporter.agent.activity.ObservedActivityDistributer;
import org.valuereporter.agent.http.HttpObservationDistributer;

import java.io.IOException;

public class ServiceStarter {
    private final static Logger log = LoggerFactory.getLogger(ServiceStarter.class);
    private HttpServer httpServer;
    private int webappPort;
    private final String contextpath="/tokenservice";
    public static String version;


    public static void main(String[] args) throws IOException {
        ServiceStarter serviceStarter = new ServiceStarter();
        serviceStarter.startServer();
        try {
            // wait forever...
            Thread.currentThread().join();
        } catch (InterruptedException ie) {
        }
        serviceStarter.stop();
    }

    protected void startServer() throws IOException {
        String appMode = ApplicationMode.getApplicationMode();
        AppConfig appConfig = new AppConfig();
        version = this.getClass().getPackage().getImplementationVersion();

        Injector injector = Guice.createInjector(new SecurityTokenServiceModule(appConfig, appMode));

        log.info("Starting SecurityTokenService... version:{}",version);


        //Start Valuereporter event distributer.
        String reporterHost = appConfig.getProperty("valuereporter.host");
        String reporterPort = appConfig.getProperty("valuereporter.port");
        String prefix = appConfig.getProperty("applicationname");
        int cacheSize = Integer.parseInt(appConfig.getProperty("valuereporter.activity.batchsize"));
        int forwardInterval = Integer.parseInt(appConfig.getProperty("valuereporter.activity.postintervalms"));
        new Thread(new ObservedActivityDistributer(reporterHost, reporterPort, prefix, cacheSize, forwardInterval)).start();
        new Thread(new HttpObservationDistributer(reporterHost, reporterPort, prefix)).start();


        ServletHandler adapter = new ServletHandler();
        adapter.setContextPath(contextpath);
        adapter.addInitParameter("com.sun.jersey.config.property.packages", "net.whydah");
        adapter.setProperty(ServletHandler.LOAD_ON_STARTUP, "1");

        GuiceFilter filter = new GuiceFilter();
        adapter.addFilter(filter, "guiceFilter", null);

//        GuiceContainer container = new GuiceContainer(injector);
//        adapter.setServletInstance(container);

        webappPort = Integer.valueOf(appConfig.getProperty("service.port"));
        httpServer = new HttpServer();
        ServerConfiguration serverconfig = httpServer.getServerConfiguration();
        serverconfig.addHttpHandler(adapter, "/");
        NetworkListener listener = new NetworkListener("grizzly", NetworkListener.DEFAULT_NETWORK_HOST, webappPort);
        httpServer.addListener(listener);
        httpServer.start();

        ActiveUserTokenRepository.initializeDistributedMap();  // Kick-off hazelcast distributed tokensession-map
        UserTokenResource.initializeDistributedMap();  // Kick-off hazelcast distributed ticketmap

        log.info("SecurityTokenService started on port {}, IAM_MODE = {}", webappPort, ApplicationMode.getApplicationMode());
        log.info("Status: http://localhost:{}{}/", webappPort, contextpath);
        log.info("Health: http://localhost:{}{}/health", webappPort, contextpath);
        log.info("WADL:   http://localhost:{}{}/application.wadl", webappPort, contextpath);
        log.info("Testpage = {}, TestDriverWeb:   http://localhost:{}{}/",appConfig.getProperty("testpage"), webappPort, contextpath);
    }

    public int getPort() {
        return webappPort;
    }

    public void stop() {
        if(httpServer != null) {
            httpServer.stop();
        }
    }
}
