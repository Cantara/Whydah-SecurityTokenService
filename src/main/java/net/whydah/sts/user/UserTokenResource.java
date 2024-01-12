package net.whydah.sts.user;

import com.fasterxml.jackson.databind.util.LRUMap;
import com.google.inject.Inject;
import com.hazelcast.config.Config;
import com.hazelcast.config.XmlConfigBuilder;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import net.whydah.sso.commands.adminapi.user.CommandSendSMSToUser;
import net.whydah.sso.config.ApplicationMode;
import net.whydah.sso.user.mappers.UserTokenMapper;
import net.whydah.sso.user.types.UserToken;
import net.whydah.sso.util.SSLTool;
import net.whydah.sts.application.AuthenticatedApplicationTokenRepository;
import net.whydah.sts.config.AppConfig;
import net.whydah.sts.config.DevModeHelper;
import net.whydah.sts.errorhandling.AppException;
import net.whydah.sts.errorhandling.AppExceptionCode;
import net.whydah.sts.errorhandling.AuthenticationFailedException;
import net.whydah.sts.threat.ThreatResource;
import net.whydah.sts.user.authentication.ActivePinRepository;
import net.whydah.sts.user.authentication.UserAuthenticator;
import net.whydah.sts.user.statistics.UserSessionObservedActivity;
import net.whydah.sts.util.DelayedSendSMSTask;
import org.glassfish.jersey.server.mvc.Viewable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.valuereporter.activity.ObservedActivity;
import org.valuereporter.client.MonitorReporter;

import java.io.FileNotFoundException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Path("/user")
public class UserTokenResource {
	private final static Logger log = LoggerFactory.getLogger(UserTokenResource.class);
	public static final String ACCESS_CONTROL_ALLOW_ORIGIN = "Access-Control-Allow-Origin";
	public static final String ACCESS_CONTROL_ALLOW_METHODS = "Access-Control-Allow-Methods";
	public static final String GET_POST_DELETE_PUT = "GET, POST, DELETE, PUT";
	public static final String USER_AUTHENTICATION_FAILED = "User authentication failed";
	public static final String APPLICATION_AUTHENTICATION_NOT_VALID = "Application authentication not valid.";
	public static final String ILLEGAL_APPLICATION_FOR_THIS_SERVICE = "Illegal application for this service";

	private static Map userticketmap = new HashMap();
	//private static Map applicationtokenidmap = new HashMap();
	private static final LRUMap<String, String> failinguserTokenMap = new LRUMap<String, String>(20, 20);
	private static String failingUserTokenId = null;
	private static java.util.Random generator = new SecureRandom();

	private static final String SMS_GW_SERVICE_URL;
	private static final String SMS_GW_SERVICE_ACCOUNT;
	private static final String SMS_GW_USERNAME;
	private static final String SMS_GW_PASSWORD;
	private static final String SMS_GW_QUERY_PARAM;

	public static final String GRIDPREFIX = "gridprefix";


	static {

		AppConfig appConfig = new AppConfig();

		// Property-overwrite of SSL verification to support weak ssl certificates
		if ("disabled".equalsIgnoreCase(appConfig.getProperty("sslverification"))) {
			SSLTool.disableCertificateValidation();

		}
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
		hazelcastConfig.setProperty("hazelcast.logging.type", "slf4j");
		//hazelcastConfig.getGroupConfig().setName("STS_HAZELCAST");
		HazelcastInstance hazelcastInstance;
		try {
			hazelcastInstance = Hazelcast.newHazelcastInstance(hazelcastConfig);
		} catch(Exception ex) {
			hazelcastInstance = Hazelcast.newHazelcastInstance();
		}
		userticketmap = hazelcastInstance.getMap(appConfig.getProperty(GRIDPREFIX) + "userticket_map");
		log.info("Connectiong to map {}", appConfig.getProperty(GRIDPREFIX) + "userticket_map");
		//applicationtokenidmap = hazelcastInstance.getMap(appConfig.getProperty(GRIDPREFIX) + "applicationtokenid_map");
		//log.info("Connectiong to map {}", appConfig.getProperty(GRIDPREFIX) + "applicationtokenid_map");

		SMS_GW_SERVICE_URL = appConfig.getProperty("smsgw.serviceurl");  //URL https://smsgw.somewhere/../sendMessages/
		SMS_GW_SERVICE_ACCOUNT = appConfig.getProperty("smsgw.serviceaccount");  //serviceAccount
		SMS_GW_USERNAME = appConfig.getProperty("smsgw.username");  //smsserviceusername
		SMS_GW_PASSWORD = appConfig.getProperty("smsgw.password");  //msservicepassword
		SMS_GW_QUERY_PARAM = appConfig.getProperty("smsgw.queryparams");   //serviceId=serviceAccount&me...ssword=smsservicepassword
	}


	private UserAuthenticator userAuthenticator;

	@Context
	UriInfo uriInfo;


	@Inject
	public UserTokenResource(UserAuthenticator userAuthenticator) {
		this.userAuthenticator = userAuthenticator;
	}

	@Inject
	private AppConfig appConfig;

	@Path("/usertoken_template")
	@GET
	@Produces(MediaType.APPLICATION_XML)
	public Response getUserTokenTemplate() {
		return Response.ok(new Viewable("/usertoken.ftl", new UserToken())).build();
	}

