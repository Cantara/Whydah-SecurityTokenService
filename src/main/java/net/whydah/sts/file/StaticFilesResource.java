package net.whydah.sts.file;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;

/**
 * Handling static files, like css and js.
 * Used by testpage.html.ftl
 */
@Path("/files")
public class StaticFilesResource {
    private final static Logger log = LoggerFactory.getLogger(StaticFilesResource.class);

    private static final String WEBFILES_PATH = "/webfiles";
    private static final String CONTENT_ENCODING_UTF8 = "UTF-8";
    
    @Path("/js/{filename}")
    @GET
    @Produces("application/x-javascript")
    public Response getJsFile(@PathParam("filename") String filename) {
        log.debug("JS Request: " + filename);
        return Response.ok().entity(new StaticResouceStreamingOutput("/js/" + filename)).build();
    }

    @Path("/css/{filename}")
    @GET
    @Produces("text/css")
    public Response getCssFile(@PathParam("filename") String filename) {
        log.debug("CSS Request: " + filename);
        return Response.ok().entity(new StaticResouceStreamingOutput("/css/" + filename)).build();
    }
    
    
    public static class StaticResouceStreamingOutput implements StreamingOutput {

    	String path;
    	public StaticResouceStreamingOutput(String filepath){
    		path = WEBFILES_PATH + filepath;
    	}
    	
        @Override
        public void write(OutputStream output) throws IOException, WebApplicationException {
            InputStream classpathStream = getClass().getResourceAsStream(path);
			if(classpathStream!=null) {
				 BufferedReader reader = new BufferedReader(new InputStreamReader(classpathStream, CONTENT_ENCODING_UTF8));
				 String line;
			     while ((line = reader.readLine()) != null) {
			    	 output.write(line.getBytes(CONTENT_ENCODING_UTF8));
			    	 output.write('\n');
			     }
			     output.flush();
			}
        }

		
    }
    
}
