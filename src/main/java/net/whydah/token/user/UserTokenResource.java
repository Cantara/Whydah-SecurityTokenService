package net.whydah.token.user;

import com.google.inject.Inject;
import com.hazelcast.config.Config;
import com.hazelcast.config.XmlConfigBuilder;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import net.whydah.token.application.ApplicationThreatResource;
import net.whydah.token.application.AuthenticatedApplicationRepository;
import net.whydah.token.config.AppConfig;
import net.whydah.token.config.ApplicationMode;
import net.whydah.token.config.SSLTool;
import org.glassfish.jersey.server.mvc.Viewable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.io.FileNotFoundException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

@Path("/user")
public class UserTokenResource {
    private final static Logger log = LoggerFactory.getLogger(UserTokenResource.class);

    private static Map userticketmap = new HashMap();
    private static Map applicationtokenidmap = new HashMap();

    static {

        AppConfig appConfig = new AppConfig();

        // Property-overwrite of SSL verification to support weak ssl certificates
        if ("disabled".equalsIgnoreCase(appConfig.getProperty("sslverification"))) {
            SSLTool.disableCertificateValidation();

        }
        String xmlFileName = System.getProperty("hazelcast.config");
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
        HazelcastInstance hazelcastInstance = Hazelcast.newHazelcastInstance(hazelcastConfig);
        userticketmap = hazelcastInstance.getMap(appConfig.getProperty("gridprefix") + "userticketmap");
        log.info("Connectiong to map {}", appConfig.getProperty("gridprefix") + "userticketmap");
        applicationtokenidmap = hazelcastInstance.getMap(appConfig.getProperty("gridprefix") + "applicationtokenidmap");
        log.info("Connectiong to map {}", appConfig.getProperty("gridprefix") + "applicationtokenidmap");


    }

    public static void initializeDistributedMap() {
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
     * TODO baardl: rename the param apptoken
     *
     * @param applicationtokenid  application session
     * @param appTokenXml   application session data
     * @param userCredentialXml user credentials i.e. (username / password)
     * @return userToken - user session data
     */
    @Path("/{applicationtokenid}/usertoken")
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_XML)
    public Response getUserToken(@PathParam("applicationtokenid") String applicationtokenid,
                                 @FormParam("apptoken") String appTokenXml,
                                 @FormParam("usercredential") String userCredentialXml) {
        if (ApplicationMode.getApplicationMode().equals(ApplicationMode.DEV)) {
            return DevModeHelper.return_DEV_MODE_ExampleUserToken(1);
        }
        if (!UserTokenFactory.verifyApplicationToken(applicationtokenid, appTokenXml)) {
            log.warn("getUserToken - attempt to access from invalid application. applicationtokenid={}", applicationtokenid);
            return Response.status(Response.Status.FORBIDDEN).entity("Application authentication not valid.").build();
        }
        try {
            UserToken userToken = userAuthenticator.logonUser(applicationtokenid, appTokenXml, userCredentialXml);
            userToken.setNs2link(appConfig.getProperty("myuri") + "user/" + applicationtokenid + "/validate_usertokenid/" + userToken.getTokenid());
            userToken.setDefcon(ApplicationThreatResource.getDEFCON());
            return Response.ok(new Viewable("/usertoken.ftl", UserTokenFactory.getFilteredUserToken(applicationtokenid, userToken))).build();
        } catch (AuthenticationFailedException ae) {
            log.warn("getUserToken - User authentication failed");
            return Response.status(Response.Status.FORBIDDEN).entity("User authentication failed").build();
        }
    }

