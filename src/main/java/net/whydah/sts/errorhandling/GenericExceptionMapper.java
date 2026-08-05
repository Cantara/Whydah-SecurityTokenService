package net.whydah.sts.errorhandling;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.exoreaction.notification.SlackNotificationFacade;

import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
@Produces(MediaType.APPLICATION_JSON)
public class GenericExceptionMapper implements ExceptionMapper<Throwable> {
	private static final Logger log = LoggerFactory.getLogger(GenericExceptionMapper.class);
	// Throttle identical alarms so a stuck instance (e.g. HazelcastInstanceNotActiveException on
	// every request) emits one Slack alarm per signature per window, not hundreds (PROD, 2026-08).
	private static final long SLACK_THROTTLE_MS = 60_000L;
	private static final ConcurrentHashMap<String, Long> lastSlackNotifiedMs = new ConcurrentHashMap<>();

	public Response toResponse(Throwable ex) {
		WebApplicationException d;
		ErrorMessage errorMessage = new ErrorMessage();		
		setHttpStatus(ex, errorMessage);
		errorMessage.setCode(9999);
		errorMessage.setMessage(ex.getMessage());
		StringWriter errorStackTrace = new StringWriter();
		ex.printStackTrace(new PrintWriter(errorStackTrace));
		errorMessage.setDeveloperMessage(errorStackTrace.toString());
		errorMessage.setLink("");
				
		if (shouldNotifySlack(ex)) {
			SlackNotificationFacade.handleException(ex);
		} else {
			log.warn("Suppressed duplicate Slack alarm within {}ms window: {}: {}",
					SLACK_THROTTLE_MS, ex.getClass().getName(), ex.getMessage());
		}

		return Response.status(errorMessage.getStatus())
				.entity(ExceptionConfig.handleSecurity(errorMessage).toString())
				.type(MediaType.APPLICATION_JSON)
				.build();	
	}

	private boolean shouldNotifySlack(Throwable ex) {
		String signature = ex.getClass().getName() + ":" + String.valueOf(ex.getMessage());
		long now = System.currentTimeMillis();
		Long previous = lastSlackNotifiedMs.get(signature);
		if (previous != null && (now - previous) < SLACK_THROTTLE_MS) {
			return false;
		}
		lastSlackNotifiedMs.put(signature, now);
		// Opportunistic cleanup so the throttle map itself cannot grow without bound.
		if (lastSlackNotifiedMs.size() > 1000) {
			lastSlackNotifiedMs.values().removeIf(ts -> (now - ts) > SLACK_THROTTLE_MS);
		}
		return true;
	}

	private void setHttpStatus(Throwable ex, ErrorMessage errorMessage) {
		if(ex instanceof WebApplicationException exception ) { 
			errorMessage.setStatus(exception.getResponse().getStatus());
		} else {
			errorMessage.setStatus(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode()); //defaults to internal server error 500
		}
	}
}