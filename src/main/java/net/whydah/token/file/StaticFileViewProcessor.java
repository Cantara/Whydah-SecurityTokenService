package net.whydah.token.file;

import org.glassfish.jersey.server.mvc.Viewable;
import org.glassfish.jersey.server.mvc.spi.TemplateProcessor;
import org.slf4j.LoggerFactory;

import javax.swing.text.html.HTML;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.Provider;
import java.io.*;

/**
 * Returns static text files from /webfiles on the classpath.
 */
@Provider
public class StaticFileViewProcessor implements TemplateProcessor<String> {
    private final org.slf4j.Logger log = LoggerFactory.getLogger(getClass());
    private static final String WEBFILES_PATH = "/webfiles";
    private static final String CONTENT_ENCODING_UTF8 = "UTF-8";

    @Override
    public String resolve(String name, MediaType mediaType) {
        log.debug("Resolving path {}", name);
        if (name.endsWith(".css") || name.endsWith("js") || name.endsWith("html")) {
            return name;
        } else {
            return null;
        }
    }

    @Override
    public void writeTo(String filepath, Viewable viewable, MediaType mediaType, MultivaluedMap<String, Object> multivaluedMap,OutputStream out) throws IOException {
        String path = WEBFILES_PATH + filepath;
        InputStream classpathStream = getClass().getResourceAsStream(path);
        if (classpathStream == null)
            throw new IllegalArgumentException("Missing path " + path + " for " + filepath);
        BufferedReader reader = new BufferedReader(new InputStreamReader(classpathStream, CONTENT_ENCODING_UTF8));
        String line;
        while ((line = reader.readLine()) != null) {
            out.write(line.getBytes(CONTENT_ENCODING_UTF8));
            out.write('\n');
        }
        reader.close();
    }


}