    /**
     * Login in user by his/her usercredentials and register its ticket in the ticket-map for session handover
     *
     * @param applicationtokenid  application session
     * @param userticket  user session handover ticket
     * @param appTokenXml   application session data
     * @param userCredentialXml user credentials i.e. (username / password)
     * @return user session data
     */
    @Path("/{applicationtokenid}/{userticket}/usertoken")
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_XML)
    public Response getUserTokenAndStoreUserTicket(@PathParam("applicationtokenid") String applicationtokenid,
                                                   @PathParam("userticket") String userticket,
                                                   @FormParam("apptoken") String appTokenXml,
                                                   @FormParam("usercredential") String userCredentialXml) {
        if (ApplicationMode.getApplicationMode().equals(ApplicationMode.DEV)) {
            return DevModeHelper.return_DEV_MODE_ExampleUserToken(1);
        }

        if (!UserTokenFactory.verifyApplicationToken(applicationtokenid, appTokenXml)) {
            log.warn("getUserTokenAndStoreUserTicket - attempt to access from invalid application. applicationtokenid={}", applicationtokenid);
            return Response.status(Response.Status.FORBIDDEN).entity("Application authentication not valid.").build();
        }
        try {
            UserToken userToken = userAuthenticator.logonUser(applicationtokenid, appTokenXml, userCredentialXml);

            // Add the user to the ticket-map with the ticket given from the caller
            userticketmap.put(userticket, userToken.getTokenid());
            return createUserTokenResponse(applicationtokenid, userToken);
        } catch (AuthenticationFailedException ae) {
            log.warn("getUserTokenAndStoreUserTicket - User authentication failed");
            return Response.status(Response.Status.FORBIDDEN).entity("User authentication failed").build();
        }
    }


    /**
     * Verify that a usertoken and a user session is still valid. Usually used for application re-entries and before allowing
     * a user important and critical processes like monetary transactions
     *
     * @param applicationtokenid - application session id
     * @param userTokenXml  - user session data
     * @return - OK if valid user session exists based upon user session data
     */
    @Path("/{applicationtokenid}/validate_usertoken")
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response validateUserTokenXML(@PathParam("applicationtokenid") String applicationtokenid, @FormParam("usertoken") String userTokenXml) {
        if (!AuthenticatedApplicationRepository.verifyApplicationTokenId(applicationtokenid)) {
            log.warn("validateUserTokenXML - attempt to access from invalid application. applicationtokenid={}", applicationtokenid);
            return Response.status(Response.Status.FORBIDDEN).entity("Application authentication not valid.").build();
        }
        //UserToken userToken = UserToken.createFromUserTokenXML(userTokenXml);
        UserToken userToken = UserTokenFactory.fromXml(userTokenXml);
        if (ActiveUserTokenRepository.verifyUserToken(userToken)) {
            return Response.ok().build();
        }
        log.warn("validateUserTokenXML failed for usertoken {}", userTokenXml);
        return Response.status(Response.Status.UNAUTHORIZED).build();
    }

    @Path("/{applicationtokenid}/validate_usertokenid/{usertokenid}")
    @GET
    public Response validateUserTokenID(@PathParam("applicationtokenid") String applicationtokenid, @PathParam("usertokenid") String usertokenid) {
        if (!AuthenticatedApplicationRepository.verifyApplicationTokenId(applicationtokenid)) {
            log.warn("validateUserTokenXML - attempt to access from invalid application. applicationtokenid={}", applicationtokenid);
            return Response.status(Response.Status.FORBIDDEN).entity("Application authentication not valid.").build();
        }
        if (ActiveUserTokenRepository.getUserToken(usertokenid,applicationtokenid) != null) {
            log.trace("Verified {}", usertokenid);
            return Response.ok().build();
        }
        log.warn("Usertoken not ok: {}", usertokenid);
        return Response.status(Response.Status.UNAUTHORIZED).build();
    }

    /**
     * Used to create a userticket for a user to transfer a session between whydah SSO apps
     *
     * @param applicationtokenid  application session
     * @param appTokenXml   application session data
     * @param userticket  user session handover ticket
     * @param userTokenId  user session id
     * @return userticket - user session handover ticket
     */
    @Path("/{applicationtokenid}/create_userticket_by_usertokenid")
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response createUserTicketByUserTokenId(@PathParam("applicationtokenid") String applicationtokenid,
                                                  @FormParam("apptoken") String appTokenXml,
                                                  @FormParam("userticket") String userticket,
                                                  @FormParam("usertokenid") String userTokenId) {
        log.trace("createUserTicketByUserTokenId: applicationtokenid={}, userticket={}, usertokenid={}, appTokenXml={}", applicationtokenid, userticket, userTokenId, appTokenXml);


        if (!UserTokenFactory.verifyApplicationToken(applicationtokenid, appTokenXml)) {
            log.warn("createUserTicketByUserTokenId - attempt to access from invalid application. applicationtokenid={}", applicationtokenid);
            return Response.status(Response.Status.FORBIDDEN).entity("Illegal application for this service").build();
        }
        final UserToken userToken = ActiveUserTokenRepository.getUserToken(userTokenId,applicationtokenid);
        if (userToken != null) {
            userticketmap.put(userticket, userToken.getTokenid());
            log.trace("createUserTicketByUserTokenId OK. Response={}", userToken.toString());
            return createUserTokenResponse(applicationtokenid, userToken);
        }
        log.warn("createUserTicketByUserTokenId - attempt to access with non acceptable usertokenid {}", userTokenId);
        return Response.status(Response.Status.NOT_ACCEPTABLE).build();
    }


    /**
     * Used to get the usertoken from a usertokenid, which the application usually stores in its secure cookie
     *
     * @param applicationtokenid  application session
     * @param appTokenXml   application session data
     * @param userTokenId  user session id
     * @return usertoken
     */
    @Path("/{applicationtokenid}/get_usertoken_by_usertokenid")
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_XML)
    public Response getUserTokenByUserTokenId(@PathParam("applicationtokenid") String applicationtokenid,
                                              @FormParam("apptoken") String appTokenXml,
                                              @FormParam("usertokenid") String userTokenId) {
        log.trace("getUserTokenByUserTokenId: applicationtokenid={}, usertokenid={}, appTokenXml={}", applicationtokenid, userTokenId, appTokenXml);

        if (ApplicationMode.getApplicationMode().equals(ApplicationMode.DEV)) {
            return DevModeHelper.return_DEV_MODE_ExampleUserToken(1);
        }

        if (!UserTokenFactory.verifyApplicationToken(applicationtokenid, appTokenXml)) {
            log.warn("getUserTokenByUserTokenId - attempt to access from invalid application. applicationtokenid={}", applicationtokenid);
            return Response.status(Response.Status.FORBIDDEN).entity("Illegal application for this service").build();
        }
        final UserToken userToken = ActiveUserTokenRepository.getUserToken(userTokenId,applicationtokenid);
        if (userToken == null) {
            log.warn("getUserTokenByUserTokenId - attempt to access with non acceptable usertokenid={}", userTokenId);
            return Response.status(Response.Status.NOT_ACCEPTABLE).build();
        }
        log.info("getUserTokenByUserTokenId - valid session found for {} ",userTokenId);
        return createUserTokenResponse(applicationtokenid, userToken);
    }

    /**
     * Used to get the lase seend time, which the application usually stores in its secure cookie
     *
     * @param applicationtokenid  application session
     * @param userEmail  email of user we try to locate
     * @return last seen as String
     */
    @Path("/{applicationtokenid}/{email}/last_seen")
    @GET
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_PLAIN)
    public Response getLastSeenByUserTokenId(@PathParam("applicationtokenid") String applicationtokenid,
                                              @PathParam("email") String userEmail) {
        log.trace("getLastSeenByUserTokenId: applicationtokenid={}, email={}", applicationtokenid, userEmail);

        if (ApplicationMode.getApplicationMode().equals(ApplicationMode.DEV)) {
            return DevModeHelper.return_DEV_MODE_ExampleUserToken(1);
        }

        if (!UserTokenFactory.verifyApplicationToken(applicationtokenid, "")) {
            log.warn("getLastSeenByUserTokenId - attempt to access from invalid application. applicationtokenid={}", applicationtokenid);
            return Response.status(Response.Status.FORBIDDEN).entity("Illegal application for this service").build();
        }
        String lastSeen = ActiveUserTokenRepository.getLastSeenByEmail(userEmail);
        if (lastSeen == null) {
            log.warn("getLastSeenByUserTokenId - attempt to access with non acceptable userEmail={}", userEmail);
            return Response.status(Response.Status.NOT_ACCEPTABLE).build();
        }
        log.info("getLastSeenByUserTokenId - valid session found for {} ",userEmail);
        return Response.status(Response.Status.OK).entity(userEmail).build();
    }



    /**
     * Lookup a user by a one-time userticket, usually the first thing we do after receiving a SSO redirect back to
     * an application from SSOLoginWebApplication
     *
     * @param applicationtokenid  application session
     * @param appTokenXml   application session data
     * @param userticket  user session handover ticket
     * @return usertoken
     */
    @Path("/{applicationtokenid}/get_usertoken_by_userticket")
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_XML)
    public Response getUserTokenByUserTicket(@PathParam("applicationtokenid") String applicationtokenid,
                                             @FormParam("apptoken") String appTokenXml,
                                             @FormParam("userticket") String userticket) {
        if (isEmpty(appTokenXml) || isEmpty(userticket)) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Missing required parameters").build();
        }

        log.trace("getUserTokenByUserTicket: applicationtokenid={}, userticket={}, appTokenXml={}", applicationtokenid, userticket, appTokenXml);

        if (ApplicationMode.getApplicationMode().equals(ApplicationMode.DEV)) {
            return DevModeHelper.return_DEV_MODE_ExampleUserToken(1);
        }

        if (!UserTokenFactory.verifyApplicationToken(applicationtokenid, appTokenXml)) {
            log.warn("getUserTokenByUserTicket - attempt to access from invalid application. applicationtokenid={}", applicationtokenid);
            return Response.status(Response.Status.FORBIDDEN).entity("Illegal application for this service").build();
        }
        String userTokenId = (String) userticketmap.get(userticket);
        if (userTokenId == null) {
            log.warn("getUserTokenByUserTicket - Attempt to resolve non-existing userticket={}", userticket);
            return Response.status(Response.Status.GONE).build(); //410
        }
        log.trace("getUserTokenByUserTicket - Found usertokenid: " + userTokenId);
        userticketmap.remove(userticket);
        final UserToken userToken = ActiveUserTokenRepository.getUserToken(userTokenId,applicationtokenid);

        if (userToken == null) {
            log.warn("getUserTokenByUserTicket - illegal/Null userticket received ");
            return Response.status(Response.Status.NOT_ACCEPTABLE).build(); //406
        }
        log.trace("getUserTokenByUserTicket OK. Response={}", userToken.toString());
        userToken.setNs2link(appConfig.getProperty("myuri") + "user/" + applicationtokenid + "/validate_usertokenid/" + userToken.getTokenid());
        userToken.setLastSeen(ActiveUserTokenRepository.getLastSeen(userToken));
        userToken.setDefcon(ApplicationThreatResource.getDEFCON());
        return Response.ok(new Viewable("/usertoken.ftl", UserTokenFactory.getFilteredUserToken(applicationtokenid, userToken))).build();
    }


    /**
     * Force cross-applications/SSO session logout. Use with extreme care as the user's hate the resulting user experience..
     *
     * @param applicationtokenid  application session
     * @param usertokenid  user session id
     * @return Response.OK
     */
    @Path("/{applicationtokenid}/release_usertoken")
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response releaseUserToken(@PathParam("applicationtokenid") String applicationtokenid,
                                     @FormParam("usertokenid") String usertokenid) {
        log.trace("releaseUserToken - entry.  usertokenid={}", usertokenid);
        if (!AuthenticatedApplicationRepository.verifyApplicationTokenId(applicationtokenid)) {
            log.warn("releaseUserToken - attempt to access from invalid application. applicationtokenid={}", applicationtokenid);
            return Response.status(Response.Status.FORBIDDEN).entity("Application authentication not valid.").build();
        }
        if (usertokenid == null) {
            log.warn("releaseUserToken - attempt with no usertokenid: Null");
            return Response.status(Response.Status.BAD_REQUEST).entity("Missing usertokenid.").build();
        }
        log.trace("releaseUserToken - removed session, usertokenid={}", usertokenid);
        ActiveUserTokenRepository.removeUserToken(usertokenid);
        return Response.ok().build();
    }

    /**
     * Request SSO user session renewal.
     *
     * @param applicationtokenid  application session
     * @param usertokenid  user session id
     * @return userTokenXml - usertoken with extended lease
     */
    @Path("/{applicationtokenid}/renew_usertoken")
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response renewUserToken(@PathParam("applicationtokenid") String applicationtokenid,
                                   @FormParam("usertokenid") String usertokenid) {
        log.trace("renewUserToken - entry.  usertokenid={}", usertokenid);
        if (!AuthenticatedApplicationRepository.verifyApplicationTokenId(applicationtokenid)) {
            log.warn("renewUserToken - attempt to access from invalid application. applicationtokenid={}", applicationtokenid);
            return Response.status(Response.Status.FORBIDDEN).entity("Application authentication not valid.").build();
        }
        if (usertokenid == null) {
            log.warn("renewUserToken - attempt with no usertokenid: Null");
            return Response.status(Response.Status.BAD_REQUEST).entity("Missing usertokenid.").build();
        }
        UserToken utoken = ActiveUserTokenRepository.getUserToken(usertokenid,applicationtokenid);
        utoken.setDefcon(ApplicationThreatResource.getDEFCON());
        utoken.setTimestamp(String.valueOf(System.currentTimeMillis() + 1000));
        utoken.setLifespan(String.valueOf(60 * new Random().nextInt(100)));
        ActiveUserTokenRepository.addUserToken(utoken);

        log.trace("renewUserToken - session renewed, usertokenid={}", usertokenid);
        return Response.ok().build();
    }

    /**
     * This method is for elevating user access to a higher level for the receiving end of a session handover between SSO applications
     *
     * @param applicationtokenid  calling application session
     * @param appTokenXml   application session data
     * @param userTokenXml  user session data
     * @param newAppTokenId  application session id of receiving application session
     * @return UserTokenXml as seen for the given application session
     */
    @Path("/{applicationtokenid}/transform_usertoken")
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_XML)
    public Response transformUserToken(@PathParam("applicationtokenid") String applicationtokenid,
                                       @FormParam("apptoken") String appTokenXml,
                                       @FormParam("usertoken") String userTokenXml,
                                       @FormParam("to_apptokenid") String newAppTokenId) {
        if (ApplicationMode.getApplicationMode().equals(ApplicationMode.DEV)) {
            return DevModeHelper.return_DEV_MODE_ExampleUserToken(1);
        }

        if (ApplicationMode.getApplicationMode().equals(ApplicationMode.DEV)) {
            return DevModeHelper.return_DEV_MODE_ExampleUserToken(1);
        }

        if (!UserTokenFactory.verifyApplicationToken(applicationtokenid, appTokenXml)) {
            log.warn("getUserTokenByUserTokenId - attempt to access from invalid application. applicationtokenid={}", applicationtokenid);
            return Response.status(Response.Status.FORBIDDEN).entity("Illegal application for this service").build();
        }
        String userTokenId = UserTokenFactory.fromXml(userTokenXml).getTokenid();
        final UserToken userToken = ActiveUserTokenRepository.getUserToken(userTokenId,applicationtokenid);
        userToken.setDefcon(ApplicationThreatResource.getDEFCON());
        if (userToken == null) {
            log.warn("getUserTokenByUserTokenId - attempt to access with non acceptable userTokenId={}", userTokenId);
            return Response.status(Response.Status.NOT_ACCEPTABLE).build();
        }
        return createUserTokenResponse(newAppTokenId, userToken);
    }


    /**
     * The SSOLoginWebApplication backend for 3rd party UserTokens. Receive a new user, create a Whydah UserIdentity with
     * the corresponding defaultroles (UAS|UIB) and create a new session with a one-time userticket for handover to receiving
     * SSO applications
     *
     * @param applicationtokenid  calling application session
     * @param userticket  user session id
     * @param appTokenXml   application session data
     * @param userCredentialXml  user credential  i.e. (username and password)
     * @param thirdPartyUserTokenXml typically facebook user-token or other oauth2 usertoken
     * @return  user session data
     */
    @Path("/{applicationtokenid}/{userticket}/create_user")
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_XML)
    public Response createAndLogOnUser(@PathParam("applicationtokenid") String applicationtokenid,
                                       @PathParam("userticket") String userticket,
                                       @FormParam("apptoken") String appTokenXml,
                                       @FormParam("usercredential") String userCredentialXml,
                                       @FormParam("fbuser") String thirdPartyUserTokenXml) {
        log.trace("Response createAndLogOnUser: usercredential:" + userCredentialXml + "fbuser:" + thirdPartyUserTokenXml);

        if (ApplicationMode.getApplicationMode() == ApplicationMode.DEV) {
            return DevModeHelper.return_DEV_MODE_ExampleUserToken(1);
        }

        if (!UserTokenFactory.verifyApplicationToken(applicationtokenid, appTokenXml)) {
            // TODO:  Limit this operation to SSOLoginWebApplication ONLY
            log.warn("createAndLogOnUser - attempt to access from invalid application. applicationtokenid={}", applicationtokenid);
            return Response.status(Response.Status.FORBIDDEN).entity("Application authentication not valid.").build();
        }

        try {
            applicationtokenidmap.put(applicationtokenid, applicationtokenid);
            UserToken userToken = userAuthenticator.createAndLogonUser(applicationtokenid, appTokenXml, userCredentialXml, thirdPartyUserTokenXml);
            userticketmap.put(userticket, userToken.getTokenid());
            userToken.setDefcon(ApplicationThreatResource.getDEFCON());
            userToken.setNs2link(appConfig.getProperty("myuri") + "user/" + applicationtokenid + "/validate_usertokenid/" + userToken.getTokenid());
            return Response.ok(new Viewable("/usertoken.ftl", UserTokenFactory.getFilteredUserToken(applicationtokenid, userToken))).build();
        } catch (AuthenticationFailedException ae) {
            log.warn("createAndLogOnUser - Error creating or authenticating user. thirdPartyUserTokenXml={}", thirdPartyUserTokenXml);
            return Response.status(Response.Status.FORBIDDEN).entity("Error creating or authenticating user.").build();
        }
    }

    private Response createUserTokenResponse(@PathParam("applicationtokenid") String applicationtokenid, UserToken userToken) {
        log.trace("getUserTokenByUserTokenId OK. Response={}", userToken.toString());
        userToken.setNs2link(appConfig.getProperty("myuri") + "user/" + applicationtokenid + "/validate_usertokenid/" + userToken.getTokenid());
        userToken.setLastSeen(ActiveUserTokenRepository.getLastSeen(userToken));
        userToken.setDefcon(ApplicationThreatResource.getDEFCON());
        return Response.ok(new Viewable("/usertoken.ftl", UserTokenFactory.getFilteredUserToken(applicationtokenid, userToken))).build();
    }


    boolean isEmpty(String userticket) {
        boolean isEmpty = false;
        if (userticket == null || userticket.isEmpty()) {
            isEmpty = true;
        }
        return isEmpty;
    }

}
