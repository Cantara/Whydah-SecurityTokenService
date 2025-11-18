package net.whydah.sts.smsgw;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.exoreaction.notification.util.ContextMapBuilder;

import jakarta.inject.Inject;
import net.whydah.sts.slack.SlackNotifications;
import net.whydah.sts.slack.SlackNotifier;
import net.whydah.sts.user.authentication.ActivePinRepository;

/**
 * Default implementation of DLR handler that logs delivery reports
 */
public class LoggingDLRHandler implements Target365DLRHandler {
    
    private static final Logger log = LoggerFactory.getLogger(LoggingDLRHandler.class);
    
    @Override
    public void handleDeliveryReport(Target365DeliveryReport deliveryReport) {
        if (deliveryReport == null) {
            log.warn("Received null delivery report");
            return;
        }
        
        log.info("Received DLR: {}", deliveryReport);
        
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
        
        ActivePinRepository.setDLR(deliveryReport.getRecipientWithoutCountryCode(), deliveryReport.toString());
        
        //send to slack
        SlackNotifications.sendToChannel("info", "SMS delivered successfully to " + deliveryReport.getRecipient());
    }
    
    @Override
    public void onDeliveryFailure(Target365DeliveryReport deliveryReport) {
        log.error("SMS delivery failed to {}. StatusCode: {}, DetailedStatusCode: {}, TransactionId: {}, CorrelationId: {}",
                deliveryReport.getRecipient(),
                deliveryReport.getStatusCode(),
                deliveryReport.getDetailedStatusCode(),
                deliveryReport.getTransactionId(),
                deliveryReport.getCorrelationId());
        //send to slack
        SlackNotifications.sendAlarm("SMS delivery failed", ContextMapBuilder.of(
        			"DLR", deliveryReport.toString()
        		));
        
    }
}