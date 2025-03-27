package net.whydah.sts.config;

import net.whydah.sso.config.ApplicationMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.*;

/**
 * Helper methods for reading configuration.
 */
public class AppConfig {
    public static final String IAM_CONFIG_KEY = "IAM_CONFIG";
    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    private static Properties properties=null;
    private static String fullTokenApplications;
    private static List<String> preDefinedFullTokenApplications = new ArrayList<String>();
    private static java.util.Random rand = new SecureRandom();

    public String getProperty(String key) {
        return properties.getProperty(key);
    }

    public AppConfig() {
        if (rand.nextInt(100)>95 || properties==null) {  // reload properties on 5% of the calls or if not loaded
            try {
                properties = readProperties(ApplicationMode.getApplicationMode());
                logProperties(properties);
                String predefinedFullTokenApplicationConfig = getProperty("fulltokenapplications");
                if(predefinedFullTokenApplicationConfig!=null && predefinedFullTokenApplicationConfig.contains(",")){
                	preDefinedFullTokenApplications = Arrays.asList(predefinedFullTokenApplicationConfig.split("\\s*,\\s*"));
                }
            } catch (IOException e) {
                throw new RuntimeException(e.getLocalizedMessage(), e);
            }
        }
    }
    
    static {
    	if (properties==null) {  // reload properties on 5% of the calls or if not loaded
            try {
                properties = readProperties(ApplicationMode.getApplicationMode());
                String predefinedFullTokenApplicationConfig = properties.getProperty("fulltokenapplications");
                if(predefinedFullTokenApplicationConfig!=null && predefinedFullTokenApplicationConfig.contains(",")){
                	preDefinedFullTokenApplications = Arrays.asList(predefinedFullTokenApplicationConfig.split("\\s*,\\s*"));
                }
            } catch (IOException e) {
                throw new RuntimeException(e.getLocalizedMessage(), e);
            }
        }
    }
    
    private void logProperties(Properties properties) {
        Set keys = properties.keySet();
        for (Object key : keys) {
            log.info("Property: {}, value {}", key, properties.getProperty((String) key));
        }
    }

    private static Properties readProperties(String appMode) throws IOException {
        Properties properties = loadFromClasspath(appMode);

        String configfilename = System.getProperty(IAM_CONFIG_KEY);
        if(configfilename != null) {
            loadFromFile(properties, configfilename);
        }
        return properties;
    }

    private static Properties loadFromClasspath(String appMode) throws IOException {
        Properties properties = new Properties();
        String propertyfile = "securitytokenservice.%s.properties".formatted(appMode);
        log.info("Loading default properties from classpath: {}", propertyfile);
        InputStream is = AppConfig.class.getClassLoader().getResourceAsStream(propertyfile);
        if(is == null) {
            log.error("Error reading {} from classpath.", propertyfile);

        }
        properties.load(is);
        return properties;
    }

    private static void loadFromFile(Properties properties, String configfilename) throws IOException {
        File file = new File(configfilename);
        //System.out.println(file.getAbsolutePath());
        log.info("Overriding default properties with file {}", file.getAbsolutePath());
        if(file.exists()) {
            properties.load(new FileInputStream(file));
        } else {
            log.error("Config file {} specified by System property {} not found.", configfilename, IAM_CONFIG_KEY);
        }
    }


    public static String getFullTokenApplications() {
        return fullTokenApplications;
    }
    
    public static List<String> getPredefinedFullTokenApplications() {
        return preDefinedFullTokenApplications;
    }

    public static void setFullTokenApplications(String appLinks) {
        fullTokenApplications = appLinks;
        log.debug("updated fulltoken list: "+appLinks);
    }


//    public static void updateFullTokenApplicationList(URI userAdminServiceUri, String myAppTokenId, String userTokenId) {
//        log.trace("updateFullTokenApplicationList(URI userAdminServiceUri:{}, String myAppTokenId:{}, String userTokenId:{}", userAdminServiceUri, myAppTokenId, userTokenId);
//        if (userTokenId == null || userTokenId.length() < 2) {
//            // Ignore empty responses
//            return;
//        }        
//        if (shouldUpdate() || getFullTokenApplications() == null || getFullTokenApplications().length()==01) {
//            try {
//                ApplicationModelFacade.updateApplicationList();
//            	List<Application> applications = ApplicationModelFacade.getApplicationList();
//                String fTokenList = "";
//                for (Application application : applications) {
//                    if (application.isFullTokenApplication()) {
//                        fTokenList = fTokenList + application.getId() + ",";
//                        log.debug("is fulltoken {} appid:{}", application.isFullTokenApplication(), application.getId());
//                    }
//                }
//                if (fTokenList == null || fTokenList.length() < 3) {
//                    return;
//                }
//
//                setFullTokenApplications(fTokenList.substring(0, fTokenList.length() - 1));
//            } catch (Exception e) {
//                log.warn("updateFullTokenApplicationList - userTokenId: {}", userTokenId);
//            }
//        }
//       
//    }
//
//
//    public static boolean shouldUpdate() {
//        int max = 100;
//        return (5 >= ((int) (Math.random() * max)));  // update on 5 percent of requests
//    }

}
