package net.whydah.sts.smsgw;

/**
 * Handler for processing Target365 delivery reports
 */
public interface Target365DLRHandler {
    
    /**
     * Handle incoming delivery report
     * @param deliveryReport The delivery report from Target365
     */
    void handleDeliveryReport(Target365DeliveryReport deliveryReport);
    
    /**
     * Handle delivery success
     * @param deliveryReport The delivery report
     */
    default void onDeliverySuccess(Target365DeliveryReport deliveryReport) {
        // Override to implement custom logic
    }
    
    /**
     * Handle delivery failure
     * @param deliveryReport The delivery report
     */
    default void onDeliveryFailure(Target365DeliveryReport deliveryReport) {
        // Override to implement custom logic
    }
}