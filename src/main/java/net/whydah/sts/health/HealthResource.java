package net.whydah.sts.health;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import net.whydah.sso.whydah.ThreatSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Endpoint for health check
 */
@Path("/health")
public class HealthResource {
    private static final Logger log = LoggerFactory.getLogger(HealthResource.class);

    final static AsyncHealthService healthService = new AsyncHealthService(2, ChronoUnit.SECONDS);
    final static ObjectMapper mapper = new ObjectMapper();

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response isHealthy() {
        try {

            String healthJson = healthService.getHealthJson();
            log.trace("healthJson: {}", healthJson);

            return Response.ok(healthJson).build();

        } catch (Throwable t) {

            log.error("While getting health", t);
            ObjectNode health = mapper.createObjectNode();
            health.put("Status", "FAIL");
            health.put("errorMessage", "While getting health");
            StringWriter strWriter = new StringWriter();
            t.printStackTrace(new PrintWriter(strWriter));
            health.put("errorCause", strWriter.toString());
            String errorHealthJson = health.toPrettyString();
            log.debug("errorHealthJson: {}", errorHealthJson);

            return Response.serverError().build();
        }
    }

    public static Instant getRunningSince() {
        return healthService.getRunningSince();
    }

    public static void addThreatSignal(ThreatSignal threatSignal) {
        healthService.addThreatSignal(threatSignal);
    }

    public static String getHealthTextJson() {
        return healthService.getHealthJson();
    }
}
