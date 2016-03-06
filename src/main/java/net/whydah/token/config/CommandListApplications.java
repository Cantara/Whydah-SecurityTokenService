package net.whydah.token.config;


import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixCommandGroupKey;
import org.slf4j.Logger;

import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.URI;

import static javax.ws.rs.core.Response.Status.FORBIDDEN;
import static javax.ws.rs.core.Response.Status.OK;
import static org.slf4j.LoggerFactory.getLogger;

public class CommandListApplications extends HystrixCommand<String> {
    private static final Logger log = getLogger(CommandListApplications.class);
    private URI userAdminServiceUri;
    private String myAppTokenId;
    private String adminUserTokenId;
    private String applicationQuery;


    public CommandListApplications(URI userAdminServiceUri, String myAppTokenId, String adminUserTokenId, String applicationQuery) {
        super(HystrixCommandGroupKey.Factory.asKey("UASUserAdminGroup"));
        this.userAdminServiceUri = userAdminServiceUri;
        this.myAppTokenId = myAppTokenId;
        this.adminUserTokenId = adminUserTokenId;
        this.applicationQuery = applicationQuery;
        if (userAdminServiceUri == null || myAppTokenId == null || adminUserTokenId == null || applicationQuery == null) {
            log.error("CommandListApplications initialized with null-values - will fail");
        }

    }


    @Override
    protected String run() {
        log.trace("CommandListApplications - myAppTokenId={}", myAppTokenId);

        WebTarget uasResource = ClientBuilder.newClient().target(userAdminServiceUri);

        WebTarget webResource = uasResource.path(myAppTokenId).path(adminUserTokenId).path("/applications");
        log.debug("CommandListApplications - Calling applications " + webResource.toString());
        Response response = webResource.request(MediaType.APPLICATION_JSON).get(Response.class);

        if (response.getStatus() == FORBIDDEN.getStatusCode()) {
            log.info("CommandListApplications -  User authentication failed with status code " + response.getStatus());
            return null;
            //throw new IllegalArgumentException("Log on failed. " + Response.Status.FORBIDDEN);
        }
        if (response.getStatus() == OK.getStatusCode()) {
            String responseJson = response.toString();
            log.debug("CommandListApplications - Listing applications {}", responseJson);
            return responseJson;
        }

        return null;
    }

    @Override
    protected String getFallback() {
        log.warn("CommandListApplications - timeout - uri={}", userAdminServiceUri.toString());
        return null;
    }


}
