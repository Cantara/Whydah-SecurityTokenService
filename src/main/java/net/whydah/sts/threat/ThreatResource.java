package net.whydah.sts.threat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import net.whydah.sso.whydah.DEFCON;
import net.whydah.sso.whydah.ThreatSignal;
import net.whydah.sts.application.AuthenticatedApplicationTokenRepository;
import net.whydah.sts.health.HealthResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;

@Path("/threat")
public class ThreatResource {
    private final static Logger log = LoggerFactory.getLogger(ThreatResource.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static String defconvalue= DEFCON.DEFCON5.toString();
    private static ThreatSignal receivedSignal;


    @Path("/{applicationtokenid}/signal")
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response logSignal(@PathParam("applicationtokenid") String applicationtokenid,
                              @FormParam("signal") String jsonSignal) {
        log.warn("logSignal with applicationtokenid: {} - signal={}", applicationtokenid, jsonSignal);

        try {

            receivedSignal = mapper.readValue(jsonSignal, ThreatSignal.class);
            if (receivedSignal.getSignalEmitter() != null || receivedSignal.getSignalEmitter().length() < 5) {
                if (applicationtokenid != null && applicationtokenid.length() > 6) {
                    String applicationID = AuthenticatedApplicationTokenRepository.getApplicationIdFromApplicationTokenID(applicationtokenid);
                    String applicationName = AuthenticatedApplicationTokenRepository.getApplicationNameFromApplicationTokenID(applicationtokenid);
                    receivedSignal.setSignalEmitter(applicationID + ":" + applicationName);
                }
            }
        } catch (Exception e) {
            receivedSignal = new ThreatSignal();
            receivedSignal.setText(jsonSignal);
            if (applicationtokenid != null && applicationtokenid.length() > 6) {
                String applicationID = AuthenticatedApplicationTokenRepository.getApplicationIdFromApplicationTokenID(applicationtokenid);
                String applicationName = AuthenticatedApplicationTokenRepository.getApplicationNameFromApplicationTokenID(applicationtokenid);
                receivedSignal.setSignalEmitter(applicationID + ":" + applicationName);
            }
        }

        receivedSignal.setSignalEmitter(applicationtokenid + " - " + receivedSignal.getSignalEmitter());

        Executors.newCachedThreadPool().execute(new Runnable() {
            @Override
            public void run() {
                HealthResource.addThreatSignal(receivedSignal);
            }
        });
        return Response.ok().build();
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
