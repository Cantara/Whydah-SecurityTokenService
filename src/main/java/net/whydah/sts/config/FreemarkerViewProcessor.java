package net.whydah.sts.config;

import freemarker.cache.ClassTemplateLoader;
import freemarker.ext.beans.BeansWrapper;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateModel;
import jakarta.servlet.ServletContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.Provider;
import org.glassfish.jersey.server.mvc.Viewable;
import org.glassfish.jersey.server.mvc.spi.AbstractTemplateProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Hentet fra https://github.com/cwinters/jersey-freemarker/blob/master/src/com/cwinters/jersey/FreemarkerTemplateProvider.java
 *
 * Match a Viewable-named view with a Freemarker template.
 *
 * This class is based on the following original implementation:
 * http://github.com/cwinters/jersey-freemarker/
 *
 * <p>
 * You can configure the location of your templates with the context param
 * 'freemarker.template.path'. If not assigned we'll use a default of
 * <tt>WEB-INF/templates</tt>. Note that this uses Freemarker's
 * {@link freemarker.cache.WebappTemplateLoader} to load/cache the templates, so
 * check its docs (or crank up the logging under the 'freemarker.cache' package)
 * if your templates aren't getting loaded.
 * </p>
 *
 * <p>
 * This will put your Viewable's model object in the template variable "it",
 * unless the model is a Map. If so, the values will be assigned to the template
 * assuming the map is of type Map-String,Object.
 * </p>
 *
 * <p>
 * There are a number of methods you can override to change the behavior, such
 * as handling processing exceptions, changing the default template extension,
 * or adding variables to be assigned to every template context.
 * </p>
 *
 * @author Chris Winters chris@cwinters.com // original code
 * @author Olivier Grisel ogrisel@nuxeo.com // ViewProcessor refactoring
 */
@Provider
public class FreemarkerViewProcessor extends AbstractTemplateProcessor<Template> {
    private final Logger log = LoggerFactory.getLogger(getClass());

    private Configuration freemarkerConfig;

    public FreemarkerViewProcessor(jakarta.ws.rs.core.Configuration config, ServletContext servletContext, String propertySuffix, String... supportedExtensions) {
        super(config, servletContext, propertySuffix, supportedExtensions);
    }


    /**
     * Catch any exception generated during template processing.
     *
     * @param e Exception caught
     * @param template path of template we're executing
     * @param out output stream from servlet container
     * @throws IOException on any write errors, or if you want to rethrow
     */
    private void onProcessException(final Exception e,
            final Template template, final OutputStream out) throws IOException {
        log.error("Error processing freemarker template @ {}", template.getName(), e);
        out.write("<pre>".getBytes());
        e.printStackTrace(new PrintStream(out));
        out.write("</pre>".getBytes());
    }

    private Configuration getConfig() {
        if (freemarkerConfig == null) {
            Configuration config = new Configuration();
            config.setEncoding(Locale.getDefault(), "UTF-8");
            config.setDefaultEncoding("UTF-8");
            config.setOutputEncoding("UTF-8");
            
            config.setTemplateLoader(new ClassTemplateLoader(getClass(), "/templates"));
            
            // don't always put a ',' in numbers (e.g., id=2000 vs id=2,000)
            config.setNumberFormat("0");
            config.setLocalizedLookup(false);
            config.setTemplateUpdateDelay(3600);
            
            freemarkerConfig = config;
        }
        return freemarkerConfig;
    }

    @Override
    protected Template resolve(String path, Reader reader) throws Exception {
        log.debug("Resolving path {}", path);
        if(!path.endsWith(".ftl")) {
            log.debug("{} not handled by FreemarkerViewProcessor", path);
            return null;
        }
        try {
            return getConfig().getTemplate(path);
        } catch (IOException e) {
            log.error("Failed to load freemaker template: " + path);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public void writeTo(Template template, Viewable viewable, OutputStream out) throws IOException {
    	
    	template.setEncoding("UTF-8");
    	template.setOutputEncoding("UTF-8");
        out.flush(); // send status + headers

        Object model = viewable.getModel();
        final Map<String, Object> vars = new HashMap<>();
        if (model instanceof Map<?, ?>) {
            vars.putAll((Map<String, Object>) model);
        } else {
            vars.put("it", model);
        }

        //  Add the static members to the statics field
        BeansWrapper w = new BeansWrapper();
        TemplateModel statics = w.getStaticModels();
        vars.put("statics", statics); // map is java.util.Map

        final OutputStreamWriter writer = new OutputStreamWriter(out, "UTF-8");
        try {
            template.process(vars, writer);
        } catch (Exception e) {
            onProcessException(e, template, out);
        }
    }

    @SuppressWarnings("unchecked")
    public void writeTo(Template template, Viewable viewable, Map m, OutputStream out) throws IOException {
    	template.setEncoding("UTF-8");
    	template.setOutputEncoding("UTF-8");
    	
        out.flush(); // send status + headers

        Object model = viewable.getModel();
        final Map<String, Object> vars = new HashMap<>();
        if (model instanceof Map<?, ?>) {
            vars.putAll((Map<String, Object>) model);
        } else {
            vars.put("it", model);
        }
        if (m != null) {
            vars.putAll((Map<String, Object>) m);

        }

        //  Add the static members to the statics field
        BeansWrapper w = new BeansWrapper();
        TemplateModel statics = w.getStaticModels();
        vars.put("statics", statics); // map is java.util.Map

        final OutputStreamWriter writer = new OutputStreamWriter(out, "UTF-8");
        try {
            template.process(vars, writer);
        } catch (Exception e) {
            onProcessException(e, template, out);
        }
    }

    @Override
    public void writeTo(Template template, Viewable viewable, MediaType mediaType, MultivaluedMap<String, Object> multivaluedMap, OutputStream outputStream) throws IOException {
        template.setEncoding("UTF-8");
        template.setOutputEncoding("UTF-8");

        outputStream.flush(); // send status + headers

        Object model = viewable.getModel();
        final Map<String, Object> vars = new HashMap<>();
        if (model instanceof Map<?, ?>) {
            vars.putAll((Map<String, Object>) model);
        } else {
            vars.put("it", model);
        }
        if (multivaluedMap != null) {
            vars.putAll(multivaluedMap);

        }

        //  Add the static members to the statics field
        BeansWrapper w = new BeansWrapper();
        TemplateModel statics = w.getStaticModels();
        vars.put("statics", statics); // map is java.util.Map

        final OutputStreamWriter writer = new OutputStreamWriter(outputStream, "UTF-8");
        try {
            template.process(vars, writer);
        } catch (Exception e) {
            onProcessException(e, template, outputStream);
        }
    }
}
