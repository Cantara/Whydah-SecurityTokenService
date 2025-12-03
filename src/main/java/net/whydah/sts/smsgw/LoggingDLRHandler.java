package net.whydah.sts.smsgw;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hazelcast.core.HazelcastInstance;

import net.whydah.sts.user.authentication.ActivePinRepository;

/**
 * Default implementation of DLR handler that logs delivery reports
 * and aggregates statistics for periodic Slack notifications using Hazelcast.
 */
public class LoggingDLRHandler implements Target365DLRHandler {
    
    private static final Logger log = LoggerFactory.getLogger(LoggingDLRHandler.class);
    
    // Static monitor instance shared across all handler instances
    private static SMSDeliveryMonitor monitor;
    
    /**
     * Initialize the SMS delivery monitor with Hazelcast support.
     * Should be called once during application startup.
     * 
     * @param hazelcastInstance The Hazelcast instance for distributed maps
     * @param gridPrefix The prefix for Hazelcast map names
     */
    public static void initializeMonitor(HazelcastInstance hazelcastInstance, String gridPrefix) {
        if (monitor == null) {
            monitor = new SMSDeliveryMonitor(hazelcastInstance, gridPrefix);
            monitor.start();
            
            if (monitor.isReportingNode()) {
                log.info("SMSDeliveryMonitor initialized and started as REPORTING NODE");
            } else {
                log.info("SMSDeliveryMonitor initialized as DATA NODE (reporting disabled)");
            }
        } else {
            log.warn("SMSDeliveryMonitor already initialized");
        }
    }
    
    /**
     * Shutdown the SMS delivery monitor.
     * Should be called during application shutdown.
     */
    public static void shutdownMonitor() {
        if (monitor != null) {
            monitor.stop();
            log.info("SMSDeliveryMonitor stopped");
        }
    }
    
    /**
     * Get monitor statistics (for health checks).
     */
    public static Map<String, Object> getMonitorStatistics() {
        if (monitor != null) {
            return monitor.getStatistics();
        }
        return Map.of("status", "not initialized");
    }
    
    @Override
    public void handleDeliveryReport(Target365DeliveryReport deliveryReport) {
        if (deliveryReport == null) {
            log.warn("Received null delivery report");
            return;
        }
        
        log.debug("Received DLR: {}", deliveryReport);
        
        if (deliveryReport.isSuccessful()) {
            onDeliverySuccess(deliveryReport);
        } else {
            onDeliveryFailure(deliveryReport);
        }
    }
    
    @Override
    public void onDeliverySuccess(Target365DeliveryReport deliveryReport) {
        log.info("SMS delivered successfully to {}. TransactionId: {}, CorrelationId: {}",
                deliveryReport.getRecipient(),
                deliveryReport.getTransactionId(),
                deliveryReport.getCorrelationId());
        
        ActivePinRepository.setDLR(deliveryReport.getRecipientWithoutCountryCode(), 
                                   deliveryReport.toString());
        
        // Record success in distributed Hazelcast map
        if (monitor != null) {
            monitor.recordSuccess(deliveryReport);
        } else {
            log.warn("SMSDeliveryMonitor not initialized - success not recorded");
        }
    }
    
    @Override
    public void onDeliveryFailure(Target365DeliveryReport deliveryReport) {
        log.error("SMS delivery failed to {}. StatusCode: {}, DetailedStatusCode: {}, " +
                 "TransactionId: {}, CorrelationId: {}",
                deliveryReport.getRecipient(),
                deliveryReport.getStatusCode(),
                deliveryReport.getDetailedStatusCode(),
                deliveryReport.getTransactionId(),
                deliveryReport.getCorrelationId());
        
        // Record failure in distributed Hazelcast map
        if (monitor != null) {
        	    //some extra filter
        		boolean isUnkownPhoneNumber = deliveryReport.getDetailedStatusCode().toUpperCase().contains("UNKNOWNSUBSCRIBER");
            if(!isUnkownPhoneNumber) {
            		monitor.recordFailure(deliveryReport);
            }
        		
        } else {
            log.error("SMSDeliveryMonitor not initialized - failure not recorded");
        }
    }
}