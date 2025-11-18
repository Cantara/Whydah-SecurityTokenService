package net.whydah.sts.smsgw;

import com.fasterxml.jackson.annotation.JsonProperty;

public class Target365DeliveryReport {
    
    @JsonProperty("correlationId")
    private String correlationId;
    
    @JsonProperty("transactionId")
    private String transactionId;
    
    @JsonProperty("sessionId")
    private String sessionId;
    
    @JsonProperty("price")
    private Double price;
    
    @JsonProperty("sender")
    private String sender;
    
    @JsonProperty("recipient")
    private String recipient;
    
    @JsonProperty("operatorId")
    private String operatorId;
    
    @JsonProperty("statusCode")
    private String statusCode;
    
    @JsonProperty("detailedStatusCode")
    private String detailedStatusCode;
    
    @JsonProperty("delivered")
    private Boolean delivered;
    
    @JsonProperty("billed")
    private Boolean billed;
    
    @JsonProperty("smscTransactionId")
    private String smscTransactionId;
    
    @JsonProperty("smscMessageParts")
    private Integer smscMessageParts;
    
    @JsonProperty("received")
    private String received;
    
    // Getters and setters
    
    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Double getPrice() {
        return price;
    }

    public void setPrice(Double price) {
        this.price = price;
    }

    public String getSender() {
        return sender;
    }

    public void setSender(String sender) {
        this.sender = sender;
    }

    public String getRecipient() {
        return recipient;
    }

    public void setRecipient(String recipient) {
        this.recipient = recipient;
    }

    /**
     * Get recipient phone number without country code
     * +4798079008 becomes 98079008
     */
    public String getRecipientWithoutCountryCode() {
        return removeCountryCode(recipient);
    }
    
    /**
     * Remove country code from phone number
     * +4798079008 -> 98079008
     * +47123456789 -> 123456789
     */
    private String removeCountryCode(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isEmpty()) {
            return phoneNumber;
        }
        
        // Remove + prefix
        String normalized = phoneNumber.startsWith("+") ? phoneNumber.substring(1) : phoneNumber;
        
        // Remove common country codes (Norway: 47, Sweden: 46, Denmark: 45, etc.)
        if (normalized.startsWith("47") && normalized.length() >= 10) {
            return normalized.substring(2);
        } else if (normalized.startsWith("46") && normalized.length() >= 10) {
            return normalized.substring(2);
        } else if (normalized.startsWith("45") && normalized.length() >= 10) {
            return normalized.substring(2);
        }
        
        return normalized;
    }

    public String getOperatorId() {
        return operatorId;
    }

    public void setOperatorId(String operatorId) {
        this.operatorId = operatorId;
    }

    public String getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(String statusCode) {
        this.statusCode = statusCode;
    }

    public String getDetailedStatusCode() {
        return detailedStatusCode;
    }

    public void setDetailedStatusCode(String detailedStatusCode) {
        this.detailedStatusCode = detailedStatusCode;
    }

    public Boolean getDelivered() {
        return delivered;
    }

    public void setDelivered(Boolean delivered) {
        this.delivered = delivered;
    }

    public Boolean getBilled() {
        return billed;
    }

    public void setBilled(Boolean billed) {
        this.billed = billed;
    }

    public String getSmscTransactionId() {
        return smscTransactionId;
    }

    public void setSmscTransactionId(String smscTransactionId) {
        this.smscTransactionId = smscTransactionId;
    }

    public Integer getSmscMessageParts() {
        return smscMessageParts;
    }

    public void setSmscMessageParts(Integer smscMessageParts) {
        this.smscMessageParts = smscMessageParts;
    }

    public String getReceived() {
        return received;
    }

    public void setReceived(String received) {
        this.received = received;
    }

    public boolean isSuccessful() {
        return delivered != null && delivered;
    }

    @Override
    public String toString() {
        return "Target365DeliveryReport{" +
                "correlationId='" + correlationId + '\'' +
                ", transactionId='" + transactionId + '\'' +
                ", recipient='" + recipient + '\'' +
                ", statusCode='" + statusCode + '\'' +
                ", detailedStatusCode='" + detailedStatusCode + '\'' +
                ", delivered=" + delivered +
                ", billed=" + billed +
                ", received='" + received + '\'' +
                '}';
    }
}