	/**
	 * @throws AppException
	 * @api {post} :applicationtokenid/usertoken getUserToken
	 * @apiName getUserToken
	 * @apiGroup Security Token Service (STS)
	 * @apiDescription Login in user by his/her credentials and acquire User Token XML data
	 * @apiParam {String} apptoken Application Token XML.
	 * @apiParamExample {xml} Request-Example:
	 * &lt;applicationtoken&gt;
	 * &lt;params&gt;
	 * &lt;applicationtokenID&gt;1d58b70dc0fdc98b5cdce4745fb086c4&lt;/applicationtokenID&gt;
	 * &lt;applicationid&gt;101&lt;/applicationid&gt;
	 * &lt;applicationname&gt;Whydah-SystemTests&lt;/applicationname&gt;
	 * &lt;expires&gt;1480931112185&lt;/expires&gt;
	 * &lt;/params&gt;
	 * &lt;Url type="application/xml" method="POST" template="https://whydahdev.cantara.no/tokenservice/user/1d58b70dc0fdc98b5cdce4745fb086c4/get_usertoken_by_usertokenid"/&gt;
	 * &lt;/applicationtoken&gt;
	 * @apiParam {String} usercredential User Credential XML
	 * @apiParamExample {xml} Request-Example:
	 * &lt;?xml version="1.0" encoding="UTF-8" standalone="yes"?&gt;
	 * &lt;usercredential&gt;
	 * &lt;params&gt;
	 * &lt;username&gt;YOUR_USER_NAME&lt;/username&gt;
	 * &lt;password&gt;YOUR_PASSWORD&lt;/password&gt;
	 * &lt;/params&gt;
	 * &lt;/usercredential&gt;
	 * @apiSuccessExample Success-Response:
	 * HTTP/1.1 200 OK
	 * &lt;?xml version="1.0" encoding="UTF-8" standalone="yes"?&gt;
	 * &lt;usertoken xmlns:ns2="http://www.w3.org/1999/xhtml" id="16987d1a-f305-4a98-a3dc-2ce9a97e8424"&gt;
	 * &lt;uid&gt;systest&lt;/uid&gt;
	 * &lt;timestamp&gt;1480935597694&lt;/timestamp&gt;
	 * &lt;lifespan&gt;1209600000&lt;/lifespan&gt;
	 * &lt;issuer&gt;https://whydahdev.cantara.no/tokenservice/user/0700445a583e014102affd92c8d896a0/validate_usertokenid/16987d1a-f305-4a98-a3dc-2ce9a97e8424&lt;/issuer&gt;
	 * &lt;securitylevel&gt;1&lt;/securitylevel&gt;
	 * &lt;DEFCON&gt;&lt;/DEFCON&gt;
	 * &lt;username&gt;systest&lt;/username&gt;
	 * &lt;firstname&gt;SystemTestUser&lt;/firstname&gt;
	 * &lt;lastname&gt;UserAdminWebApp&lt;/lastname&gt;
	 * &lt;cellphone&gt;87654321&lt;/cellphone&gt;
	 * &lt;email&gt;whydahadmin@getwhydah.com&lt;/email&gt;
	 * &lt;personref&gt;42&lt;/personref&gt;
	 * &lt;application ID="101"&gt;
	 * &lt;applicationName&gt;ACSResource&lt;/applicationName&gt;
	 * &lt;organizationName&gt;Opplysningen 1881&lt;/organizationName&gt;
	 * &lt;role name="INNDATA" value="MY_ADDRESS_JSON"/&gt;
	 * &lt;/application&gt;
	 * &lt;ns2:link type="application/xml" href="https://whydahdev.cantara.no/tokenservice/user/0700445a583e014102affd92c8d896a0/validate_usertokenid/16987d1a-f305-4a98-a3dc-2ce9a97e8424" rel="self"/&gt;
	 * &lt;hash type="MD5"&gt;57755c9efa6337dc9739fe5cb719a9b4&lt;/hash&gt;
	 * &lt;/usertoken&gt;
	 * @apiError 403/7000 Application is invalid.
	 * @apiError 403/6000 Authentication failed.
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
	@Path("/{applicationtokenid}/usertoken")
	@POST
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	@Produces(MediaType.APPLICATION_XML)
	public Response getUserToken(@PathParam("applicationtokenid") String applicationtokenid,
			@FormParam("apptoken") String appTokenXml,
			@FormParam("usercredential") String userCredentialXml) throws AppException {
		if (ApplicationMode.getApplicationMode().equals(ApplicationMode.DEV)) {
			return DevModeHelper.return_DEV_MODE_ExampleUserToken(1);
		}
		if (!UserTokenFactory.verifyApplicationToken(applicationtokenid, appTokenXml)) {
			log.warn("getUserToken - attempt to access from invalid application. applicationtokenid={}", applicationtokenid);
			//return Response.status(Response.Status.FORBIDDEN).entity(APPLICATION_AUTHENTICATION_NOT_VALID).header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
			throw AppExceptionCode.APP_ILLEGAL_7000;
		}
		try {
			UserToken userToken = userAuthenticator.logonUser(applicationtokenid, appTokenXml, userCredentialXml);
			return createUserTokenResponse(applicationtokenid, userToken);

		} catch (AuthenticationFailedException ae) {
			log.warn("getUserToken - User authentication failed");
			//return Response.status(Response.Status.FORBIDDEN).entity(USER_AUTHENTICATION_FAILED).header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
			throw AppExceptionCode.USER_AUTHENTICATION_FAILED_6000.setDeveloperMessage(ae.getMessage());
		}
	}

	/**
	 * @throws AppException
	 * @api {post} :applicationtokenid/:userticket/usertoken getUserTokenAndStoreUserTicket
	 * @apiName getUserTokenAndStoreUserTicket
	 * @apiGroup Security Token Service (STS)
	 * @apiDescription Login in user by his/her user credentials and register its ticket in the ticket-map for session hand-over
	 * @apiParam {String} apptoken Application Token XML.
	 * @apiParamExample {xml} Request-Example:
	 * &lt;applicationtoken&gt;
	 * &lt;params&gt;
	 * &lt;applicationtokenID&gt;1d58b70dc0fdc98b5cdce4745fb086c4&lt;/applicationtokenID&gt;
	 * &lt;applicationid&gt;101&lt;/applicationid&gt;
	 * &lt;applicationname&gt;Whydah-SystemTests&lt;/applicationname&gt;
	 * &lt;expires&gt;1480931112185&lt;/expires&gt;
	 * &lt;/params&gt;
	 * &lt;Url type="application/xml" method="POST" template="https://whydahdev.cantara.no/tokenservice/user/1d58b70dc0fdc98b5cdce4745fb086c4/get_usertoken_by_usertokenid"/&gt;
	 * &lt;/applicationtoken&gt;
	 * @apiParam {String} usercredential User Credential XML
	 * @apiParamExample {xml} Request-Example:
	 * &lt;?xml version="1.0" encoding="UTF-8" standalone="yes"?&gt;
	 * &lt;usercredential&gt;
	 * &lt;params&gt;
	 * &lt;username&gt;YOUR_USER_NAME&lt;/username&gt;
	 * &lt;password&gt;YOUR_PASSWORD&lt;/password&gt;
	 * &lt;/params&gt;
	 * &lt;/usercredential&gt;
	 * @apiSuccessExample Success-Response:
	 * HTTP/1.1 200 OK
	 * &lt;?xml version="1.0" encoding="UTF-8" standalone="yes"?&gt;
	 * &lt;usertoken xmlns:ns2="http://www.w3.org/1999/xhtml" id="16987d1a-f305-4a98-a3dc-2ce9a97e8424"&gt;
	 * &lt;uid&gt;systest&lt;/uid&gt;
	 * &lt;timestamp&gt;1480935597694&lt;/timestamp&gt;
	 * &lt;lifespan&gt;1209600000&lt;/lifespan&gt;
	 * &lt;issuer&gt;https://whydahdev.cantara.no/tokenservice/user/0700445a583e014102affd92c8d896a0/validate_usertokenid/16987d1a-f305-4a98-a3dc-2ce9a97e8424&lt;/issuer&gt;
	 * &lt;securitylevel&gt;1&lt;/securitylevel&gt;
	 * &lt;DEFCON&gt;&lt;/DEFCON&gt;
	 * &lt;username&gt;systest&lt;/username&gt;
	 * &lt;firstname&gt;SystemTestUser&lt;/firstname&gt;
	 * &lt;lastname&gt;UserAdminWebApp&lt;/lastname&gt;
	 * &lt;cellphone&gt;87654321&lt;/cellphone&gt;
	 * &lt;email&gt;whydahadmin@getwhydah.com&lt;/email&gt;
	 * &lt;personref&gt;42&lt;/personref&gt;
	 * &lt;application ID="101"&gt;
	 * &lt;applicationName&gt;ACSResource&lt;/applicationName&gt;
	 * &lt;organizationName&gt;Opplysningen 1881&lt;/organizationName&gt;
	 * &lt;role name="INNDATA" value="MY_ADDRESS_JSON"/&gt;
	 * &lt;/application&gt;
	 * &lt;ns2:link type="application/xml" href="https://whydahdev.cantara.no/tokenservice/user/0700445a583e014102affd92c8d896a0/validate_usertokenid/16987d1a-f305-4a98-a3dc-2ce9a97e8424" rel="self"/&gt;
	 * &lt;hash type="MD5"&gt;57755c9efa6337dc9739fe5cb719a9b4&lt;/hash&gt;
	 * &lt;/usertoken&gt;
	 * @apiError 403/7000 Application is invalid.
	 * @apiError 403/6000 Authentication failed.
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
	@Path("/{applicationtokenid}/{userticket}/usertoken")
	@POST
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	@Produces(MediaType.APPLICATION_XML)
	public Response getUserTokenAndStoreUserTicket(@PathParam("applicationtokenid") String applicationtokenid,
			@PathParam("userticket") String userticket,
			@FormParam("apptoken") String appTokenXml,
			@FormParam("usercredential") String userCredentialXml) throws AppException {
		if (ApplicationMode.getApplicationMode().equals(ApplicationMode.DEV)) {
			return DevModeHelper.return_DEV_MODE_ExampleUserToken(1);
		}

		if (!UserTokenFactory.verifyApplicationToken(applicationtokenid, appTokenXml)) {
			log.warn("getUserTokenAndStoreUserTicket - attempt to access from invalid application. applicationtokenid={}", applicationtokenid);
			//return Response.status(Response.Status.FORBIDDEN).entity("Application authentication not valid.").header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
			throw AppExceptionCode.APP_ILLEGAL_7000;
		}
		try {
			UserToken userToken = null;
			if (!userticketmap.containsKey(userticket)) {
				userToken = userAuthenticator.logonUser(applicationtokenid, appTokenXml, userCredentialXml);
				// Add the user to the ticket-map with the ticket given from the caller
				userticketmap.put(userticket, userToken.getUserTokenId());
			} else {
				userToken = AuthenticatedUserTokenRepository.getUserToken(userticketmap.get(userticket).toString(), applicationtokenid);
				//userToken = refreshAndGetThisUser(userticketmap.get(userticket).toString(), applicationtokenid);
			}
			return createUserTokenResponse(applicationtokenid, userToken);
		} catch (AuthenticationFailedException ae) {
			log.warn("getUserTokenAndStoreUserTicket - User authentication failed");
			//return Response.status(Response.Status.FORBIDDEN).entity(USER_AUTHENTICATION_FAILED).header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
			throw AppExceptionCode.USER_AUTHENTICATION_FAILED_6000.setDeveloperMessage(ae.getMessage());
		}
	}

	/**
	 * @throws AppException
	 * @api {post} :applicationtokenid/validate_usertoken validateUserTokenXML
	 * @apiName validateUserTokenXML
	 * @apiGroup Security Token Service (STS)
	 * @apiDescription Verify whether a usertoken and a user session is still valid. This is usually used for application re-entries and before granting a critical process like monetary transactions
	 * @apiParam {String} usertoken User Token XML.
	 * @apiParamExample {xml} Request-Example:
	 * &lt;?xml version="1.0" encoding="UTF-8" standalone="yes"?&gt;
	 * &lt;usertoken xmlns:ns2="http://www.w3.org/1999/xhtml" id="16987d1a-f305-4a98-a3dc-2ce9a97e8424"&gt;
	 * &lt;uid&gt;systest&lt;/uid&gt;
	 * &lt;timestamp&gt;1480935597694&lt;/timestamp&gt;
	 * &lt;lifespan&gt;1209600000&lt;/lifespan&gt;
	 * &lt;issuer&gt;https://whydahdev.cantara.no/tokenservice/user/0700445a583e014102affd92c8d896a0/validate_usertokenid/16987d1a-f305-4a98-a3dc-2ce9a97e8424&lt;/issuer&gt;
	 * &lt;securitylevel&gt;1&lt;/securitylevel&gt;
	 * &lt;DEFCON&gt;&lt;/DEFCON&gt;
	 * &lt;username&gt;systest&lt;/username&gt;
	 * &lt;firstname&gt;SystemTestUser&lt;/firstname&gt;
	 * &lt;lastname&gt;UserAdminWebApp&lt;/lastname&gt;
	 * &lt;cellphone&gt;87654321&lt;/cellphone&gt;
	 * &lt;email&gt;whydahadmin@getwhydah.com&lt;/email&gt;
	 * &lt;personref&gt;42&lt;/personref&gt;
	 * &lt;application ID="101"&gt;
	 * &lt;applicationName&gt;ACSResource&lt;/applicationName&gt;
	 * &lt;organizationName&gt;Opplysningen 1881&lt;/organizationName&gt;
	 * &lt;role name="INNDATA" value="MY_ADDRESS_JSON"/&gt;
	 * &lt;/application&gt;
	 * &lt;ns2:link type="application/xml" href="https://whydahdev.cantara.no/tokenservice/user/0700445a583e014102affd92c8d896a0/validate_usertokenid/16987d1a-f305-4a98-a3dc-2ce9a97e8424" rel="self"/&gt;
	 * &lt;hash type="MD5"&gt;57755c9efa6337dc9739fe5cb719a9b4&lt;/hash&gt;
	 * &lt;/usertoken&gt;
	 * @apiSuccessExample Success-Response:
	 * HTTP/1.1 200 OK
	 * {
	 * "result": "true"
	 * }
	 * @apiError 403/7000 Application is invalid.
	 * @apiError 401/6001 UserToken is invalid.
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
	@Path("/{applicationtokenid}/validate_usertoken")
	@POST
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response validateUserTokenXML(@PathParam("applicationtokenid") String applicationtokenid, @FormParam("usertoken") String userTokenXml) throws AppException {
		if (!AuthenticatedApplicationTokenRepository.verifyApplicationTokenId(applicationtokenid)) {
			log.warn("validateUserTokenXML - attempt to access from invalid application. applicationtokenid={}", applicationtokenid);
			//return Response.status(Response.Status.FORBIDDEN).entity("Application authentication not valid.").header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
			throw AppExceptionCode.APP_ILLEGAL_7000;
		}
		UserToken userToken = UserTokenMapper.fromUserTokenXml(userTokenXml);
		if (AuthenticatedUserTokenRepository.verifyUserToken(userToken, applicationtokenid)) {
			return Response.ok("{\"result\": \"true\"}").header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
		}
		log.warn("validateUserTokenXML failed for usertoken {}", userTokenXml);
		//return Response.status(Response.Status.UNAUTHORIZED).header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
		throw AppExceptionCode.USER_VALIDATE_FAILED_6001.setDeveloperMessage("validateUserTokenXML failed for usertoken " + userTokenXml);
	}

	/**
	 * @throws AppException
	 * @api {get} :applicationtokenid/validate_usertokenid/:usertokenid validateUserTokenID
	 * @apiName validateUserTokenID
	 * @apiGroup Security Token Service (STS)
	 * @apiDescription Verify if a user session is still valid. This is usually used for application re-entries and before granting a critical process like monetary transactions
	 * @apiSuccessExample Success-Response:
	 * HTTP/1.1 200 OK
	 * {
	 * "result": "true"
	 * }
	 * @apiError 403/7000 Application is invalid.
	 * @apiError 401/6001 UserToken is invalid.
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
	@Path("/{applicationtokenid}/validate_usertokenid/{usertokenid}")
	@GET
	public Response validateUserTokenID(@PathParam("applicationtokenid") String applicationtokenid, @PathParam("usertokenid") String usertokenid) throws AppException {
		if (!AuthenticatedApplicationTokenRepository.verifyApplicationTokenId(applicationtokenid)) {
			log.warn("validateUserTokenXML - attempt to access from invalid application. applicationtokenid={}", applicationtokenid);
			//return Response.status(Response.Status.FORBIDDEN).entity("Application authentication not valid.").header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
			throw AppExceptionCode.APP_ILLEGAL_7000;
		}
		if (AuthenticatedUserTokenRepository.getUserToken(usertokenid, applicationtokenid) != null) {
			log.trace("Verified {}", usertokenid);
			return Response.ok("{\"result\": \"true\"}").header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
		}
		log.warn("Usertoken not ok: {}", usertokenid);
		//return Response.status(Response.Status.UNAUTHORIZED).header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
		throw AppExceptionCode.USER_VALIDATE_FAILED_6001.setDeveloperMessage("validateUserTokenID failed for usertokenid " + usertokenid);
	}

	/**
	 * @throws AppException
	 * @api {post} :applicationtokenid/create_userticket_by_usertokenid createUserTicketByUserTokenId
	 * @apiName createUserTicketByUserTokenId
	 * @apiGroup Security Token Service (STS)
	 * @apiDescription This is used to create a userticket for a user to transfer a session between whydah SSO apps
	 * @apiParam {String} apptoken Application Token XML.
	 * @apiParamExample {xml} Request-Example:
	 * &lt;applicationtoken&gt;
	 * &lt;params&gt;
	 * &lt;applicationtokenID&gt;1d58b70dc0fdc98b5cdce4745fb086c4&lt;/applicationtokenID&gt;
	 * &lt;applicationid&gt;101&lt;/applicationid&gt;
	 * &lt;applicationname&gt;Whydah-SystemTests&lt;/applicationname&gt;
	 * &lt;expires&gt;1480931112185&lt;/expires&gt;
	 * &lt;/params&gt;
	 * &lt;Url type="application/xml" method="POST" template="https://whydahdev.cantara.no/tokenservice/user/1d58b70dc0fdc98b5cdce4745fb086c4/get_usertoken_by_usertokenid"/&gt;
	 * &lt;/applicationtoken&gt;
	 * @apiParam {String} userticket user session handover ticket
	 * @apiParam {String} usertokenid user session id
	 * @apiSuccessExample Success-Response:
	 * HTTP/1.1 200 OK
	 * &lt;?xml version="1.0" encoding="UTF-8" standalone="yes"?&gt;
	 * &lt;usertoken xmlns:ns2="http://www.w3.org/1999/xhtml" id="16987d1a-f305-4a98-a3dc-2ce9a97e8424"&gt;
	 * &lt;uid&gt;systest&lt;/uid&gt;
	 * &lt;timestamp&gt;1480935597694&lt;/timestamp&gt;
	 * &lt;lifespan&gt;1209600000&lt;/lifespan&gt;
	 * &lt;issuer&gt;https://whydahdev.cantara.no/tokenservice/user/0700445a583e014102affd92c8d896a0/validate_usertokenid/16987d1a-f305-4a98-a3dc-2ce9a97e8424&lt;/issuer&gt;
	 * &lt;securitylevel&gt;1&lt;/securitylevel&gt;
	 * &lt;DEFCON&gt;&lt;/DEFCON&gt;
	 * &lt;username&gt;systest&lt;/username&gt;
	 * &lt;firstname&gt;SystemTestUser&lt;/firstname&gt;
	 * &lt;lastname&gt;UserAdminWebApp&lt;/lastname&gt;
	 * &lt;cellphone&gt;87654321&lt;/cellphone&gt;
	 * &lt;email&gt;whydahadmin@getwhydah.com&lt;/email&gt;
	 * &lt;personref&gt;42&lt;/personref&gt;
	 * &lt;application ID="101"&gt;
	 * &lt;applicationName&gt;ACSResource&lt;/applicationName&gt;
	 * &lt;organizationName&gt;Opplysningen 1881&lt;/organizationName&gt;
	 * &lt;role name="INNDATA" value="MY_ADDRESS_JSON"/&gt;
	 * &lt;/application&gt;
	 * &lt;ns2:link type="application/xml" href="https://whydahdev.cantara.no/tokenservice/user/0700445a583e014102affd92c8d896a0/validate_usertokenid/16987d1a-f305-4a98-a3dc-2ce9a97e8424" rel="self"/&gt;
	 * &lt;hash type="MD5"&gt;57755c9efa6337dc9739fe5cb719a9b4&lt;/hash&gt;
	 * &lt;/usertoken&gt;
	 * @apiError 403/7000 Application is invalid.
	 * @apiError 406/6002 Attempt to access with non acceptable usertokenid.
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
	@Path("/{applicationtokenid}/create_userticket_by_usertokenid")
	@POST
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response createUserTicketByUserTokenId(@PathParam("applicationtokenid") String applicationtokenid,
			@FormParam("apptoken") String appTokenXml,
			@FormParam("userticket") String userticket,
			@FormParam("usertokenid") String userTokenId) throws AppException {
		log.trace("createUserTicketByUserTokenId: applicationtokenid={}, userticket={}, usertokenid={}, appTokenXml={}", applicationtokenid, userticket, userTokenId, appTokenXml);


		if (!UserTokenFactory.verifyApplicationToken(applicationtokenid, appTokenXml)) {
			log.warn("createUserTicketByUserTokenId - attempt to access from invalid application. applicationtokenid={}", applicationtokenid);
			//return Response.status(Response.Status.FORBIDDEN).entity(ILLEGAL_APPLICATION_FOR_THIS_SERVICE).header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
			throw AppExceptionCode.APP_ILLEGAL_7000;
		}
		final UserToken userToken = AuthenticatedUserTokenRepository.getUserToken(userTokenId, applicationtokenid);
		//final UserToken userToken = refreshAndGetThisUser(userTokenId, applicationtokenid);
		if (userToken == null) {
			log.warn("createUserTicketByUserTokenId - attempt to access with non acceptable usertokenid {}", userTokenId);
			//return Response.status(Response.Status.NOT_ACCEPTABLE).build();
			throw AppExceptionCode.USER_INVALID_USERTOKENID_6002.setDeveloperMessage("getUserTokenByUserTokenId - attempt to access with non acceptable usertokenid=" + userTokenId);
		} else {
			userticketmap.put(userticket, userToken.getUserTokenId());
			log.trace("createUserTicketByUserTokenId OK. Response={}", userToken.toString());
			return createUserTokenResponse(applicationtokenid, userToken);
		}

	}

	@Path("/{applicationtokenid}/get_userticket_by_usertokenid")
	@POST
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response getUserTicketByUserTokenId(@PathParam("applicationtokenid") String applicationtokenid,
			@FormParam("usertokenid") String userTokenId) throws AppException {

		String userticket = UUID.randomUUID().toString();

		log.trace("createUserTicketByUserTokenId: applicationtokenid={}, userticket={}, usertokenid={}", applicationtokenid, userticket, userTokenId);


		if (!UserTokenFactory.verifyApplicationToken(applicationtokenid, "HIDDEN")) {
			log.warn("createUserTicketByUserTokenId - attempt to access from invalid application. applicationtokenid={}", applicationtokenid);
			//return Response.status(Response.Status.FORBIDDEN).entity(ILLEGAL_APPLICATION_FOR_THIS_SERVICE).header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
			throw AppExceptionCode.APP_ILLEGAL_7000;
		}
		final UserToken userToken = AuthenticatedUserTokenRepository.getUserToken(userTokenId, applicationtokenid);
		//final UserToken userToken = refreshAndGetThisUser(userTokenId, applicationtokenid);
		if (userToken == null) {
			log.warn("createUserTicketByUserTokenId - attempt to access with non acceptable usertokenid {}", userTokenId);
			//return Response.status(Response.Status.NOT_ACCEPTABLE).build();
			throw AppExceptionCode.USER_INVALID_USERTOKENID_6002.setDeveloperMessage("getUserTokenByUserTokenId - attempt to access with non acceptable usertokenid=" + userTokenId);
		} else {
			userticketmap.put(userticket, userToken.getUserTokenId());
			log.trace("createUserTicketByUserTokenId OK. Response={}", userToken.toString());
			return Response.ok(userticket).header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
		}

	}

	/**
	 * @throws AppException
	 * @api {post} :applicationtokenid/get_usertoken_by_usertokenid getUserTokenByUserTokenId
	 * @apiName getUserTokenByUserTokenId
	 * @apiGroup Security Token Service (STS)
	 * @apiDescription Acquire User Token XML data by usertokenid
	 * @apiParam {String} apptoken Application Token XML.
	 * @apiParamExample {xml} Request-Example:
	 * &lt;applicationtoken&gt;
	 * &lt;params&gt;
	 * &lt;applicationtokenID&gt;1d58b70dc0fdc98b5cdce4745fb086c4&lt;/applicationtokenID&gt;
	 * &lt;applicationid&gt;101&lt;/applicationid&gt;
	 * &lt;applicationname&gt;Whydah-SystemTests&lt;/applicationname&gt;
	 * &lt;expires&gt;1480931112185&lt;/expires&gt;
	 * &lt;/params&gt;
	 * &lt;Url type="application/xml" method="POST" template="https://whydahdev.cantara.no/tokenservice/user/1d58b70dc0fdc98b5cdce4745fb086c4/get_usertoken_by_usertokenid"/&gt;
	 * &lt;/applicationtoken&gt;
	 * @apiParam {String} usertokenid The user session id
	 * @apiSuccessExample Success-Response:
	 * HTTP/1.1 200 OK
	 * &lt;?xml version="1.0" encoding="UTF-8" standalone="yes"?&gt;
	 * &lt;usertoken xmlns:ns2="http://www.w3.org/1999/xhtml" id="16987d1a-f305-4a98-a3dc-2ce9a97e8424"&gt;
	 * &lt;uid&gt;systest&lt;/uid&gt;
	 * &lt;timestamp&gt;1480935597694&lt;/timestamp&gt;
	 * &lt;lifespan&gt;1209600000&lt;/lifespan&gt;
	 * &lt;issuer&gt;https://whydahdev.cantara.no/tokenservice/user/0700445a583e014102affd92c8d896a0/validate_usertokenid/16987d1a-f305-4a98-a3dc-2ce9a97e8424&lt;/issuer&gt;
	 * &lt;securitylevel&gt;1&lt;/securitylevel&gt;
	 * &lt;DEFCON&gt;&lt;/DEFCON&gt;
	 * &lt;username&gt;systest&lt;/username&gt;
	 * &lt;firstname&gt;SystemTestUser&lt;/firstname&gt;
	 * &lt;lastname&gt;UserAdminWebApp&lt;/lastname&gt;
	 * &lt;cellphone&gt;87654321&lt;/cellphone&gt;
	 * &lt;email&gt;whydahadmin@getwhydah.com&lt;/email&gt;
	 * &lt;personref&gt;42&lt;/personref&gt;
	 * &lt;application ID="101"&gt;
	 * &lt;applicationName&gt;ACSResource&lt;/applicationName&gt;
	 * &lt;organizationName&gt;Opplysningen 1881&lt;/organizationName&gt;
	 * &lt;role name="INNDATA" value="MY_ADDRESS_JSON"/&gt;
	 * &lt;/application&gt;
	 * &lt;ns2:link type="application/xml" href="https://whydahdev.cantara.no/tokenservice/user/0700445a583e014102affd92c8d896a0/validate_usertokenid/16987d1a-f305-4a98-a3dc-2ce9a97e8424" rel="self"/&gt;
	 * &lt;hash type="MD5"&gt;57755c9efa6337dc9739fe5cb719a9b4&lt;/hash&gt;
	 * &lt;/usertoken&gt;
	 * @apiError 403/7000 Application is invalid.
	 * @apiError 406/6002 Attempt to access with non-acceptable usertokenid.
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
	@Path("/{applicationtokenid}/get_usertoken_by_usertokenid")
	@POST
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	@Produces(MediaType.APPLICATION_XML)
	public Response getUserTokenByUserTokenId(@PathParam("applicationtokenid") String applicationtokenid,
			@FormParam("apptoken") String appTokenXml,
			@FormParam("usertokenid") String userTokenId) throws AppException {
		log.trace("getUserTokenByUserTokenId: applicationtokenid={}, usertokenid={}, appTokenXml={}", applicationtokenid, userTokenId, appTokenXml);

		if (ApplicationMode.getApplicationMode().equals(ApplicationMode.DEV)) {
			return DevModeHelper.return_DEV_MODE_ExampleUserToken(1);
		}
		if (!UserTokenFactory.verifyApplicationToken(applicationtokenid, appTokenXml)) {
			log.warn("getUserTokenByUserTokenId - attempt to access from invalid application. applicationtokenid={}", applicationtokenid);
			//return Response.status(Response.Status.FORBIDDEN).entity(ILLEGAL_APPLICATION_FOR_THIS_SERVICE).header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
			throw AppExceptionCode.APP_ILLEGAL_7000;
		}

		if (failinguserTokenMap.get(userTokenId) != null) {
			log.warn("getUserTokenByUserTokenId - repeatedly attempt to access with non-acceptable usertokenid={}", userTokenId);
			//return Response.status(Response.Status.NOT_ACCEPTABLE).header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
			throw AppExceptionCode.USER_INVALID_USERTOKENID_6002.setDeveloperMessage("getUserTokenByUserTokenId - blacklisted - attempt to access with non acceptable usertokenid=" + userTokenId);
		}

		final UserToken userToken = AuthenticatedUserTokenRepository.getUserToken(userTokenId, applicationtokenid);
		//final UserToken userToken = refreshAndGetThisUser(userTokenId, applicationtokenid);
		if (userToken == null) {
			log.warn("getUserTokenByUserTokenId - attempt to access with non-acceptable usertokenid={}", userTokenId);
			//return Response.status(Response.Status.NOT_ACCEPTABLE).header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();

			// make it a bit more robust in terms of requiring two consewuitive attempts before being blacklisted.
			if (failingUserTokenId != null && failingUserTokenId.equalsIgnoreCase(userTokenId)) {
				failinguserTokenMap.put(userTokenId, userTokenId);
				failingUserTokenId = null;
			} else {
				failingUserTokenId = userTokenId;
			}
			throw AppExceptionCode.USER_INVALID_USERTOKENID_6002.setDeveloperMessage("getUserTokenByUserTokenId - initial attempt to access with non-acceptable usertokenid=" + userTokenId);
		}
		log.info("getUserTokenByUserTokenId - valid session found for {} ", userTokenId);


		return createUserTokenResponse(applicationtokenid, userToken);
	}

	//    public UserToken refreshAndGetThisUser(String userTokenId, String applicationtokenid){
	//    	//shoudl refresh frist
	//        UserToken refreshedUserToken = userAuthenticator.getRefreshedUserToken(userTokenId);
	//        AuthenticatedUserTokenRepository.refreshUserToken(userTokenId, applicationtokenid, refreshedUserToken);
	//        final UserToken userToken = AuthenticatedUserTokenRepository.getUserToken(userTokenId, applicationtokenid);
	//        return userToken;
	//    }

	/**
	 * @throws AppException
	 * @api {post} :applicationtokenid/:email/last_seen getLastSeenByUserTokenId
	 * @apiName getLastSeenByUserTokenId
	 * @apiGroup Security Token Service (STS)
	 * @apiDescription This is used to get the last sent time. The result is a string of the form: dow mon dd hh:mm:ss zzz yyyy converted from Date object (See Date().toString())
	 * @apiParam {String} usertoken User Token XML.
	 * @apiParamExample {xml} Request-Example:
	 * &lt;?xml version="1.0" encoding="UTF-8" standalone="yes"?&gt;
	 * &lt;usertoken xmlns:ns2="http://www.w3.org/1999/xhtml" id="16987d1a-f305-4a98-a3dc-2ce9a97e8424"&gt;
	 * &lt;uid&gt;systest&lt;/uid&gt;
	 * &lt;timestamp&gt;1480935597694&lt;/timestamp&gt;
	 * &lt;lifespan&gt;1209600000&lt;/lifespan&gt;
	 * &lt;issuer&gt;https://whydahdev.cantara.no/tokenservice/user/0700445a583e014102affd92c8d896a0/validate_usertokenid/16987d1a-f305-4a98-a3dc-2ce9a97e8424&lt;/issuer&gt;
	 * &lt;securitylevel&gt;1&lt;/securitylevel&gt;
	 * &lt;DEFCON&gt;&lt;/DEFCON&gt;
	 * &lt;username&gt;systest&lt;/username&gt;
	 * &lt;firstname&gt;SystemTestUser&lt;/firstname&gt;
	 * &lt;lastname&gt;UserAdminWebApp&lt;/lastname&gt;
	 * &lt;cellphone&gt;87654321&lt;/cellphone&gt;
	 * &lt;email&gt;whydahadmin@getwhydah.com&lt;/email&gt;
	 * &lt;personref&gt;42&lt;/personref&gt;
	 * &lt;application ID="101"&gt;
	 * &lt;applicationName&gt;ACSResource&lt;/applicationName&gt;
	 * &lt;organizationName&gt;Opplysningen 1881&lt;/organizationName&gt;
	 * &lt;role name="INNDATA" value="MY_ADDRESS_JSON"/&gt;
	 * &lt;/application&gt;
	 * &lt;ns2:link type="application/xml" href="https://whydahdev.cantara.no/tokenservice/user/0700445a583e014102affd92c8d896a0/validate_usertokenid/16987d1a-f305-4a98-a3dc-2ce9a97e8424" rel="self"/&gt;
	 * &lt;hash type="MD5"&gt;57755c9efa6337dc9739fe5cb719a9b4&lt;/hash&gt;
	 * &lt;/usertoken&gt;
	 * @apiSuccessExample Success-Response:
	 * Thu Jan 10 02:00:00 EET 1992
	 * @apiError 403/7000 Application is invalid.
	 * @apiError 406/6005 Attempt to access with non acceptable user's email.
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
	@Path("/{applicationtokenid}/{email}/last_seen")
	@GET
	@Produces(MediaType.TEXT_PLAIN)
	public Response getLastSeenByUserTokenId(@PathParam("applicationtokenid") String applicationtokenid,
			@PathParam("email") String userEmail) throws AppException {
		log.trace("getLastSeenByUserTokenId: applicationtokenid={}, email={}", applicationtokenid, userEmail);

		if (ApplicationMode.getApplicationMode().equals(ApplicationMode.DEV)) {
			return DevModeHelper.return_DEV_MODE_ExampleUserToken(1);
		}

		if (!UserTokenFactory.verifyApplicationToken(applicationtokenid, "")) {
			log.warn("getLastSeenByUserTokenId - attempt to access from invalid application. applicationtokenid={}", applicationtokenid);
			//return Response.status(Response.Status.FORBIDDEN).entity(ILLEGAL_APPLICATION_FOR_THIS_SERVICE).header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
			throw AppExceptionCode.APP_ILLEGAL_7000;
		}
		String lastSeen = AuthenticatedUserTokenRepository.getLastSeenByEmail(userEmail);
		if (lastSeen == null) {
			log.warn("getLastSeenByUserTokenId - attempt to access with non acceptable userEmail={}", userEmail);
			//return Response.status(Response.Status.NOT_ACCEPTABLE).header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
			throw AppExceptionCode.USER_USER_EMAIL_NOTFOUND_6005;
		}
		log.info("getLastSeenByUserTokenId - valid session found for {} ", userEmail);
		return Response.status(Response.Status.OK).entity(lastSeen).header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
	}

	/**
	 * @throws AppException
	 * @api {post} :applicationtokenid/get_usertoken_by_userticket getUserTokenByUserTicket
	 * @apiName getUserTokenByUserTicket
	 * @apiGroup Security Token Service (STS)
	 * @apiDescription Lookup a user by a one-time userticket, usually the first thing we do after receiving a SSO redirect back to
	 * @apiParam {String} apptoken Application Token XML.
	 * @apiParamExample {xml} Request-Example:
	 * &lt;applicationtoken&gt;
	 * &lt;params&gt;
	 * &lt;applicationtokenID&gt;1d58b70dc0fdc98b5cdce4745fb086c4&lt;/applicationtokenID&gt;
	 * &lt;applicationid&gt;101&lt;/applicationid&gt;
	 * &lt;applicationname&gt;Whydah-SystemTests&lt;/applicationname&gt;
	 * &lt;expires&gt;1480931112185&lt;/expires&gt;
	 * &lt;/params&gt;
	 * &lt;Url type="application/xml" method="POST" template="https://whydahdev.cantara.no/tokenservice/user/1d58b70dc0fdc98b5cdce4745fb086c4/get_usertoken_by_usertokenid"/&gt;
	 * &lt;/applicationtoken&gt;
	 * @apiParam {String} userticket The userticket which is redirected back from SSO Logon service
	 * @apiSuccessExample Success-Response:
	 * HTTP/1.1 200 OK
	 * &lt;?xml version="1.0" encoding="UTF-8" standalone="yes"?&gt;
	 * &lt;usertoken xmlns:ns2="http://www.w3.org/1999/xhtml" id="16987d1a-f305-4a98-a3dc-2ce9a97e8424"&gt;
	 * &lt;uid&gt;systest&lt;/uid&gt;
	 * &lt;timestamp&gt;1480935597694&lt;/timestamp&gt;
	 * &lt;lifespan&gt;1209600000&lt;/lifespan&gt;
	 * &lt;issuer&gt;https://whydahdev.cantara.no/tokenservice/user/0700445a583e014102affd92c8d896a0/validate_usertokenid/16987d1a-f305-4a98-a3dc-2ce9a97e8424&lt;/issuer&gt;
	 * &lt;securitylevel&gt;1&lt;/securitylevel&gt;
	 * &lt;DEFCON&gt;&lt;/DEFCON&gt;
	 * &lt;username&gt;systest&lt;/username&gt;
	 * &lt;firstname&gt;SystemTestUser&lt;/firstname&gt;
	 * &lt;lastname&gt;UserAdminWebApp&lt;/lastname&gt;
	 * &lt;cellphone&gt;87654321&lt;/cellphone&gt;
	 * &lt;email&gt;whydahadmin@getwhydah.com&lt;/email&gt;
	 * &lt;personref&gt;42&lt;/personref&gt;
	 * &lt;application ID="101"&gt;
	 * &lt;applicationName&gt;ACSResource&lt;/applicationName&gt;
	 * &lt;organizationName&gt;Opplysningen 1881&lt;/organizationName&gt;
	 * &lt;role name="INNDATA" value="MY_ADDRESS_JSON"/&gt;
	 * &lt;/application&gt;
	 * &lt;ns2:link type="application/xml" href="https://whydahdev.cantara.no/tokenservice/user/0700445a583e014102affd92c8d896a0/validate_usertokenid/16987d1a-f305-4a98-a3dc-2ce9a97e8424" rel="self"/&gt;
	 * &lt;hash type="MD5"&gt;57755c9efa6337dc9739fe5cb719a9b4&lt;/hash&gt;
	 * &lt;/usertoken&gt;
	 * @apiError 403/7000 Application is invalid.
	 * @apiError 406/6002 Authentication failed.
	 * @apiError 406/6003 Attempt to resolve non-existing userticket.
	 * @apiError 400/9998 Missing required parameters
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
	@Path("/{applicationtokenid}/get_usertoken_by_userticket")
	@POST
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	@Produces(MediaType.APPLICATION_XML)
	public Response getUserTokenByUserTicket(@PathParam("applicationtokenid") String applicationtokenid,
			@FormParam("apptoken") String appTokenXml,
			@FormParam("userticket") String userticket) throws AppException {
		if (isEmpty(appTokenXml) || isEmpty(userticket)) {
			//return Response.status(Response.Status.BAD_REQUEST).entity("Missing required parameters").build();
			throw AppExceptionCode.MISC_MISSING_PARAMS_9998;
		}

		log.debug("getUserTokenByUserTicket: applicationtokenid={}, userticket={}, appTokenXml={}", applicationtokenid, userticket, appTokenXml);

		if (ApplicationMode.getApplicationMode().equals(ApplicationMode.DEV)) {
			return DevModeHelper.return_DEV_MODE_ExampleUserToken(1);
		}

		if (!UserTokenFactory.verifyApplicationToken(applicationtokenid, appTokenXml)) {
			log.warn("getUserTokenByUserTicket - attempt to access from invalid application. applicationtokenid={}", applicationtokenid);
			//return Response.status(Response.Status.FORBIDDEN).entity(ILLEGAL_APPLICATION_FOR_THIS_SERVICE).header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
			throw AppExceptionCode.APP_ILLEGAL_7000;
		}
		String userTokenId = (String) userticketmap.get(userticket);
		if (userTokenId == null) {
			log.warn("getUserTokenByUserTicket - Attempt to resolve non-existing userticket={}", userticket);
			//return Response.status(Response.Status.GONE).header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build(); //410
			throw AppExceptionCode.USER_USERTICKET_NOTFOUND_6003.setDeveloperMessage("getUserTokenByUserTicket - Attempt to resolve non-existing userticket=" + userticket);
		}
		log.trace("getUserTokenByUserTicket - Found usertokenid: " + userTokenId);
		userticketmap.remove(userticket);
		final UserToken userToken = AuthenticatedUserTokenRepository.getUserToken(userTokenId, applicationtokenid);
		//final UserToken userToken = refreshAndGetThisUser(userTokenId, applicationtokenid);

		if (userToken == null) {
			log.warn("getUserTokenByUserTicket - illegal/Null userticket received ");
			//return Response.status(Response.Status.NOT_ACCEPTABLE).header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build(); //406
			throw AppExceptionCode.USER_INVALID_USERTOKENID_6002.setDeveloperMessage("getUserTokenByUserTicket - illegal/Null userticket received");
		}
		log.debug("getUserTokenByUserTicket OK. Response={}", userToken.toString());
		return createUserTokenResponse(applicationtokenid, userToken);
	}

	/**
	 * @throws AppException
	 * @api {post} :applicationtokenid/:userticket/get_usertoken_by_pin_and_logon_user getUserTokenByPinAndLogonUser
	 * @apiName getUserTokenByPinAndLogonUser
	 * @apiGroup Security Token Service (STS)
	 * @apiDescription Lookup a user by a one-time pin-code distributed to the users registered cellPhone number, usually the first thing we do after receiving a SSO redirect back to
	 * @apiParam {String} apptoken Application Token XML.
	 * @apiParamExample {xml} Request-Example:
	 * &lt;applicationtoken&gt;
	 * &lt;params&gt;
	 * &lt;applicationtokenID&gt;1d58b70dc0fdc98b5cdce4745fb086c4&lt;/applicationtokenID&gt;
	 * &lt;applicationid&gt;101&lt;/applicationid&gt;
	 * &lt;applicationname&gt;Whydah-SystemTests&lt;/applicationname&gt;
	 * &lt;expires&gt;1480931112185&lt;/expires&gt;
	 * &lt;/params&gt;
	 * &lt;Url type="application/xml" method="POST" template="https://whydahdev.cantara.no/tokenservice/user/1d58b70dc0fdc98b5cdce4745fb086c4/get_usertoken_by_usertokenid"/&gt;
	 * &lt;/applicationtoken&gt;
	 * @apiParam {String} adminUserTokenId The UserTokenId
	 * @apiParam {String} phoneno The phone number to which a pin-code is sent
	 * @apiParam {String} pin The pin-code is distributred to the registered cellPhone number
	 * @apiSuccessExample Success-Response:
	 * HTTP/1.1 200 OK
	 * &lt;?xml version="1.0" encoding="UTF-8" standalone="yes"?&gt;
	 * &lt;usertoken xmlns:ns2="http://www.w3.org/1999/xhtml" id="16987d1a-f305-4a98-a3dc-2ce9a97e8424"&gt;
	 * &lt;uid&gt;systest&lt;/uid&gt;
	 * &lt;timestamp&gt;1480935597694&lt;/timestamp&gt;
	 * &lt;lifespan&gt;1209600000&lt;/lifespan&gt;
	 * &lt;issuer&gt;https://whydahdev.cantara.no/tokenservice/user/0700445a583e014102affd92c8d896a0/validate_usertokenid/16987d1a-f305-4a98-a3dc-2ce9a97e8424&lt;/issuer&gt;
	 * &lt;securitylevel&gt;1&lt;/securitylevel&gt;
	 * &lt;DEFCON&gt;&lt;/DEFCON&gt;
	 * &lt;username&gt;systest&lt;/username&gt;
	 * &lt;firstname&gt;SystemTestUser&lt;/firstname&gt;
	 * &lt;lastname&gt;UserAdminWebApp&lt;/lastname&gt;
	 * &lt;cellphone&gt;87654321&lt;/cellphone&gt;
	 * &lt;email&gt;whydahadmin@getwhydah.com&lt;/email&gt;
	 * &lt;personref&gt;42&lt;/personref&gt;
	 * &lt;application ID="101"&gt;
	 * &lt;applicationName&gt;ACSResource&lt;/applicationName&gt;
	 * &lt;organizationName&gt;Opplysningen 1881&lt;/organizationName&gt;
	 * &lt;role name="INNDATA" value="MY_ADDRESS_JSON"/&gt;
	 * &lt;/application&gt;
	 * &lt;ns2:link type="application/xml" href="https://whydahdev.cantara.no/tokenservice/user/0700445a583e014102affd92c8d896a0/validate_usertokenid/16987d1a-f305-4a98-a3dc-2ce9a97e8424" rel="self"/&gt;
	 * &lt;hash type="MD5"&gt;57755c9efa6337dc9739fe5cb719a9b4&lt;/hash&gt;
	 * &lt;/usertoken&gt;
	 * @apiError 403/7000 Application is invalid.
	 * @apiError 406/6004 Attempt to access with non acceptable username/phoneno.
	 * @apiError 400/9998 Missing required parameters
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
	@Path("/{applicationtokenid}/{userticket}/get_usertoken_by_pin_and_logon_user")
	@POST
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	@Produces(MediaType.APPLICATION_XML)
	public Response getUserTokenByPinAndLogonUser(@PathParam("applicationtokenid") String applicationtokenid,
			@PathParam("userticket") String userticket,
			@FormParam("adminUserTokenId") String adminUserTokenId,
			@FormParam("apptoken") String appTokenXml,
			@FormParam("phoneno") String phoneno,
			@FormParam("pin") String pin) throws AppException {

		log.trace("getUserTokenByDistributedPinAndLogonUser() called with " + "applicationtokenid = [" + applicationtokenid + "], userticket = [" + userticket + "], appTokenXml = [" + appTokenXml + "], phoneno = [" + phoneno + "], pin = [" + pin + "]");

		if (isEmpty(appTokenXml) || isEmpty(pin) || isEmpty(phoneno)) {
			//return Response.status(Response.Status.BAD_REQUEST).entity("Missing required parameters").build();
			throw AppExceptionCode.MISC_MISSING_PARAMS_9998;
		}

		log.trace("getUserTokenByDistributedPinAndLogonUser: applicationtokenid={}, pin={}, appTokenXml={}", applicationtokenid, pin, appTokenXml);

		if (ApplicationMode.getApplicationMode().equals(ApplicationMode.DEV)) {
			return DevModeHelper.return_DEV_MODE_ExampleUserToken(1);
		}

		// Verify calling application
		if (!UserTokenFactory.verifyApplicationToken(applicationtokenid, appTokenXml)) {
			log.warn("getUserTokenByUserTicket - attempt to access from invalid application. applicationtokenid={}", applicationtokenid);
			//return Response.status(Response.Status.FORBIDDEN).entity(ILLEGAL_APPLICATION_FOR_THIS_SERVICE).header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
			throw AppExceptionCode.APP_ILLEGAL_7000;
		}

		try {
			UserToken userToken = userAuthenticator.logonPinUser(applicationtokenid, appTokenXml, adminUserTokenId, phoneno, pin);
			return createUserTokenResponse(applicationtokenid, userToken);

		} catch (AuthenticationFailedException ae) {
			log.warn("getUserToken - User authentication failed");
			//return Response.status(Response.Status.NOT_ACCEPTABLE).header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
			throw AppExceptionCode.USER_LOGIN_PIN_FAILED_6004.setDeveloperMessage(ae.getMessage());
		}


	}

	/**
	 * @throws AppException
	 * @api {post} :applicationtokenid/releaseUserToken releaseUserToken
	 * @apiName releaseUserToken
	 * @apiGroup Security Token Service (STS)
	 * @apiDescription Force cross-applications/SSO session logout.
	 * @apiParam {String} usertokenid The UserTokenId
	 * @apiSuccessExample Success-Response:
	 * HTTP/1.1 200 OK
	 * {
	 * "result": "true"
	 * }
	 * @apiError 403/7000 Application is invalid.
	 * @apiError 400/9998 Missing required parameters
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
	@Path("/{applicationtokenid}/release_usertoken")
	@POST
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response releaseUserToken(@PathParam("applicationtokenid") String applicationtokenid,
			@FormParam("usertokenid") String usertokenid) throws AppException {
		log.trace("releaseUserToken - entry.  usertokenid={}", usertokenid);
		if (!AuthenticatedApplicationTokenRepository.verifyApplicationTokenId(applicationtokenid)) {
			log.warn("releaseUserToken - attempt to access from invalid application. applicationtokenid={}", applicationtokenid);
			//return Response.status(Response.Status.FORBIDDEN).entity("Application authentication not valid.").header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
			throw AppExceptionCode.APP_ILLEGAL_7000;
		}
		if (usertokenid == null) {
			log.warn("releaseUserToken - attempt with no usertokenid: Null");
			//return Response.status(Response.Status.BAD_REQUEST).entity("Missing usertokenid.").header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
			throw AppExceptionCode.MISC_MISSING_PARAMS_9998;
		}
		log.trace("releaseUserToken - removed session, usertokenid={}", usertokenid);
		AuthenticatedUserTokenRepository.removeUserToken(usertokenid, applicationtokenid);
		return Response.ok("{\"result\": \"true\"}").header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
	}

	/**
	 * @throws AppException
	 * @api {post} :applicationtokenid/renew_usertoken renewUserToken
	 * @apiName renewUserToken
	 * @apiGroup Security Token Service (STS)
	 * @apiDescription Request SSO user session renewal.
	 * @apiParam {String} usertokenid The UserTokenId
	 * @apiSuccessExample Success-Response:
	 * HTTP/1.1 200 OK
	 * {
	 * "result": "true"
	 * }
	 * @apiError 403/7000 Application is invalid.
	 * @apiError 400/9998 Missing required parameters
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
	@Path("/{applicationtokenid}/renew_usertoken")
	@POST
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response renewUserToken(@PathParam("applicationtokenid") String applicationtokenid,
			@FormParam("usertokenid") String usertokenid) throws AppException {
		log.trace("renewUserToken - entry.  usertokenid={}", usertokenid);
		if (!AuthenticatedApplicationTokenRepository.verifyApplicationTokenId(applicationtokenid)) {
			log.warn("renewUserToken - attempt to access from invalid application. applicationtokenid={}", applicationtokenid);
			//return Response.status(Response.Status.FORBIDDEN).entity("Application authentication not valid.").header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
			throw AppExceptionCode.APP_ILLEGAL_7000;
		}
		if (isEmpty(usertokenid)) {
			log.warn("renewUserToken - attempt with no usertokenid: Null");
			//return Response.status(Response.Status.BAD_REQUEST).entity("Missing usertokenid.").header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
			throw AppExceptionCode.MISC_MISSING_PARAMS_9998;
		}

		AuthenticatedUserTokenRepository.renewUserToken(usertokenid, applicationtokenid);

		log.trace("renewUserToken - session renewed, usertokenid={}", usertokenid);
		return Response.ok("{\"result\": \"true\"}").header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
	}

	/**
	 * @throws AppException
	 * @api {post} :applicationtokenid/refresh_usertoken refreshUserToken
	 * @apiName refreshUserToken
	 * @apiGroup Security Token Service (STS)
	 * @apiDescription Refresh SSO user session.
	 * @apiParam {String} usertokenid The UserTokenId
	 * @apiSuccessExample Success-Response:
	 * HTTP/1.1 200 OK
	 * &lt;?xml version="1.0" encoding="UTF-8" standalone="yes"?&gt;
	 * &lt;usertoken xmlns:ns2="http://www.w3.org/1999/xhtml" id="16987d1a-f305-4a98-a3dc-2ce9a97e8424"&gt;
	 * &lt;uid&gt;systest&lt;/uid&gt;
	 * &lt;timestamp&gt;1480935597694&lt;/timestamp&gt;
	 * &lt;lifespan&gt;1209600000&lt;/lifespan&gt;
	 * &lt;issuer&gt;https://whydahdev.cantara.no/tokenservice/user/0700445a583e014102affd92c8d896a0/validate_usertokenid/16987d1a-f305-4a98-a3dc-2ce9a97e8424&lt;/issuer&gt;
	 * &lt;securitylevel&gt;1&lt;/securitylevel&gt;
	 * &lt;DEFCON&gt;&lt;/DEFCON&gt;
	 * &lt;username&gt;systest&lt;/username&gt;
	 * &lt;firstname&gt;SystemTestUser&lt;/firstname&gt;
	 * &lt;lastname&gt;UserAdminWebApp&lt;/lastname&gt;
	 * &lt;cellphone&gt;87654321&lt;/cellphone&gt;
	 * &lt;email&gt;whydahadmin@getwhydah.com&lt;/email&gt;
	 * &lt;personref&gt;42&lt;/personref&gt;
	 * &lt;application ID="101"&gt;
	 * &lt;applicationName&gt;ACSResource&lt;/applicationName&gt;
	 * &lt;organizationName&gt;Opplysningen 1881&lt;/organizationName&gt;
	 * &lt;role name="INNDATA" value="MY_ADDRESS_JSON"/&gt;
	 * &lt;/application&gt;
	 * &lt;ns2:link type="application/xml" href="https://whydahdev.cantara.no/tokenservice/user/0700445a583e014102affd92c8d896a0/validate_usertokenid/16987d1a-f305-4a98-a3dc-2ce9a97e8424" rel="self"/&gt;
	 * &lt;hash type="MD5"&gt;57755c9efa6337dc9739fe5cb719a9b4&lt;/hash&gt;
	 * &lt;/usertoken&gt;
	 * @apiError 403/7000 Application is invalid.
	 * @apiError 400/9998 Missing required parameters.
	 * @apiError 403/6000 Authentication failed.
	 * @apiError 500/9999 A generic exception or an unexpected error.
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
	@Path("/{applicationtokenid}/refresh_usertoken")
	@POST
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response refreshUserToken(@PathParam("applicationtokenid") String applicationtokenid,
			@FormParam("usertokenid") String usertokenid) throws AppException {
		log.debug("refresh_usertoken - entry.  usertokenid={}, applicationtokenid:{}", usertokenid, applicationtokenid);
		if (!AuthenticatedApplicationTokenRepository.verifyApplicationTokenId(applicationtokenid)) {
			log.warn("refresh_usertoken - attempt to access from invalid application. applicationtokenid={}", applicationtokenid);
			//return Response.status(Response.Status.FORBIDDEN).entity("Application authentication not valid.").header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
			throw AppExceptionCode.APP_ILLEGAL_7000;
		}
		if (usertokenid == null) {
			log.warn("refresh_usertoken - attempt with no usertokenid: Null");
			//return Response.status(Response.Status.BAD_REQUEST).entity("Missing usertokenid.").header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
			throw AppExceptionCode.MISC_MISSING_PARAMS_9998;
		}

		try {
			UserToken refreshedUserToken = userAuthenticator.getRefreshedUserToken(usertokenid);

			final UserToken userToken = AuthenticatedUserTokenRepository.refreshUserToken(applicationtokenid, refreshedUserToken);
			log.debug("refresh_usertoken - usertoken refreshed, usertokenid={}", usertokenid);
			return createUserTokenResponse(applicationtokenid, userToken);
		} catch (AuthenticationFailedException ae) {
			log.warn("Unable to refresh_usertoken, usertokenid:{}", usertokenid, ae);
			throw AppExceptionCode.USER_AUTHENTICATION_FAILED_6000.setDeveloperMessage(ae.getMessage());
		} catch (Exception e) {
			log.warn("Unable to refresh_usertoken, usertokenid:{}", usertokenid, e);
			throw AppExceptionCode.USER_AUTHENTICATION_FAILED_6000.setDeveloperMessage(e.getMessage());
		}


	}

	@Path("/{applicationtokenid}/refresh_usertoken_by_username")
	@POST
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response refreshUserTokenByUsername(@PathParam("applicationtokenid") String applicationtokenid,
			@FormParam("username") String username) throws AppException {
		log.debug("refresh_usertoken_by_username - entry.  username={}, applicationtokenid:{}", username, applicationtokenid);
		if (!AuthenticatedApplicationTokenRepository.verifyApplicationTokenId(applicationtokenid)) {
			log.warn("refresh_usertoken_by_username - attempt to access from invalid application. applicationtokenid={}", applicationtokenid);
			//return Response.status(Response.Status.FORBIDDEN).entity("Application authentication not valid.").header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
			throw AppExceptionCode.APP_ILLEGAL_7000;
		}
		UserToken usertoken = AuthenticatedUserTokenRepository.getUserTokenByUserName(username, applicationtokenid);
		if (usertoken == null) {
			log.warn("refresh_usertoken_by_username - attempt with no username found. No need to refresh");
			return Response.ok("").header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
		}

		try {
			UserToken refreshedUserToken = userAuthenticator.getRefreshedUserToken(usertoken.getUserTokenId());
			final UserToken userToken = AuthenticatedUserTokenRepository.refreshUserToken(applicationtokenid, refreshedUserToken);
			log.debug("refresh_usertoken_by_username - usertoken refreshed, usertokenid={}", userToken.getUserTokenId());
			return createUserTokenResponse(applicationtokenid, userToken);
		} catch (AuthenticationFailedException ae) {
			throw AppExceptionCode.USER_AUTHENTICATION_FAILED_6000.setDeveloperMessage(ae.getMessage());
		}


	}

	/**
	 * @throws AppException
	 * @api {post} :applicationtokenid/transform_usertoken transformUserToken
	 * @apiName transformUserToken
	 * @apiGroup Security Token Service (STS)
	 * @apiDescription This method is for elevating user access to a higher level for the receiving end of a session handover between SSO applications
	 * @apiParam {String} apptoken Application Token XML.
	 * @apiParamExample {xml} Request-Example:
	 * &lt;applicationtoken&gt;
	 * &lt;params&gt;
	 * &lt;applicationtokenID&gt;1d58b70dc0fdc98b5cdce4745fb086c4&lt;/applicationtokenID&gt;
	 * &lt;applicationid&gt;101&lt;/applicationid&gt;
	 * &lt;applicationname&gt;Whydah-SystemTests&lt;/applicationname&gt;
	 * &lt;expires&gt;1480931112185&lt;/expires&gt;
	 * &lt;/params&gt;
	 * &lt;Url type="application/xml" method="POST" template="https://whydahdev.cantara.no/tokenservice/user/1d58b70dc0fdc98b5cdce4745fb086c4/get_usertoken_by_usertokenid"/&gt;
	 * &lt;/applicationtoken&gt;
	 * @apiParam {String} usertoken User Token XML to transfer.
	 * @apiParamExample {xml} Request-Example:
	 * &lt;?xml version="1.0" encoding="UTF-8" standalone="yes"?&gt;
	 * &lt;usertoken xmlns:ns2="http://www.w3.org/1999/xhtml" id="16987d1a-f305-4a98-a3dc-2ce9a97e8424"&gt;
	 * &lt;uid&gt;systest&lt;/uid&gt;
	 * &lt;timestamp&gt;1480935597694&lt;/timestamp&gt;
	 * &lt;lifespan&gt;1209600000&lt;/lifespan&gt;
	 * &lt;issuer&gt;https://whydahdev.cantara.no/tokenservice/user/0700445a583e014102affd92c8d896a0/validate_usertokenid/16987d1a-f305-4a98-a3dc-2ce9a97e8424&lt;/issuer&gt;
	 * &lt;securitylevel&gt;1&lt;/securitylevel&gt;
	 * &lt;DEFCON&gt;&lt;/DEFCON&gt;
	 * &lt;username&gt;systest&lt;/username&gt;
	 * &lt;firstname&gt;SystemTestUser&lt;/firstname&gt;
	 * &lt;lastname&gt;UserAdminWebApp&lt;/lastname&gt;
	 * &lt;cellphone&gt;87654321&lt;/cellphone&gt;
	 * &lt;email&gt;whydahadmin@getwhydah.com&lt;/email&gt;
	 * &lt;personref&gt;42&lt;/personref&gt;
	 * &lt;application ID="101"&gt;
	 * &lt;applicationName&gt;ACSResource&lt;/applicationName&gt;
	 * &lt;organizationName&gt;Opplysningen 1881&lt;/organizationName&gt;
	 * &lt;role name="INNDATA" value="MY_ADDRESS_JSON"/&gt;
	 * &lt;/application&gt;
	 * &lt;ns2:link type="application/xml" href="https://whydahdev.cantara.no/tokenservice/user/0700445a583e014102affd92c8d896a0/validate_usertokenid/16987d1a-f305-4a98-a3dc-2ce9a97e8424" rel="self"/&gt;
	 * &lt;hash type="MD5"&gt;57755c9efa6337dc9739fe5cb719a9b4&lt;/hash&gt;
	 * &lt;/usertoken&gt;
	 * @apiParam {String} to_apptokenid New application receiving this user sts
	 * @apiSuccessExample Success-Response:
	 * HTTP/1.1 200 OK
	 * &lt;?xml version="1.0" encoding="UTF-8" standalone="yes"?&gt;
	 * &lt;usertoken xmlns:ns2="http://www.w3.org/1999/xhtml" id="16987d1a-f305-4a98-a3dc-2ce9a97e8424"&gt;
	 * &lt;uid&gt;systest&lt;/uid&gt;
	 * &lt;timestamp&gt;1480935597694&lt;/timestamp&gt;
	 * &lt;lifespan&gt;1209600000&lt;/lifespan&gt;
	 * &lt;issuer&gt;https://whydahdev.cantara.no/tokenservice/user/0700445a583e014102affd92c8d896a0/validate_usertokenid/16987d1a-f305-4a98-a3dc-2ce9a97e8424&lt;/issuer&gt;
	 * &lt;securitylevel&gt;1&lt;/securitylevel&gt;
	 * &lt;DEFCON&gt;&lt;/DEFCON&gt;
	 * &lt;username&gt;systest&lt;/username&gt;
	 * &lt;firstname&gt;SystemTestUser&lt;/firstname&gt;
	 * &lt;lastname&gt;UserAdminWebApp&lt;/lastname&gt;
	 * &lt;cellphone&gt;87654321&lt;/cellphone&gt;
	 * &lt;email&gt;whydahadmin@getwhydah.com&lt;/email&gt;
	 * &lt;personref&gt;42&lt;/personref&gt;
	 * &lt;application ID="101"&gt;
	 * &lt;applicationName&gt;ACSResource&lt;/applicationName&gt;
	 * &lt;organizationName&gt;Opplysningen 1881&lt;/organizationName&gt;
	 * &lt;role name="INNDATA" value="MY_ADDRESS_JSON"/&gt;
	 * &lt;/application&gt;
	 * &lt;ns2:link type="application/xml" href="https://whydahdev.cantara.no/tokenservice/user/0700445a583e014102affd92c8d896a0/validate_usertokenid/16987d1a-f305-4a98-a3dc-2ce9a97e8424" rel="self"/&gt;
	 * &lt;hash type="MD5"&gt;57755c9efa6337dc9739fe5cb719a9b4&lt;/hash&gt;
	 * &lt;/usertoken&gt;
	 * @apiError 403/7000 Application is invalid.
	 * @apiError 406/6002 Attempt to access with non acceptable usertokenid.
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
	@Path("/{applicationtokenid}/transform_usertoken")
	@POST
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	@Produces(MediaType.APPLICATION_XML)
	public Response transformUserToken(@PathParam("applicationtokenid") String applicationtokenid,
			@FormParam("apptoken") String appTokenXml,
			@FormParam("usertoken") String userTokenXml,
			@FormParam("to_apptokenid") String newAppTokenId) throws AppException {
		if (ApplicationMode.getApplicationMode().equals(ApplicationMode.DEV)) {
			return DevModeHelper.return_DEV_MODE_ExampleUserToken(1);
		}

		if (ApplicationMode.getApplicationMode().equals(ApplicationMode.DEV)) {
			return DevModeHelper.return_DEV_MODE_ExampleUserToken(1);
		}

		if (!UserTokenFactory.verifyApplicationToken(applicationtokenid, appTokenXml)) {
			log.warn("getUserTokenByUserTokenId - attempt to access from invalid application. applicationtokenid={}", applicationtokenid);
			//return Response.status(Response.Status.FORBIDDEN).entity(ILLEGAL_APPLICATION_FOR_THIS_SERVICE).header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
			throw AppExceptionCode.APP_ILLEGAL_7000;
		}
		String userTokenId = UserTokenMapper.fromUserTokenXml(userTokenXml).getUserTokenId();
		final UserToken userToken = AuthenticatedUserTokenRepository.getUserToken(userTokenId, applicationtokenid);
		//final UserToken userToken = refreshAndGetThisUser(userTokenId, applicationtokenid);

		if (userToken == null) {
			log.warn("getUserTokenByUserTokenId - attempt to access with non acceptable userTokenId={}", userTokenId);
			//return Response.status(Response.Status.NOT_ACCEPTABLE).header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
			throw AppExceptionCode.USER_INVALID_USERTOKENID_6002;
		}
		userToken.setDefcon(ThreatResource.getDEFCON());
		return createUserTokenResponse(newAppTokenId, userToken);
	}

	/**
	 * @throws AppException
	 * @apiIgnore The backend for PIN signup processes
	 * @api {post} :applicationtokenid/send_sms_pin sendSMSPin
	 * @apiName sendSMSPin
	 * @apiGroup Security Token Service (STS)
	 * @apiDescription The backend for PIN signup processes
	 * @apiParam {String} phoneNo The phone number to which a pin-code is sent
	 * @apiParam {String} smsPin The pin-code massage to send to the user
	 * @apiSuccessExample Success-Response:
	 * HTTP/1.1 200 OK
	 * {
	 * "result": "true"
	 * }
	 * @apiError 403/7000 Application is invalid.
	 * @apiError 400/9998 Missing required parameters
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
	@Path("/{applicationtokenid}/send_sms_pin")
	@POST
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	@Produces(MediaType.APPLICATION_XML)
	public Response sendSMSPin(@PathParam("applicationtokenid") String applicationtokenid,
			@FormParam("phoneNo") String phoneNo,
			@FormParam("smsPin") String smsPin) throws AppException {
		log.info("sendSMSPin: phoneNo:" + phoneNo + ", smsPin:" + smsPin);

		if (phoneNo == null || smsPin == null) {
			log.warn("sendSMSPin: attempt to use service with emty parameters");
			//return Response.status(Response.Status.NOT_ACCEPTABLE).header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
			throw AppExceptionCode.MISC_MISSING_PARAMS_9998;
		}

		if (!AuthenticatedApplicationTokenRepository.verifyApplicationTokenId(applicationtokenid)) {
			log.warn("sendSMSPin - attempt to access from invalid application. applicationtokenid={}", applicationtokenid);
			//return Response.status(Response.Status.FORBIDDEN).entity(ILLEGAL_APPLICATION_FOR_THIS_SERVICE).header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
			throw AppExceptionCode.APP_ILLEGAL_7000;
		}
		
		log.trace("CommandSendSMSToUser - ({}, {}, {}, {}, {}, {}, {})", SMS_GW_SERVICE_URL, SMS_GW_SERVICE_ACCOUNT, SMS_GW_USERNAME, SMS_GW_PASSWORD, SMS_GW_QUERY_PARAM, phoneNo, smsPin);
		String response = new CommandSendSMSToUser(SMS_GW_SERVICE_URL, SMS_GW_SERVICE_ACCOUNT, SMS_GW_USERNAME, SMS_GW_PASSWORD, SMS_GW_QUERY_PARAM, phoneNo, smsPin).execute();
		log.debug("Answer from smsgw: " + response);
		ActivePinRepository.setPin(phoneNo, smsPin, response);


		
		return Response.ok("{\"result\": \"true\"}").header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();

	}

	/**
	 * @throws AppException
	 * @api {post} :applicationtokenid/generate_pin_and_send_sms_pin sendgenerateAndSendSMSPin
	 * @apiName sendgenerateAndSendSMSPin
	 * @apiGroup Security Token Service (STS)
	 * @apiDescription The backend for PIN signup processes
	 * @apiParam {String} phoneNo The phone number to which a pin-code is sent
	 * @apiSuccessExample Success-Response:
	 * HTTP/1.1 200 OK
	 * {
	 * "result": "true"
	 * }
	 * @apiError 403/7000 Application is invalid.
	 * @apiError 400/9998 Missing required parameters
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
	@Path("/{applicationtokenid}/generate_pin_and_send_sms_pin")
	@POST
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	@Produces(MediaType.APPLICATION_XML)
	public Response sendgenerateAndSendSMSPin(@PathParam("applicationtokenid") String applicationtokenid,
			@FormParam("phoneNo") String phoneNo, @FormParam("msg") String msg) throws AppException {
		log.info("sendgenerateAndSendSMSPin: phoneNo:" + phoneNo);

		
		
		if (isEmpty(phoneNo)) {
			log.warn("sendSMSPin: attempt to use service with emty parameters");
			//return Response.status(Response.Status.NOT_ACCEPTABLE).header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
			throw AppExceptionCode.MISC_MISSING_PARAMS_9998;
		}

		if (!AuthenticatedApplicationTokenRepository.verifyApplicationTokenId(applicationtokenid)) {
			log.warn("sendSMSPin - attempt to access from invalid application. applicationtokenid={}", applicationtokenid);
			//return Response.status(Response.Status.FORBIDDEN).entity(ILLEGAL_APPLICATION_FOR_THIS_SERVICE).header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
			throw AppExceptionCode.APP_ILLEGAL_7000;
		}
		
		String pinFound = ActivePinRepository.getPinSentIfAny(phoneNo);
		String smsPin = generatePin();
		boolean setPin = pinFound == null;
		if(pinFound!=null) {
			smsPin = pinFound;
		}
		if(msg==null || !msg.contains("{}")) {
			msg = smsPin;
		} else {
			msg = msg.replace("{}", smsPin);
		}
		
		String response = null;
		try{
			log.trace("CommandSendSMSToUser - ({}, {}, {}, {}, {}, {}, {})", SMS_GW_SERVICE_URL, SMS_GW_SERVICE_ACCOUNT, SMS_GW_USERNAME, SMS_GW_PASSWORD, SMS_GW_QUERY_PARAM, phoneNo, smsPin);
			response = new CommandSendSMSToUser(SMS_GW_SERVICE_URL, SMS_GW_SERVICE_ACCOUNT, SMS_GW_USERNAME, SMS_GW_PASSWORD, SMS_GW_QUERY_PARAM, phoneNo, msg).execute();
			log.trace("Answer from smsgw: " + response);
		} catch(Exception ex){
			ex.printStackTrace();
		}
		if(setPin) {
			ActivePinRepository.setPin(phoneNo, smsPin, response);
		}
		
		
		
		return Response.ok("{\"result\": \"true\"}").header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();

	}

	/**
	 * @throws AppException
	 * @api {post} :applicationtokenid/verify_phone_by_pin verifyPhoneByPin
	 * @apiName verifyPhoneByPin
	 * @apiGroup Security Token Service (STS)
	 * @apiDescription verify a one-time pin-code distributed to the user's registered cellPhone number
	 * @apiParam {String} appTokenXml Application Token XML.
	 * @apiParamExample {xml} Request-Example:
	 * &lt;applicationtoken&gt;
	 * &lt;params&gt;
	 * &lt;applicationtokenID&gt;1d58b70dc0fdc98b5cdce4745fb086c4&lt;/applicationtokenID&gt;
	 * &lt;applicationid&gt;101&lt;/applicationid&gt;
	 * &lt;applicationname&gt;Whydah-SystemTests&lt;/applicationname&gt;
	 * &lt;expires&gt;1480931112185&lt;/expires&gt;
	 * &lt;/params&gt;
	 * &lt;Url type="application/xml" method="POST" template="https://whydahdev.cantara.no/tokenservice/user/1d58b70dc0fdc98b5cdce4745fb086c4/get_usertoken_by_usertokenid"/&gt;
	 * &lt;/applicationtoken&gt;
	 * @apiParam {String} phoneno The phone number to which a pin-code is sent
	 * @apiParam {String} pin The pin-code is distributred to the registered cellPhone number
	 * @apiSuccessExample Success-Response:
	 * HTTP/1.1 200 OK
	 * {
	 * "result": "true"
	 * }
	 * @apiError 403/7000 Application is invalid.
	 * @apiError 406/6006 Invalid pin code.
	 * @apiError 400/9998 Missing required parameters
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
	@Path("/{applicationtokenid}/verify_phone_by_pin")
	@POST
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	@Produces(MediaType.APPLICATION_XML)
	public Response verifyPhoneByPin(@PathParam("applicationtokenid") String applicationtokenid,
			@FormParam("appTokenXml") String appTokenXml,
			@FormParam("phoneno") String phoneno,
			@FormParam("pin") String pin) throws AppException {

		log.trace("verifyPhoneByPin() called with " + "applicationtokenid = [" + applicationtokenid + "], appTokenXml = [" + appTokenXml + "], phoneno = [" + phoneno + "], pin = [" + pin + "]");

		if (isEmpty(appTokenXml) || isEmpty(pin) || isEmpty(phoneno)) {
			//return Response.status(Response.Status.BAD_REQUEST).entity("Missing required parameters").build();
			throw AppExceptionCode.MISC_MISSING_PARAMS_9998;
		}

		log.trace("verifyPhoneByPin: applicationtokenid={}, pin={}, appTokenXml={}", applicationtokenid, pin, appTokenXml);

		if (ApplicationMode.getApplicationMode().equals(ApplicationMode.DEV)) {
			return DevModeHelper.return_DEV_MODE_ExampleUserToken(1);
		}

		// Verify calling application
		if (!UserTokenFactory.verifyApplicationToken(applicationtokenid, appTokenXml)) {
			log.warn("verifyPhoneByPin - attempt to access from invalid application. applicationtokenid={}", applicationtokenid);
			//return Response.status(Response.Status.FORBIDDEN).entity(ILLEGAL_APPLICATION_FOR_THIS_SERVICE).header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
			throw AppExceptionCode.APP_ILLEGAL_7000;
		}

		if (ActivePinRepository.usePin(phoneno, pin)) {
			return Response.ok("{\"result\": \"true\"}").build();
		} else {
			//return Response.status(Response.Status.NOT_ACCEPTABLE).build();
			throw AppExceptionCode.USER_INVALID_PINCODE_6006;
		}
	}


	/**
	 * @apiIgnore The backend for sms messages to the user
	 * @api {post} :applicationtokenid/send_sms sendSMSMessage
	 * @apiName sendSMSMessage
	 * @apiGroup Security Token Service (STS)
	 * @apiDescription The backend for sms messages to the user
	 * @apiParam {String} phoneNo The cellPhone to send message to
	 * @apiParam {String} smsMessage The message to send to the user
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
	@Path("/{applicationtokenid}/send_sms")
	@POST
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	@Produces(MediaType.APPLICATION_XML)
	public Response sendSMSMessage(@PathParam("applicationtokenid") String applicationtokenid,
			@FormParam("phoneNo") String phoneNo,
			@FormParam("smsMessage") String smsMessage) throws AppException {
		log.info("Response sendSMSMessage: phoneNo:{}, smsMessage:{}", phoneNo, smsMessage);

		if (!AuthenticatedApplicationTokenRepository.verifyApplicationTokenId(applicationtokenid)) {
			log.warn("sendSMSMessage - attempt to access from invalid application. applicationtokenid={}", applicationtokenid);
			//return Response.status(Response.Status.FORBIDDEN).entity(ILLEGAL_APPLICATION_FOR_THIS_SERVICE).header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
			throw AppExceptionCode.APP_ILLEGAL_7000;
		}
		String cellNo = phoneNo;
		new CommandSendSMSToUser(SMS_GW_SERVICE_URL, SMS_GW_SERVICE_ACCOUNT, SMS_GW_USERNAME, SMS_GW_PASSWORD, SMS_GW_QUERY_PARAM, cellNo, smsMessage).execute();
		return Response.ok("{\"result\": \"true\"}").header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();

	}

	/**
	 * @apiIgnore The backend for scheduling sms messages sent to the user
	 * @api {post} :applicationtokenid/send_scheduled_sms sendScheduledSMSMessage
	 * @apiName sendScheduledSMSMessage
	 * @apiGroup Security Token Service (STS)
	 * @apiDescription The backend for sms messages to the user
	 * @apiParam {String} phoneNo The cellPhone to send message to
	 * @apiParam {String} smsMessage The message to send to the user
	 * @apiParam {String} timestamp The timestamp of when to send the message
	 * @apiSuccessExample Success-Response:
	 * HTTP/1.1 200 OK
	 * {
	 * "result": "true"
	 * }
	 * @apiError 403/7000 Application is invalid.
	 * @apiError 400/9998 Missing required parameters
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
	@Path("/{applicationtokenid}/send_scheduled_sms")
	@POST
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	@Produces(MediaType.APPLICATION_XML)
	public Response sendScheduledSMSMessage(@PathParam("applicationtokenid") String applicationtokenid,
			@FormParam("timestamp") String timestamp,
			@FormParam("phoneNo") String phoneNo,
			@FormParam("smsMessage") String smsMessage) {
		log.info("Response sendScheduledSMSMessage: timestamp:{}, phoneNo:{}, smsMessage:{}", timestamp, phoneNo, smsMessage);

		if (!AuthenticatedApplicationTokenRepository.verifyApplicationTokenId(applicationtokenid)) {
			log.warn("sendScheduledSMSMessage - attempt to access from invalid application. applicationtokenid={}", applicationtokenid);
			return Response.status(Response.Status.FORBIDDEN).entity(ILLEGAL_APPLICATION_FOR_THIS_SERVICE).header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
		}
		String cellNo = phoneNo;
		new DelayedSendSMSTask(Long.parseLong(timestamp), SMS_GW_SERVICE_URL, SMS_GW_SERVICE_ACCOUNT, SMS_GW_USERNAME, SMS_GW_PASSWORD, SMS_GW_QUERY_PARAM, cellNo, smsMessage);
		return Response.ok().header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();

	}

	/**
	 * @throws AppException
	 * @api {post} :applicationtokenid/:userticket/create_user createAndLogOnUser
	 * @apiName createAndLogOnUser
	 * @apiGroup Security Token Service (STS)
	 * @apiDescription The SSOLoginWebApplication backend for 3rd party UserTokens. Receive a new user, create a Whydah UserIdentity with
	 * the corresponding defaultroles (UAS|UIB) and create a new session with a one-time userticket for handover to receiving
	 * SSO applications
	 * @apiParam {String} apptoken Application Token XML.
	 * @apiParamExample {xml} Request-Example:
	 * &lt;applicationtoken&gt;
	 * &lt;params&gt;
	 * &lt;applicationtokenID&gt;1d58b70dc0fdc98b5cdce4745fb086c4&lt;/applicationtokenID&gt;
	 * &lt;applicationid&gt;101&lt;/applicationid&gt;
	 * &lt;applicationname&gt;Whydah-SystemTests&lt;/applicationname&gt;
	 * &lt;expires&gt;1480931112185&lt;/expires&gt;
	 * &lt;/params&gt;
	 * &lt;Url type="application/xml" method="POST" template="https://whydahdev.cantara.no/tokenservice/user/1d58b70dc0fdc98b5cdce4745fb086c4/get_usertoken_by_usertokenid"/&gt;
	 * &lt;/applicationtoken&gt;
	 * @apiParam {String} usercredential user credential  i.e. (username and password)
	 * @apiParamExample {xml} Request-Example:
	 * &lt;?xml version="1.0" encoding="UTF-8" standalone="yes"?&gt;
	 * &lt;usercredential&gt;
	 * &lt;params&gt;
	 * &lt;username&gt;YOUR_USER_NAME&lt;/username&gt;
	 * &lt;password&gt;YOUR_PASSWORD&lt;/password&gt;
	 * &lt;/params&gt;
	 * &lt;/usercredential&gt;
	 * @apiParam {String} fbuser The typical facebook user-sts or other oauth2 usertoken
	 * @apiSuccessExample Success-Response:
	 * HTTP/1.1 200 OK
	 * &lt;?xml version="1.0" encoding="UTF-8" standalone="yes"?&gt;
	 * &lt;usertoken xmlns:ns2="http://www.w3.org/1999/xhtml" id="16987d1a-f305-4a98-a3dc-2ce9a97e8424"&gt;
	 * &lt;uid&gt;systest&lt;/uid&gt;
	 * &lt;timestamp&gt;1480935597694&lt;/timestamp&gt;
	 * &lt;lifespan&gt;1209600000&lt;/lifespan&gt;
	 * &lt;issuer&gt;https://whydahdev.cantara.no/tokenservice/user/0700445a583e014102affd92c8d896a0/validate_usertokenid/16987d1a-f305-4a98-a3dc-2ce9a97e8424&lt;/issuer&gt;
	 * &lt;securitylevel&gt;1&lt;/securitylevel&gt;
	 * &lt;DEFCON&gt;&lt;/DEFCON&gt;
	 * &lt;username&gt;systest&lt;/username&gt;
	 * &lt;firstname&gt;SystemTestUser&lt;/firstname&gt;
	 * &lt;lastname&gt;UserAdminWebApp&lt;/lastname&gt;
	 * &lt;cellphone&gt;87654321&lt;/cellphone&gt;
	 * &lt;email&gt;whydahadmin@getwhydah.com&lt;/email&gt;
	 * &lt;personref&gt;42&lt;/personref&gt;
	 * &lt;application ID="101"&gt;
	 * &lt;applicationName&gt;ACSResource&lt;/applicationName&gt;
	 * &lt;organizationName&gt;Opplysningen 1881&lt;/organizationName&gt;
	 * &lt;role name="INNDATA" value="MY_ADDRESS_JSON"/&gt;
	 * &lt;/application&gt;
	 * &lt;ns2:link type="application/xml" href="https://whydahdev.cantara.no/tokenservice/user/0700445a583e014102affd92c8d896a0/validate_usertokenid/16987d1a-f305-4a98-a3dc-2ce9a97e8424" rel="self"/&gt;
	 * &lt;hash type="MD5"&gt;57755c9efa6337dc9739fe5cb719a9b4&lt;/hash&gt;
	 * &lt;/usertoken&gt;
	 * @apiError 403/7000 Application is invalid.
	 * @apiError 403/6000 Authentication failed.
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
	@Path("/{applicationtokenid}/{userticket}/create_user")
	@POST
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	@Produces(MediaType.APPLICATION_XML)
	public Response createAndLogOnUser(@PathParam("applicationtokenid") String applicationtokenid,
			@PathParam("userticket") String userticket,
			@FormParam("apptoken") String appTokenXml,
			@FormParam("usercredential") String userCredentialXml,
			@FormParam("userxml") String userxml) throws AppException {
		log.trace("Response createAndLogOnUser: usercredential:" + userCredentialXml + "thirdpartyuser:" + userxml);

		if (ApplicationMode.getApplicationMode() == ApplicationMode.DEV) {
			return DevModeHelper.return_DEV_MODE_ExampleUserToken(1);
		}

		if (!UserTokenFactory.verifyApplicationToken(applicationtokenid, appTokenXml)) {
			// TODO:  Limit this operation to SSOLoginWebApplication ONLY
			log.warn("createAndLogOnUser - attempt to access from invalid application. applicationtokenid={}", applicationtokenid);
			//return Response.status(Response.Status.FORBIDDEN).entity("Application authentication not valid.").header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
			throw AppExceptionCode.APP_ILLEGAL_7000;
		}

		try {
			//applicationtokenidmap.put(applicationtokenid, applicationtokenid);
			UserToken userToken = userAuthenticator.createAndLogonUser(applicationtokenid, appTokenXml, userCredentialXml, userxml);
			userticketmap.put(userticket, userToken.getUserTokenId());
			userToken.setDefcon(ThreatResource.getDEFCON());
			userToken.setNs2link(appConfig.getProperty("myuri") + "user/" + applicationtokenid + "/validate_usertokenid/" + userToken.getUserTokenId());
			// Report to statistics
			ObservedActivity observedActivity = new UserSessionObservedActivity(userToken.getUserName(), "userCreated", applicationtokenid);
			MonitorReporter.reportActivity(observedActivity);
			return createUserTokenResponse(applicationtokenid, userToken);
		} catch (AuthenticationFailedException ae) {
			log.warn("createAndLogOnUser - Error creating or authenticating user. thirdPartyUserTokenXml={}", userxml);
			//return Response.status(Response.Status.FORBIDDEN).entity("Error creating or authenticating user.").header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
			throw AppExceptionCode.USER_AUTHENTICATION_FAILED_6000.setDeveloperMessage(ae.getMessage());
		}
	}

	/**
	 * @throws AppException
	 * @api {post} :applicationtokenid/:userticket/:pin/create_pinverified_user createAndLogOnPinUser
	 * @apiName createAndLogOnPinUser
	 * @apiGroup Security Token Service (STS)
	 * @apiDescription The SSOLoginWebApplication backend for 3rd party UserTokens. Receive a new user, create a Whydah UserIdentity with
	 * the corresponding defaultroles (UAS|UIB) and create a new session with a one-time userticket for handover to receiving
	 * SSO applications
	 * @apiParam {String} apptoken Application Token XML.
	 * @apiParamExample {xml} Request-Example:
	 * &lt;applicationtoken&gt;
	 * &lt;params&gt;
	 * &lt;applicationtokenID&gt;1d58b70dc0fdc98b5cdce4745fb086c4&lt;/applicationtokenID&gt;
	 * &lt;applicationid&gt;101&lt;/applicationid&gt;
	 * &lt;applicationname&gt;Whydah-SystemTests&lt;/applicationname&gt;
	 * &lt;expires&gt;1480931112185&lt;/expires&gt;
	 * &lt;/params&gt;
	 * &lt;Url type="application/xml" method="POST" template="https://whydahdev.cantara.no/tokenservice/user/1d58b70dc0fdc98b5cdce4745fb086c4/get_usertoken_by_usertokenid"/&gt;
	 * &lt;/applicationtoken&gt;
	 * @apiParam {String} adminUserTokenId The userTokenId
	 * @apiParam {String} cellPhone The phone number to which the pin code was sent
	 * @apiParam {String} jsonuser a simple userjson for new user
	 * @apiSuccessExample Success-Response:
	 * HTTP/1.1 200 OK
	 * &lt;?xml version="1.0" encoding="UTF-8" standalone="yes"?&gt;
	 * &lt;usertoken xmlns:ns2="http://www.w3.org/1999/xhtml" id="16987d1a-f305-4a98-a3dc-2ce9a97e8424"&gt;
	 * &lt;uid&gt;systest&lt;/uid&gt;
	 * &lt;timestamp&gt;1480935597694&lt;/timestamp&gt;
	 * &lt;lifespan&gt;1209600000&lt;/lifespan&gt;
	 * &lt;issuer&gt;https://whydahdev.cantara.no/tokenservice/user/0700445a583e014102affd92c8d896a0/validate_usertokenid/16987d1a-f305-4a98-a3dc-2ce9a97e8424&lt;/issuer&gt;
	 * &lt;securitylevel&gt;1&lt;/securitylevel&gt;
	 * &lt;DEFCON&gt;&lt;/DEFCON&gt;
	 * &lt;username&gt;systest&lt;/username&gt;
	 * &lt;firstname&gt;SystemTestUser&lt;/firstname&gt;
	 * &lt;lastname&gt;UserAdminWebApp&lt;/lastname&gt;
	 * &lt;cellphone&gt;87654321&lt;/cellphone&gt;
	 * &lt;email&gt;whydahadmin@getwhydah.com&lt;/email&gt;
	 * &lt;personref&gt;42&lt;/personref&gt;
	 * &lt;application ID="101"&gt;
	 * &lt;applicationName&gt;ACSResource&lt;/applicationName&gt;
	 * &lt;organizationName&gt;Opplysningen 1881&lt;/organizationName&gt;
	 * &lt;role name="INNDATA" value="MY_ADDRESS_JSON"/&gt;
	 * &lt;/application&gt;
	 * &lt;ns2:link type="application/xml" href="https://whydahdev.cantara.no/tokenservice/user/0700445a583e014102affd92c8d896a0/validate_usertokenid/16987d1a-f305-4a98-a3dc-2ce9a97e8424" rel="self"/&gt;
	 * &lt;hash type="MD5"&gt;57755c9efa6337dc9739fe5cb719a9b4&lt;/hash&gt;
	 * &lt;/usertoken&gt;
	 * @apiError 403/7000 Application is invalid.
	 * @apiError 400/9998 Missing required parameters
	 * @apiError 403/6000 Authentication failed.
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
	@Path("/{applicationtokenid}/{userticket}/{pin}/create_pinverified_user")
	@POST
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	@Produces(MediaType.APPLICATION_XML)
	public Response createAndLogOnPinUser(@PathParam("applicationtokenid") String applicationtokenid,
			@PathParam("userticket") String userticket,
			@PathParam("pin") String pin,
			@FormParam("apptoken") String appTokenXml,
			@FormParam("adminUserTokenId") String adminUserTokenId,
			@FormParam("cellPhone") String cellPhone,
			@FormParam("jsonuser") String newUserjson) throws AppException {
		log.info("Request createAndLogOnPinUser:  jsonuser:" + newUserjson);

		if (ApplicationMode.getApplicationMode() == ApplicationMode.DEV) {
			return DevModeHelper.return_DEV_MODE_ExampleUserToken(1);
		}

		if (isEmpty(adminUserTokenId) || isEmpty(cellPhone) || isEmpty(newUserjson)) {
			throw AppExceptionCode.MISC_MISSING_PARAMS_9998;
		}

		if (pin == null || pin.length() < 4) {
			pin = generatePin();
			log.info("createAndLogOnPinUser - empty pin in request, generating internal pin and use it");
		}
		if (!UserTokenFactory.verifyApplicationToken(applicationtokenid, appTokenXml)) {
			// TODO:  Limit this operation to SSOLoginWebApplication ONLY
			log.warn("createAndLogOnPinUser - attempt to access from invalid application. applicationtokenid={}", applicationtokenid);
			//return Response.status(Response.Status.FORBIDDEN).entity("Application authentication not valid.").header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
			throw AppExceptionCode.APP_ILLEGAL_7000;
		}
		try {
			UserToken userToken = userAuthenticator.createAndLogonPinUser(applicationtokenid, appTokenXml, adminUserTokenId, cellPhone, pin, newUserjson);
			userticketmap.put(userticket, userToken.getUserTokenId());
			log.debug("createAndLogOnPinUser Added ticket:{} for usertoken:{} username: {}", userticket, userToken.getUserTokenId(), userToken.getUserName());
			userToken.setDefcon(ThreatResource.getDEFCON());
			userToken.setNs2link(appConfig.getProperty("myuri") + "user/" + applicationtokenid + "/validate_usertokenid/" + userToken.getUserTokenId());
			// Report to statistics
			ObservedActivity observedActivity = new UserSessionObservedActivity(userToken.getUserName(), "userCreated", applicationtokenid);
			MonitorReporter.reportActivity(observedActivity);
			return createUserTokenResponse(applicationtokenid, userToken);
		} catch (AuthenticationFailedException ae) {
			log.warn(String.format("createAndLogOnPinUser - Error creating or authenticating user. jsonuser=%s", newUserjson), ae);
			//return Response.status(Response.Status.FORBIDDEN).entity("Error creating or authenticating user.").header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
			throw AppExceptionCode.USER_AUTHENTICATION_FAILED_6000;
		}
	}

	private Response createUserTokenResponse(@PathParam("applicationtokenid") String applicationtokenid, UserToken userToken) {
		log.debug("getUserTokenByUserTokenId OK. Response={}", userToken.toString());
		userToken.setNs2link(appConfig.getProperty("myuri") + "user/" + applicationtokenid + "/validate_usertokenid/" + userToken.getUserTokenId());
		userToken.setLastSeen(AuthenticatedUserTokenRepository.getLastSeen(userToken));
		userToken.setDefcon(ThreatResource.getDEFCON());
		UserToken filteredUserToken = UserTokenFactory.getFilteredUserToken(applicationtokenid, userToken);
		AuthenticatedUserTokenRepository.setLastSeen(filteredUserToken);
		Map<String, Object> model = new HashMap();
		model.put("it", filteredUserToken);
		model.put("DEFCON", filteredUserToken.getDefcon());
		log.debug("Response (it):{}", filteredUserToken);
		return Response.ok(new Viewable("/usertoken.ftl", model)).header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
	}

	boolean isEmpty(String userticket) {
		boolean isEmpty = false;
		if (userticket == null || userticket.isEmpty()) {
			isEmpty = true;
		}
		return isEmpty;
	}

	public static String generatePin() {
		int i = generator.nextInt(10000) % 10000;
		java.text.DecimalFormat f = new java.text.DecimalFormat("0000");
		return f.format(i);

	}

	@Path("/{applicationtokenid}/isUserNameFoundInPinMap")
	@POST
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	@Produces(MediaType.APPLICATION_XML)
	public Response isUserNameFoundInPinMap(@PathParam("applicationtokenid") String applicationtokenid, @FormParam("adminUserTokenId") String adminUserTokenId,
			@FormParam("phoneno") String phoneno) throws AppException {

		log.trace("isUserNameFoundInPinMap() called with " + "applicationtokenid = [" + applicationtokenid + "], phoneno = [" + phoneno + "]");

		if (isEmpty(phoneno)) {
			throw AppExceptionCode.MISC_MISSING_PARAMS_9998;
		}

		log.trace("verifyPhoneByPin: applicationtokenid={}, phone={}", applicationtokenid, phoneno);

		if (ApplicationMode.getApplicationMode().equals(ApplicationMode.DEV)) {
			return DevModeHelper.return_DEV_MODE_ExampleUserToken(1);
		}

		// Verify calling application
		if (!AuthenticatedApplicationTokenRepository.verifyApplicationTokenId(applicationtokenid)) {
			log.warn("verifyPhoneByPin - attempt to access from invalid application. applicationtokenid={}", applicationtokenid);
			//return Response.status(Response.Status.FORBIDDEN).entity(ILLEGAL_APPLICATION_FOR_THIS_SERVICE).header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
			throw AppExceptionCode.APP_ILLEGAL_7000;
		}

		UserToken admin = AuthenticatedUserTokenRepository.getUserToken(adminUserTokenId, applicationtokenid);
		if (admin != null && admin.getUserName().equals(appConfig.getProperty("whydah.adminuser.username"))) {
			if (ActivePinRepository.getPinMap().containsKey(phoneno)) {
				return Response.ok("{\"result\": \"true\"}").build();
			} else {
				return Response.ok("{\"result\": \"false\"}").build();
			}
		} else {
			throw AppExceptionCode.USER_AUTHENTICATION_FAILED_6000;
		}


	}

	@Path("/{applicationtokenid}/getSMSLog")
	@POST
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	@Produces(MediaType.TEXT_PLAIN)
	public Response getSMSLog(@PathParam("applicationtokenid") String applicationtokenid,
			@FormParam("adminUserTokenId") String adminUserTokenId,
			@FormParam("phoneno") String phoneno) throws AppException {

		log.trace("getSMSLog() called with " + "applicationtokenid = [" + applicationtokenid + "], phoneno = [" + phoneno + "]");

		if (isEmpty(phoneno)) {
			throw AppExceptionCode.MISC_MISSING_PARAMS_9998;
		}

		log.trace("verifyPhoneByPin: applicationtokenid={}, phone={}", applicationtokenid, phoneno);

		if (ApplicationMode.getApplicationMode().equals(ApplicationMode.DEV)) {
			return DevModeHelper.return_DEV_MODE_ExampleUserToken(1);
		}

		// Verify calling application
		if (!AuthenticatedApplicationTokenRepository.verifyApplicationTokenId(applicationtokenid)) {
			log.warn("verifyPhoneByPin - attempt to access from invalid application. applicationtokenid={}", applicationtokenid);
			//return Response.status(Response.Status.FORBIDDEN).entity(ILLEGAL_APPLICATION_FOR_THIS_SERVICE).header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
			throw AppExceptionCode.APP_ILLEGAL_7000;
		}

		UserToken admin = AuthenticatedUserTokenRepository.getUserToken(adminUserTokenId, applicationtokenid);
		if (admin != null && admin.getUserName().equals(appConfig.getProperty("whydah.adminuser.username"))) {
			if (ActivePinRepository.getSMSResponseLogMap().containsKey(phoneno)) {
				return Response.ok(ActivePinRepository.getSMSResponseLogMap().get(phoneno)).build();
			} else {
				return Response.ok("").build();
			}
		} else {
			throw AppExceptionCode.USER_AUTHENTICATION_FAILED_6000;
		}

	}

	@Path("/{applicationtokenid}/getPin")
	@POST
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	@Produces(MediaType.APPLICATION_XML)
	public Response getPin(@PathParam("applicationtokenid") String applicationtokenid,
			@FormParam("adminUserTokenId") String adminUserTokenId,
			@FormParam("phoneno") String phoneno) throws AppException {

		log.trace("getPin() called with " + "applicationtokenid = [" + applicationtokenid + "], phoneno = [" + phoneno + "], adminUserTokenId = [" + adminUserTokenId + "]");

		if (isEmpty(phoneno)) {
			//return Response.status(Response.Status.BAD_REQUEST).entity("Missing required parameters").build();
			throw AppExceptionCode.MISC_MISSING_PARAMS_9998;
		}


		if (ApplicationMode.getApplicationMode().equals(ApplicationMode.DEV)) {
			return DevModeHelper.return_DEV_MODE_ExampleUserToken(1);
		}

		// Verify calling application
		if (!AuthenticatedApplicationTokenRepository.verifyApplicationTokenId(applicationtokenid)) {
			log.warn("getUserTokenByUserTicket - attempt to access from invalid application. applicationtokenid={}", applicationtokenid);
			//return Response.status(Response.Status.FORBIDDEN).entity(ILLEGAL_APPLICATION_FOR_THIS_SERVICE).header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
			throw AppExceptionCode.APP_ILLEGAL_7000;
		}

		try {

			UserToken admin = AuthenticatedUserTokenRepository.getUserToken(adminUserTokenId, applicationtokenid);
			if (admin != null && admin.getUserName().equals(appConfig.getProperty("whydah.adminuser.username"))) {
				String pin = ActivePinRepository.getPinSentIfAny(phoneno);
				if (pin == null) {
					pin = "";
				}
				return Response.ok(pin).build();

			} else {
				throw AppExceptionCode.USER_AUTHENTICATION_FAILED_6000;
			}


		} catch (AuthenticationFailedException ae) {
			log.warn("getUserToken - User authentication failed");
			//return Response.status(Response.Status.NOT_ACCEPTABLE).header(ACCESS_CONTROL_ALLOW_ORIGIN, "*").header(ACCESS_CONTROL_ALLOW_METHODS, GET_POST_DELETE_PUT).build();
			throw AppExceptionCode.USER_LOGIN_PIN_FAILED_6004.setDeveloperMessage(ae.getMessage());
		}


	}
}
