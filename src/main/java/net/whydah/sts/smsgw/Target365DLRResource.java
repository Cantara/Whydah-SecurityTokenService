package net.whydah.sts.smsgw;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import net.minidev.json.JSONObject;
import net.minidev.json.parser.JSONParser;

@Path("/sms/dlr")
public class Target365DLRResource {
    
    private static final Logger log = LoggerFactory.getLogger(Target365DLRResource.class);
    private final Target365DLRHandler dlrHandler;
    
    public Target365DLRResource() {
        this(new LoggingDLRHandler());
    }
    
    public Target365DLRResource(Target365DLRHandler dlrHandler) {
        this.dlrHandler = dlrHandler;
    }
    
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response receiveDLR(String jsonPayload) {
        try {
            log.debug("Received DLR payload: {}", jsonPayload);
            
            // Parse JSON
            JSONParser parser = new JSONParser(JSONParser.MODE_PERMISSIVE);
            JSONObject json = (JSONObject) parser.parse(jsonPayload);
            
            // Map to DeliveryReport object
            Target365DeliveryReport dlr = mapToDeliveryReport(json);
            
            // Handle the delivery report
            dlrHandler.handleDeliveryReport(dlr);
            
            return Response.ok().entity("{\"status\":\"OK\"}").build();
            
        } catch (Exception e) {
            log.error("Error processing DLR", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"status\":\"ERROR\",\"message\":\"" + e.getMessage() + "\"}")
                    .build();
        }
    }
    
    private Target365DeliveryReport mapToDeliveryReport(JSONObject json) {
        Target365DeliveryReport dlr = new Target365DeliveryReport();
        dlr.setCorrelationId(json.getAsString("correlationId"));
        dlr.setTransactionId(json.getAsString("transactionId"));
        dlr.setSender(json.getAsString("sender"));
        dlr.setRecipient(json.getAsString("recipient"));
        dlr.setOperatorId(json.getAsString("operatorId"));
        dlr.setStatusCode(json.getAsString("statusCode"));
        dlr.setDetailedStatusCode(json.getAsString("detailedStatusCode"));
        
        // Handle Boolean fields - can be null
        if (json.get("delivered") != null) {
            String deliveredStr = json.getAsString("delivered");
            dlr.setDelivered(Boolean.parseBoolean(deliveredStr));
        }
        
        if (json.get("billed") != null) {
            String billedStr = json.getAsString("billed");
            dlr.setBilled(Boolean.parseBoolean(billedStr));
        }
        
        dlr.setSmscTransactionId(json.getAsString("smscTransactionId"));
        
        // Handle numeric fields
        if (json.get("smscMessageParts") != null) {
            dlr.setSmscMessageParts(Integer.parseInt(json.getAsString("smscMessageParts")));
        }
        
        if (json.get("price") != null) {
            dlr.setPrice(Double.parseDouble(json.getAsString("price")));
        }
        
        return dlr;
    }
}