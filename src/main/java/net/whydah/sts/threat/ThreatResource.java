package net.whydah.sts.threat;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.exoreaction.notification.SlackNotificationFacade;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import net.whydah.sso.whydah.DEFCON;
import net.whydah.sso.whydah.ThreatSignal;
import net.whydah.sts.application.AuthenticatedApplicationTokenRepository;
import net.whydah.sts.errorhandling.AppException;
import net.whydah.sts.errorhandling.AppExceptionCode;
import net.whydah.sts.health.HealthResource;

@Path("/threat")
public class ThreatResource {
    private final static Logger log = LoggerFactory.getLogger(ThreatResource.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static String defconvalue= DEFCON.DEFCON5.toString();

    private static final int MAX_LOGGED_LENGTH = 500;

    // Signals are dispatched off the request thread. Bounded queue with DiscardPolicy so a burst
    // of signals cannot exhaust threads or memory; daemon threads so they never block JVM shutdown.
    private static final ThreadPoolExecutor signalDispatcher = new ThreadPoolExecutor(
            1, 2, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(100),
            runnable -> {
                Thread thread = new Thread(runnable, "threat-signal-dispatcher");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.DiscardPolicy());

    @Path("/{applicationtokenid}/signal")
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response logSignal(@PathParam("applicationtokenid") String applicationtokenid,
                              @FormParam("signal") String jsonSignal) throws AppException {
        if (applicationtokenid == null || applicationtokenid.isEmpty()
                || !AuthenticatedApplicationTokenRepository.verifyApplicationTokenId(applicationtokenid)) {
            log.warn("logSignal - attempt to access from invalid application. applicationtokenid={}",
                    forLogging(applicationtokenid));
            throw AppExceptionCode.APP_ILLEGAL_7000;
        }

        log.warn("logSignal with applicationtokenid: {} - signal={}",
                forLogging(applicationtokenid), forLogging(jsonSignal));

        ThreatSignal signal;
        try {
            signal = mapper.readValue(jsonSignal, ThreatSignal.class);
        } catch (Exception e) {
            signal = new ThreatSignal();
            signal.setText(jsonSignal);
        }
        if (signal.getSignalEmitter() == null || signal.getSignalEmitter().length() < 5) {
            String applicationID = AuthenticatedApplicationTokenRepository.getApplicationIdFromApplicationTokenID(applicationtokenid);
            String applicationName = AuthenticatedApplicationTokenRepository.getApplicationNameFromApplicationTokenID(applicationtokenid);
            signal.setSignalEmitter(applicationID + ":" + applicationName);
        }
        signal.setSignalEmitter(applicationtokenid + " - " + signal.getSignalEmitter());

        final ThreatSignal receivedSignal = signal;
        signalDispatcher.execute(() -> {
            try {
                if ("HIGH".equalsIgnoreCase(receivedSignal.getSignalSeverity()) && !isInvalidPIN(receivedSignal)) {
                    HealthResource.addThreatSignal(receivedSignal);
                    String signalJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(receivedSignal);
                    SlackNotificationFacade.sendAlarm("Threat received:" + signalJson);
                }
            } catch (Exception e) {
                log.warn("Unable to dispatch threat signal", e);
            }
        });
        return Response.ok().build();
    }

    private static boolean isInvalidPIN(ThreatSignal receivedSignal) {
        String text = receivedSignal.getText();
        return text != null
                && (text.startsWith("Pin verification failed")
                || text.startsWith("Registration failed. Illegal form data"));
    }

    private static String forLogging(String value) {
        if (value == null) {
            return null;
        }
        String singleLine = value.replaceAll("[\r\n]", "_");
        return singleLine.length() > MAX_LOGGED_LENGTH
                ? singleLine.substring(0, MAX_LOGGED_LENGTH) + "...(truncated)"
                : singleLine;
    }

    public static String getDEFCON(){
        return defconvalue;
    }

    public static void setDEFCON(String s){
        if (isInEnum(s, DEFCON.class)){
            defconvalue=s;
        }
    }


    public static <E extends Enum<E>> boolean isInEnum(String value, Class<E> enumClass) {
        for (E e : enumClass.getEnumConstants()) {
            if(e.name().equals(value)) {
                return true; }
        }
        return false;
    }
}
