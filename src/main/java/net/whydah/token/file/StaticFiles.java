package net.whydah.token.file;

import org.glassfish.jersey.server.mvc.Viewable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;

/**
 * Handling static files, like css and js.
 * Used by testpage.html.ftl
 */
@Path("/files")
public class StaticFiles {
    private final static Logger log = LoggerFactory.getLogger(StaticFiles.class);

    @Path("/js/{filename}")
    @GET
    @Produces("application/x-javascript")
    public Response getJsFile(@PathParam("filename") String filename) {
        log.debug("JS Request: " + filename);
        return Response.ok().entity(new Viewable("/js/" + filename)).build();
    }

    @Path("/css/{filename}")
    @GET
    @Produces("text/css")
    public Response getCssFile(@PathParam("filename") String filename) {
        log.debug("CSS Request: " + filename);
        return Response.ok().entity(new Viewable("/css/" + filename)).build();
    }
}
