package net.whydah.sts.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import net.whydah.sso.application.helpers.ApplicationCredentialHelper;
import net.whydah.sso.application.mappers.ApplicationCredentialMapper;
import net.whydah.sso.application.mappers.ApplicationTokenMapper;
import net.whydah.sso.application.types.Application;
import net.whydah.sso.application.types.ApplicationCredential;
import net.whydah.sso.application.types.ApplicationToken;
import net.whydah.sso.application.types.Tag;
import net.whydah.sso.config.ApplicationMode;
import net.whydah.sso.ddd.model.application.ApplicationName;
import net.whydah.sso.ddd.model.application.ApplicationTokenExpires;
import net.whydah.sso.ddd.model.application.ApplicationTokenID;
import net.whydah.sso.session.baseclasses.CryptoUtil;
import net.whydah.sso.session.baseclasses.ExchangeableKey;
import net.whydah.sso.user.types.UserCredential;
import net.whydah.sso.user.types.UserToken;
import net.whydah.sts.application.authentication.ApplicationAuthenticationUASClient;
import net.whydah.sts.config.AppConfig;
import net.whydah.sts.errorhandling.AppException;
import net.whydah.sts.errorhandling.AppExceptionCode;
import net.whydah.sts.errorhandling.AuthenticationFailedException;
import net.whydah.sts.user.AuthenticatedUserTokenRepository;
import net.whydah.sts.user.authentication.UserAuthenticator;
import org.glassfish.jersey.server.mvc.Viewable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static net.whydah.sso.util.LoggerUtil.first50;
import static net.whydah.sts.application.AuthenticatedApplicationTokenRepository.DEFAULT_APPLICATION_SESSION_EXTENSION_TIME_IN_SECONDS;

@Path("/")
public class ApplicationResource {
    private final static Logger log = LoggerFactory.getLogger(ApplicationResource.class);

    private final static Set<String> encryptionEnabledApplicationIDs = new HashSet<>(Arrays.asList("9999", "99999"));
    private final static ObjectMapper mapper = new ObjectMapper();


    private final static Map<String, Instant> appAuthProcessingMap = new ConcurrentHashMap<>();
    @Inject
    private AppConfig appConfig;

    @Inject
    private UserAuthenticator userAuthenticator;


    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response info() {
        if ("enabled".equals(appConfig.getProperty("testpage"))) {
            //log.debug("Showing test page");
            HashMap<String, Object> model = new HashMap<String, Object>();
            UserCredential testUserCredential = new UserCredential("whydah_user", "whydah_password");
            model.put("applicationcredential", ApplicationCredentialHelper.getDummyApplicationCredential());
            model.put("testUserCredential", testUserCredential.toXML());
            return Response.ok().entity(new Viewable("/testpage.html.ftl", model)).header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Methods", "GET, POST, DELETE, PUT").build();
        } else {
            //log.debug("Showing prod page");
            return Response.ok().entity(new Viewable("/html/prodwelcome.html")).header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Methods", "GET, POST, DELETE, PUT").build();
        }
    }


    @Path("/applicationtokentemplate")
    @GET
    @Produces(MediaType.APPLICATION_XML)
    public Response getApplicationTokenTemplate() {
        ApplicationToken template = new ApplicationToken();
        return Response.ok().entity(ApplicationTokenMapper.toXML(template)).header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Methods", "GET, POST, DELETE, PUT").build();
    }


    @Path("/applicationcredentialtemplate")
    @GET
    @Produces(MediaType.APPLICATION_XML)
    public Response getApplicationCredentialsTemplate() {
        ApplicationCredential template = ApplicationCredentialMapper.fromXml(ApplicationCredentialHelper.getDummyApplicationCredential());
        return Response.ok().entity(ApplicationCredentialMapper.toXML(template)).header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Methods", "GET, POST, DELETE, PUT").build();
    }

    @Path("/usercredentialtemplate")
    @GET
    @Produces(MediaType.APPLICATION_XML)
    public Response getUserCredentialsTemplate() {
        UserCredential template = new UserCredential("dummyUserName", "dummyUserpassword");
        return Response.ok().entity(template.toXML()).header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Methods", "GET, POST, DELETE, PUT").build();
    }

