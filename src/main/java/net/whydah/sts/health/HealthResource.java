package net.whydah.sts.health;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.exoreaction.notification.SlackNotificationFacade;
import com.exoreaction.notification.util.ContextMapBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import net.whydah.sso.whydah.ThreatSignal;

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

            // Return 503 (not 200) when the computed health is FAIL, so an HTTP monitor / keepalive
            // can restart a zombie instance (process alive but Hazelcast dead). Previously this
            // always returned 200 even for a FAIL body, so nothing auto-recovered.
            if (isHealthFail(healthJson)) {
                log.warn("Health reported FAIL - returning 503. health: {}", healthJson);
                return Response.status(Response.Status.SERVICE_UNAVAILABLE).entity(healthJson).build();
            }

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
            
            SlackNotificationFacade.handleException(t, "Health issue in STS", ContextMapBuilder.of("health", errorHealthJson));

            return Response.serverError().build();
        }
    }

    static boolean isHealthFail(String healthJson) {
        try {
            JsonNode status = mapper.readTree(healthJson).get("Status");
            return status != null && "FAIL".equalsIgnoreCase(status.asText());
        } catch (Exception e) {
            // Do not flap to 503 on a parse hiccup; a genuinely dead instance surfaces as a
            // thrown exception (handled below with 500) or as Status=FAIL once parseable.
            log.warn("Unable to parse health Status, treating as not-FAIL", e);
            return false;
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