    /**
     * @throws AppException
     * @api {post} logon logonApplication
     * @apiName logon
     * @apiGroup Security Token Service (STS)
     * @apiDescription Log on my application and get the application specification
     * @apiParam {String} applicationcredential A label for this address.
     * @apiParamExample {xml} Request-Example:
     * &lt;?xml version="1.0" encoding="UTF-8" standalone="yes"?&gt;
     * &lt;applicationcredential&gt;
     * &lt;params&gt;
     * &lt;applicationID&gt;101&lt;/applicationID&gt;
     * &lt;applicationName&gt;Whydah-SystemTests&lt;/applicationName&gt;
     * &lt;applicationSecret&gt;55fhRM6nbKZ2wfC6RMmMuzXpk&lt;/applicationSecret&gt;
     * &lt;/params&gt;
     * &lt;/applicationcredential&gt;
     * @apiSuccessExample Success-Response:
     * HTTP/1.1 200 OK
     * &lt;applicationtoken&gt;
     * &lt;params&gt;
     * &lt;applicationtokenID&gt;1d58b70dc0fdc98b5cdce4745fb086c4&lt;/applicationtokenID&gt;
     * &lt;applicationid&gt;101&lt;/applicationid&gt;
     * &lt;applicationname&gt;Whydah-SystemTests&lt;/applicationname&gt;
     * &lt;expires&gt;1480931112185&lt;/expires&gt;
     * &lt;/params&gt;
     * &lt;Url type="application/xml" method="POST" template="https://whydahdev.cantara.no/tokenservice/user/1d58b70dc0fdc98b5cdce4745fb086c4/get_usertoken_by_usertokenid"/&gt;
     * &lt;/applicationtoken&gt;
     * @apiError 403/7000 Application is invalid.
     * @apiError 500/9999 A generic exception or an unexpected error
     * @apiErrorExample Error-Response:
     * HTTP/1.1 403 Forbidden
     * {
     * "status": 403,
     * "code": 7000,
     * "message": "Illegal Application.",
     * "link": "",
     * "developerMessage": "Application is invalid."
     * }
     */
    @Path("/logon")
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_XML)
    public Response logonApplication(@FormParam("applicationcredential") String appCredentialXml) throws AppException {
        log.trace("logonApplication with applicationcredential={}", first50(appCredentialXml));
        if (!verifyApplicationCredentialAgainstLocalAndUAS_UIB(appCredentialXml)) {
            log.warn("logonApplication - illegal applicationcredential applicationID:{} , returning FORBIDDEN :{}", appCredentialXml);
            //return Response.status(Response.Status.FORBIDDEN).header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Methods", "GET, POST, DELETE, PUT").build();
            throw AppExceptionCode.APP_ILLEGAL_7000;
        }
        try {
           
//            if(appCredentialXml.contains("2215")) {
//            	applicationToken.setBaseuri(appConfig.getProperty("myuri"));
//                applicationToken.setExpires(String.valueOf(new ApplicationTokenExpires(DEFAULT_APPLICATION_SESSION_EXTENSION_TIME_IN_SECONDS * 1000).getValue()));
//                AuthenticatedApplicationTokenRepository.addApplicationToken(applicationToken);
//                String applicationTokenXml = ApplicationTokenMapper.toXML(applicationToken);
//                log.trace("logonApplication returns applicationTokenXml={}", applicationTokenXml);
//                return Response.ok().entity(applicationTokenXml).header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Methods", "GET, POST, DELETE, PUT").build();
//            }
            
        	 ApplicationToken applicationToken = ApplicationTokenMapper.fromApplicationCredentialXML(appCredentialXml);
        	 if (applicationToken.getApplicationName() == null || applicationToken.getApplicationName().length() < 1) {
                 log.warn("Old Whydah ApplicationCredential received, please inform application owner to update the ApplicationCredential. ApplicationCredential:" + appCredentialXml);
             }
             applicationToken.setBaseuri(appConfig.getProperty("myuri"));
             applicationToken.setExpires(String.valueOf(new ApplicationTokenExpires(DEFAULT_APPLICATION_SESSION_EXTENSION_TIME_IN_SECONDS * 1000 * AuthenticatedApplicationTokenRepository.APP_TOKEN_MULTIPLIER).getValue()));

             Application app = ApplicationModelFacade.getApplication(applicationToken.getApplicationID());
             if (AuthenticatedApplicationTokenRepository.updateApplicationTokenWithDetailsFromApplication(applicationToken, app)) {
                 log.info("ApplicationToken updated with details from application-list. Application.name: {}, tags: {}", applicationToken.getApplicationName(), app.getTags());
             } else {
                 log.warn("ApplicationToken NOT updated with any details from application-list. Application.name: {}", applicationToken.getApplicationName());
             }

             AuthenticatedApplicationTokenRepository.addApplicationToken(applicationToken);

             String applicationTokenXml = ApplicationTokenMapper.toXML(applicationToken);
             log.trace("logonApplication returns applicationTokenXml={}", applicationTokenXml);
             return Response.ok().entity(applicationTokenXml).header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Methods", "GET, POST, DELETE, PUT").build();
        
            
            /*
            //only do update tags if we have UAS/UIB activated
            if( AuthenticatedApplicationTokenRepository.getUASApplicationToken()!=null && 
            		AuthenticatedApplicationTokenRepository.getUIBApplicationToken()!=null
            		) {
            	//add application token since UIB/UIB activated
            	AuthenticatedApplicationTokenRepository.addApplicationToken(applicationToken); 
            	//update tags for apps except UAS/UIB
            	if(!"2212, 2210".contains( applicationToken.getApplicationID())) {
            		log.info("UAS activated with app token {}", AuthenticatedApplicationTokenRepository.getUASApplicationToken());
            		log.info("UIB activated with app token {}", AuthenticatedApplicationTokenRepository.getUIBApplicationToken());
            		log.info("Preparing update tags for app token {} - app name {}", applicationToken.getApplicationTokenId() , applicationToken.getApplicationName());
            		applicationToken = updateWithTags(applicationToken); // TODO more than just updating tags could be done here as we are fetching full application xml
            		AuthenticatedApplicationTokenRepository.addApplicationToken(applicationToken); // add updated application-token with tags
            	}
            } else {
            	if("2212, 2210".contains( applicationToken.getApplicationID())) {
            		 AuthenticatedApplicationTokenRepository.addApplicationToken(applicationToken); 
            	} else {
            		//will fail because UAS/UIB has not activated just yet
            		log.warn("Logon called for app {} but UAS/UIB has not etablished yet.", applicationToken);
            		throw AppExceptionCode.MISC_INTERNAL_ERROR_PARAMS_9999.addMessageParams("Service is not ready for logon just yet.");
            	}
            }
            */
            
            
            
            
            
        } catch (Exception e) {
            log.error("Something went really wrong here", e);
        }
        throw AppExceptionCode.APP_ILLEGAL_7000;
    }

    /**
     * @throws AppException
     * @api {get} :applicationtokenid/validate validateApplicationTokenId
     * @apiName validateApplicationTokenId
     * @apiGroup Security Token Service (STS)
     * @apiDescription Validate application by an application sts id
     * @apiSuccessExample Success-Response:
     * HTTP/1.1 200 OK
     * {
     * "result": "true"
     * }
     * @apiError 403/7000 Application is invalid.
     * @apiError 500/9999 A generic exception or an unexpected error
     * @apiErrorExample Error-Response:
     * HTTP/1.1 403 Forbidden
     * {
     * "status": 403,
     * "code": 7000,
     * "message": "Illegal Application.",
     * "link": "",
     * "developerMessage": "Application is invalid."
     * }
     */
    @Path("{applicationtokenid}/validate")
    @GET
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response validateApplicationTokenId(@PathParam("applicationtokenid") String applicationtokenid) throws AppException {
        log.trace("validateApplicationTokenId - validate ApplicationTokenId:{}", applicationtokenid);
        if (AuthenticatedApplicationTokenRepository.verifyApplicationTokenId(applicationtokenid)) {
            log.trace("validateApplicationTokenId - ApplicationTokenId:{} for applicationname:{} is valid timeout in:{} seconds", applicationtokenid, AuthenticatedApplicationTokenRepository.getApplicationToken(applicationtokenid).getApplicationName(), Long.parseLong(AuthenticatedApplicationTokenRepository.getApplicationToken(applicationtokenid).getExpires()) - System.currentTimeMillis());
            return Response.ok("{\"result\": \"true\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Methods", "GET, POST, DELETE, PUT").build();
        } else {
            log.warn("validateApplicationTokenId - ApplicationTokenId:{}  is not valid", applicationtokenid);
            throw AppExceptionCode.APP_ILLEGAL_7000;
            //return Response.status(Response.Status.FORBIDDEN).header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Methods", "GET, POST, DELETE, PUT").build();
        }
    }

    /**
     * @throws AppException
     * @api {post} :applicationtokenid/renew_applicationtoken extendApplicationSession
     * @apiName extendApplicationSession
     * @apiGroup Security Token Service (STS)
     * @apiDescription Extend my application session
     * @apiSuccessExample Success-Response:
     * HTTP/1.1 200 OK
     * &lt;applicationtoken&gt;
     * &lt;params&gt;
     * &lt;applicationtokenID&gt;1d58b70dc0fdc98b5cdce4745fb086c4&lt;/applicationtokenID&gt;
     * &lt;applicationid&gt;101&lt;/applicationid&gt;
     * &lt;applicationname&gt;Whydah-SystemTests&lt;/applicationname&gt;
     * &lt;expires&gt;1480931112185&lt;/expires&gt;
     * &lt;/params&gt;
     * &lt;Url type="application/xml" method="POST" template="https://whydahdev.cantara.no/tokenservice/user/1d58b70dc0fdc98b5cdce4745fb086c4/get_usertoken_by_usertokenid"/&gt;
     * &lt;/applicationtoken&gt;
     * @apiError 403/7000 Application is invalid.
     * @apiError 500/9999 A generic exception or an unexpected error
     * @apiErrorExample Error-Response:
     * HTTP/1.1 403 Forbidden
     * {
     * "status": 403,
     * "code": 7000,
     * "message": "Illegal Application.",
     * "link": "",
     * "developerMessage": "Application is invalid."
     * }
     */
    @Path("{applicationtokenid}/renew_applicationtoken")
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response extendApplicationSession(@PathParam("applicationtokenid") String applicationtokenid) throws AppException {
        log.debug("renew session for ApplicationTokenId: {}", applicationtokenid);
        if (AuthenticatedApplicationTokenRepository.verifyApplicationTokenId(applicationtokenid)) {
            ExchangeableKey exchangeableKey = new ExchangeableKey(AuthenticatedApplicationTokenRepository.getApplicationCryptoKeyFromApplicationTokenID(applicationtokenid));
            ApplicationToken applicationToken = AuthenticatedApplicationTokenRepository.renewApplicationTokenId(applicationtokenid);
            log.info("ApplicationToken for {} extended, expires: {}", applicationToken.getApplicationName(), applicationToken.getExpiresFormatted());
            String applicationTokenXml = ApplicationTokenMapper.toXML(applicationToken);
            log.trace("extendApplicationSession returns applicationTokenXml={}", applicationTokenXml);

            if (handleCryptoKey(applicationToken)) {  // Disable this for normal appicationIDs until this is working as it should
                log.debug("Using cryptokey:{} for application: {} with applicationTokenId:{}", exchangeableKey, applicationToken.getApplicationID(), applicationToken.getApplicationTokenId());
                try {
                    CryptoUtil.setExchangeableKey(exchangeableKey);
                    String crtytoblock = CryptoUtil.encrypt(applicationTokenXml);
                    log.debug("Returning cryptoblock:{}", crtytoblock);
                    return Response.ok().entity(crtytoblock).header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Methods", "GET, POST, DELETE, PUT").build();
                } catch (Exception e) {
                    log.warn("Unable to encrypt massage, fallback to nonencrypted (for now)", e);
                }
            }
            // Fallback, return non-encrypted response
            return Response.ok().entity(applicationTokenXml).header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Methods", "GET, POST, DELETE, PUT").build();
        } else {
            log.warn("ApplicationTokenId={} not valid", applicationtokenid);
            throw AppExceptionCode.APP_ILLEGAL_7000;
            //return Response.status(Response.Status.FORBIDDEN).header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Methods", "GET, POST, DELETE, PUT").build();
        }
    }

    /**
     * @throws AppException
     * @api {get} :applicationtokenid/get_application_id getApplicationId
     * @apiName getApplicationIdFromApplicationTokenId
     * @apiGroup Security Token Service (STS)
     * @apiDescription Get my application id from an application sts id
     * @apiSuccessExample Success-Response:
     * HTTP/1.1 200 OK plain/text
     * 101
     * @apiError 403/7000 Application is invalid.
     * @apiError 500/9999 A generic exception or an unexpected error
     * @apiErrorExample Error-Response:
     * HTTP/1.1 403 Forbidden
     * {
     * "status": 403,
     * "code": 7000,
     * "message": "Illegal Application.",
     * "link": "",
     * "developerMessage": "Application is invalid."
     * }
     */
    @Path("{applicationtokenid}/get_application_id")
    @GET
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response getApplicationIdFromApplicationTokenId(@PathParam("applicationtokenid") String
                                                                   applicationtokenid) throws AppException {
        log.trace("verify ApplicationTokenId {}", applicationtokenid);
        ApplicationToken applicationToken = AuthenticatedApplicationTokenRepository.getApplicationToken(applicationtokenid);
        log.trace("Found applicationtoken:{}", first50(applicationToken));
        if (applicationToken == null || !ApplicationName.isValid(applicationToken.getApplicationID())) {
            log.warn("ApplicationTokenId {} is not valid", new ApplicationTokenID(applicationtokenid).getInput());
            //return Response.status(Response.Status.FORBIDDEN).header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Methods", "GET, POST, DELETE, PUT").build();
            throw AppExceptionCode.APP_ILLEGAL_7000;
        } else {
            log.debug("ApplicationTokenId for {} is valid", applicationToken.getApplicationID());
            return Response.ok(applicationToken.getApplicationID()).header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Methods", "GET, POST, DELETE, PUT").build();
        }
    }

    /**
     * @throws AppException
     * @api {get} :applicationtokenid/get_application_name getApplicationName
     * @apiName getApplicationNameFromApplicationTokenId
     * @apiGroup Security Token Service (STS)
     * @apiDescription Get my application name from an application sts id
     * @apiSuccessExample Success-Response:
     * HTTP/1.1 200 OK plain/text
     * Whydah-SystemTests
     * @apiError 403/7000 Application is invalid.
     * @apiError 500/9999 A generic exception or an unexpected error
     * @apiErrorExample Error-Response:
     * HTTP/1.1 403 Forbidden
     * {
     * "status": 403,
     * "code": 7000,
     * "message": "Illegal Application.",
     * "link": "",
     * "developerMessage": "Application is invalid."
     * }
     */
    @Path("{applicationtokenid}/get_application_name")
    @GET
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response getApplicationNameFromApplicationTokenId(@PathParam("applicationtokenid") String
                                                                     applicationtokenid) throws AppException {
        log.debug("verify ApplicationTokenId {}", applicationtokenid);
        ApplicationToken applicationToken = AuthenticatedApplicationTokenRepository.getApplicationToken(applicationtokenid);
        if (applicationToken == null || !ApplicationTokenID.isValid(applicationToken.getApplicationID())) {
            log.debug("ApplicationTokenId not valid");
            throw AppExceptionCode.APP_ILLEGAL_7000;
        } else {
            log.debug("ApplicationTokenId valid");
            return Response.ok(applicationToken.getApplicationName()).header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Methods", "GET, POST, DELETE, PUT").build();
        }
    }

    /**
     * @throws AppException
     * @api {get} :applicationtokenid/get_application_tags getApplicationTags
     * @apiName getApplicationTagsFromApplicationTokenId
     * @apiGroup Security Token Service (STS)
     * @apiDescription Get my application tags from an application sts id
     * @apiSuccessExample Success-Response:
     * HTTP/1.1 200 OK plain/text
     * Whydah-SystemTests
     * @apiError 403/7000 Application is invalid.
     * @apiError 500/9999 A generic exception or an unexpected error
     * @apiErrorExample Error-Response:
     * HTTP/1.1 403 Forbidden
     * {
     * "status": 403,
     * "code": 7000,
     * "message": "Illegal Application.",
     * "link": "",
     * "developerMessage": "Application is invalid."
     * }
     */
    @Path("{applicationtokenid}/get_application_tags")
    @GET
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response getApplicationTagsFromApplicationTokenId(
            @PathParam("applicationtokenid") String applicationtokenid,
            @QueryParam("tagname_prefix") String tagNamePrefixFilter,
            @QueryParam("tagname") String tagNameFilter,
            @QueryParam("tagvalue_prefix") String tagValuePrefixFilter,
            @QueryParam("tagvalue") String tagValueFilter) throws AppException {
        log.debug("verify ApplicationTokenId {}", applicationtokenid);
        ApplicationToken applicationToken = AuthenticatedApplicationTokenRepository.getApplicationToken(applicationtokenid);
        if (applicationToken == null || !ApplicationTokenID.isValid(applicationToken.getApplicationID())) {
            log.debug("ApplicationTokenId not valid");
            throw AppExceptionCode.APP_ILLEGAL_7000;
        } else {
            log.debug("ApplicationTokenId valid");
            List<Tag> tags = new ArrayList<>(applicationToken.getTags());
            if (tagNamePrefixFilter != null || tagNameFilter != null || tagValuePrefixFilter != null || tagValueFilter != null) {
                // tag filtering requested, allow all tags that passes any of the filters
                tags.removeIf(tag -> !(
                        (tagNamePrefixFilter != null && tag.getName().toLowerCase().startsWith(tagNamePrefixFilter.toLowerCase()))
                                || (tagNameFilter != null && tag.getName().equalsIgnoreCase(tagNameFilter))
                                || (tagValuePrefixFilter != null && tag.getValue().toLowerCase().startsWith(tagValuePrefixFilter.toLowerCase()))
                                || (tagValueFilter != null && tag.getValue().equalsIgnoreCase(tagValueFilter))
                ));
            }
            String tagsJson;
            try {
                tagsJson = mapper.writeValueAsString(tags);
            } catch (JsonProcessingException e) {
                log.error("", e);
                return Response.serverError().build();
            }
            return Response.ok(tagsJson, MediaType.TEXT_PLAIN_TYPE).header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Methods", "GET, POST, DELETE, PUT").build();
        }
    }

    /**
     * @throws AppException
     * @api {get} :applicationtokenid/get_application_key getApplicationKey
     * @apiName getApplicationCryptoKeyFromApplicationTokenId
     * @apiGroup Security Token Service (STS)
     * @apiDescription Get my application key from an application sts id
     * @apiSuccessExample Success-Response:
     * HTTP/1.1 200 OK plain/text
     * Whydah-SystemTests
     * @apiError 403/7000 Application is invalid.
     * @apiError 500/9999 A generic exception or an unexpected error
     * @apiErrorExample Error-Response:
     * HTTP/1.1 403 Forbidden
     * {
     * "status": 403,
     * "code": 7000,
     * "message": "Illegal Application.",
     * "link": "",
     * "developerMessage": "Application is invalid."
     * }
     */
    @Path("{applicationtokenid}/get_application_key")
    @GET
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response getApplicationCryptoKeyFromApplicationTokenId(@PathParam("applicationtokenid") String
                                                                          applicationtokenid) throws AppException {
        log.debug("verify ApplicationTokenId {}", applicationtokenid);
        ApplicationToken applicationToken = AuthenticatedApplicationTokenRepository.getApplicationToken(applicationtokenid);
        if (applicationToken != null && applicationToken.toString().length() > 10) {
            log.trace("ApplicationTokenId valid");
            String key = AuthenticatedApplicationTokenRepository.getApplicationCryptoKeyFromApplicationTokenID(applicationtokenid);
            if (key != null && key.length() > 10) {
                return Response.ok(key).header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Methods", "GET").build();
            }
            throw AppExceptionCode.APP_ILLEGAL_7000;  // No key for application
        } else {
            log.trace("ApplicationTokenId not valid");
            throw AppExceptionCode.APP_ILLEGAL_7000;
        }
    }

    private boolean verifyApplicationCredentialAgainstLocalAndUAS_UIB(String appCredential) {

        try {
            if (!ApplicationCredential.isValid(appCredential)) {
                log.trace("verifyApplicationCredentialAgainstLocalAndUAS_UIB - suspicious XML received, rejected.");
                return false;
            }
            if (ApplicationMode.getApplicationMode().equals(ApplicationMode.DEV)) {
                log.trace("verifyApplicationCredentialAgainstLocalAndUAS_UIB - running in DEV mode, auto accepted.");
                return true;
            }

            ApplicationCredential applicationCredential = ApplicationCredentialMapper.fromXml(appCredential);
            if (applicationCredential == null || applicationCredential.getApplicationID() == null || applicationCredential.getApplicationID().length() < 2) {
                log.warn("Application authentication failed. No or null applicationID");
                return false;

            }
            if (applicationCredential.getApplicationSecret() == null || applicationCredential.getApplicationSecret().length() < 2) {
                log.warn("verifyApplicationCredentialAgainstLocalAndUAS_UIB - verify appSecret failed. No or null applicationSecret");
                log.warn("Application authentication failed. No or null applicationSecret for applicationId={}", applicationCredential.getApplicationID());
                return false;
            }


            String expectedAppSecret = appConfig.getProperty(applicationCredential.getApplicationID());
            log.trace("verifyApplicationCredentialAgainstLocalAndUAS_UIB: appid={}, appSecret={}, expectedAppSecret={}", applicationCredential.getApplicationID(), applicationCredential.getApplicationSecret(), expectedAppSecret);


            // DDoS protection for application logon
            if (appAuthProcessingMap.size() > 200) {
                return false;
            }

            // Remove processing after 2 minutes
            if (appAuthProcessingMap.get(applicationCredential.getApplicationID()) != null) {
                Instant old = appAuthProcessingMap.get(applicationCredential.getApplicationID());
                if (Instant.now().minusMillis(2 * 60 * 1000).isBefore(old)) {
                    appAuthProcessingMap.remove(applicationCredential.getApplicationID());
                }
            }
            // Check non-local configured applications
            if ((expectedAppSecret == null || expectedAppSecret.length() < 2) && appAuthProcessingMap.get(applicationCredential.getApplicationID()) == null) {
                appAuthProcessingMap.put(applicationCredential.getApplicationID(), Instant.now());
                log.debug("No application secret in property file for applicationId={} - applicationName: {} - Trying UAS/UIB", applicationCredential.getApplicationID(), applicationCredential.getApplicationName());
                if (ApplicationAuthenticationUASClient.checkAppsecretFromUAS(applicationCredential)) {
                    log.info("Application authentication(no local) OK for appId:{}, applicationName: {} from UAS", applicationCredential.getApplicationID(), applicationCredential.getApplicationName());
                    appAuthProcessingMap.remove(applicationCredential.getApplicationID());
                    return true;
                } else {
                    log.warn("Application authentication failed. Incoming applicationSecret does not match applicationSecret in UIB");
                    appAuthProcessingMap.remove(applicationCredential.getApplicationID());
                    return false;
                }
            }
            if (!applicationCredential.getApplicationSecret().equalsIgnoreCase(expectedAppSecret) && appAuthProcessingMap.get(applicationCredential.getApplicationID()) == null) {

                //Huy: improve checking from the internal list instead of calling UIB/UAS
                if (ApplicationModelFacade.getApplicationList().size() > 0) {
                    //we can check from the internal list if available
                    Application app = ApplicationModelFacade.getApplication(applicationCredential.getApplicationID());
                    if (app != null) {
                        log.info("Check incoming applicationSecret from the internal app list");
                        if (app.getSecurity().getSecret().equalsIgnoreCase(applicationCredential.getApplicationSecret())) {
                            return true;
                        }
                    }
                }

                log.info("Incoming applicationSecret does not match applicationSecret from property file or internal application list. - Trying UAS/UIB");

                appAuthProcessingMap.put(applicationCredential.getApplicationID(), Instant.now());
                if (ApplicationAuthenticationUASClient.checkAppsecretFromUAS(applicationCredential)) {
                    log.info("Application authentication(local mismatch fallback) OK for appId:{}, applicationName: {} from UAS", applicationCredential.getApplicationID(), applicationCredential.getApplicationName());
                    appAuthProcessingMap.remove(applicationCredential.getApplicationID());
                    return true;
                } else {
                    log.warn("Application authentication failed. Incoming applicationSecret does not match applicationSecret in UIB");
                    appAuthProcessingMap.remove(applicationCredential.getApplicationID());
                    return false;
                }
            } else if (applicationCredential.getApplicationSecret().equalsIgnoreCase(expectedAppSecret)) {  // Everything is in order in UIB

                return true;
            } else {
                return false;
            }
        } catch (Exception e) {
            log.error("Error in verifyApplicationCredentialAgainstLocalAndUAS_UIB.", e);
            return false;
        }
    }

    private ApplicationToken updateWithTags(ApplicationToken applicationToken) {
        String user = appConfig.getProperty("whydah.adminuser.username");
        ApplicationToken stsApplicationToken = AuthenticatedApplicationTokenRepository.getSTSApplicationToken();
        //ApplicationToken uasApplicationToken = AuthenticatedApplicationTokenRepository.getUASApplicationToken();
        UserToken whydahUserAdminUserToken = AuthenticatedUserTokenRepository.getUserTokenByUserName(user, stsApplicationToken.getApplicationTokenId());
        if (whydahUserAdminUserToken == null || !whydahUserAdminUserToken.isValid()) {
            String password = appConfig.getProperty("whydah.adminuser.password");
            UserCredential userCredential = new UserCredential(user, password);
            try {
            	whydahUserAdminUserToken = userAuthenticator.logonUser(stsApplicationToken.getApplicationTokenId(), ApplicationTokenMapper.toXML(stsApplicationToken), userCredential.toXML());
            	if(whydahUserAdminUserToken!=null) {
                	return ApplicationAuthenticationUASClient.addApplicationTagsFromUAS(applicationToken, whydahUserAdminUserToken);
                }
            } catch(AuthenticationFailedException ex) {
            	ex.printStackTrace();
            }
        }
        return applicationToken;
    }


    private static boolean handleCryptoKey(ApplicationToken applicationToken) {
        ExchangeableKey lookupKey = AuthenticatedApplicationTokenRepository.getExchangeableKeyForApplicationToken(applicationToken);
        log.debug("Lookup cryptokey for Applicationid:{} for ApplicationTokenId:{} resulted in ExchangeableKey:{}", applicationToken.getApplicationID(), applicationToken.getApplicationTokenId(), lookupKey);
        if (lookupKey == null) {
            return false;
        }
        try {
            CryptoUtil.setExchangeableKey(lookupKey);
            // This test should be smarter and look at the application datastructure later
            //
            // ie if (ApplicationModelFacade.getApplication(applicationToken.getApplicationID()).getSecurity().isWhydahAdmin() ||
            //        ApplicationModelFacade.getApplication(applicationToken.getApplicationID()).getSecurity().isWhydahUASAccess())
            //
            if (encryptionEnabledApplicationIDs.contains(applicationToken.getApplicationID())) {  // Disable this for normal appicationIDs until this is working as it should
                log.debug("Using cryptokey:{} for ApplicationID: {} with applicationTokenId:{}", CryptoUtil.getActiveKey(), applicationToken.getApplicationID(), applicationToken.getApplicationTokenId());
                return true;
            }
        } catch (Exception e) {
            log.warn("Unable to use encryption", e);
        }
        return false;
    }
}
