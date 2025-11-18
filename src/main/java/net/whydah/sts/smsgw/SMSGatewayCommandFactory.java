package net.whydah.sts.smsgw;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.whydah.sso.commands.adminapi.user.CommandSendSMSToUser;
import net.whydah.sso.commands.adminapi.user.CommandSendSMSToUserTarget365;
import net.whydah.sts.config.AppConfig;

public class SMSGatewayCommandFactory {
    
    private static final Logger log = LoggerFactory.getLogger(SMSGatewayCommandFactory.class);
    
    private static volatile SMSGatewayCommandFactory instance;
    private final SMSGatewayConfig config;
    
    /**
     * Private constructor for singleton pattern
     */
    private SMSGatewayCommandFactory() {
        this.config = new SMSGatewayConfig();
        log.info("SMSGatewayCommandFactory initialized with provider: {}", config.getActiveProvider());
    }
    
    /**
     * Constructor for testing with custom config
     */
    public SMSGatewayCommandFactory(SMSGatewayConfig config) {
        this.config = config;
        log.info("SMSGatewayCommandFactory initialized with provider: {}", config.getActiveProvider());
    }
    
    /**
     * Get singleton instance
     */
    public static SMSGatewayCommandFactory getInstance() {
        if (instance == null) {
            synchronized (SMSGatewayCommandFactory.class) {
                if (instance == null) {
                    instance = new SMSGatewayCommandFactory();
                }
            }
        }
        return instance;
    }
    
    /**
     * Create SMS command based on active provider configuration
     * @param recipientPhoneNumber Recipient phone number
     * @param messageContent SMS message content
     * @return SMS command ready to execute
     */
    public SMSGatewayCommand createSendSMSCommand(String recipientPhoneNumber, String messageContent) {
        return createSendSMSCommand(recipientPhoneNumber, messageContent, null);
    }
    
    /**
     * Create SMS command based on active provider configuration
     * @param recipientPhoneNumber Recipient phone number
     * @param messageContent SMS message content
     * @param tag Message tag (e.g., "sso", "pincode") - only used for Target365
     * @return SMS command ready to execute
     */
    public SMSGatewayCommand createSendSMSCommand(String recipientPhoneNumber, String messageContent, String tag) {
        String activeProvider = config.getActiveProvider();
        
        if (activeProvider == null || activeProvider.isEmpty()) {
            log.error("No active SMS gateway provider configured");
            throw new IllegalStateException("No active SMS gateway provider configured");
        }
        
        switch (activeProvider.toLowerCase()) {
            case "puzzel":
                return createPuzzelCommand(recipientPhoneNumber, messageContent);
            
            case "target365":
            case "strex":
                return createTarget365Command(recipientPhoneNumber, messageContent, tag);
            
            default:
                log.error("Unknown SMS gateway provider: {}", activeProvider);
                throw new IllegalArgumentException("Unknown SMS gateway provider: " + activeProvider);
        }
    }
    
    private SMSGatewayCommand createPuzzelCommand(String recipientPhoneNumber, String messageContent) {
        log.debug("Creating Puzzel SMS command for recipient: {}", recipientPhoneNumber);
        
        return new SMSGatewayCommand(
            new CommandSendSMSToUser(
                config.getPuzzelServiceUrl(),
                config.getPuzzelServiceAccount(),
                config.getPuzzelUsername(),
                config.getPuzzelPassword(),
                config.getPuzzelQueryParams(),
                recipientPhoneNumber,
                messageContent
            ),
            "puzzel"
        );
    }
    
    private SMSGatewayCommand createTarget365Command(String recipientPhoneNumber, String messageContent, String tag) {
        log.debug("Creating Target365 SMS command for recipient: {} with tag: {}", recipientPhoneNumber, tag);
        
        // Use default tag if not provided
        String messageTag = (tag != null && !tag.isEmpty()) ? tag : config.getTarget365DefaultTag();
        String dlrUrl = AppConfig.getProperty("myuri") + "sms/dlr";
        String correlationId = java.util.UUID.randomUUID().toString();
        return new SMSGatewayCommand(
            new CommandSendSMSToUserTarget365(
            		config.getTarget365ServiceUrl(),
                    config.getTarget365ApiKey(),
                    config.getTarget365Sender(),
                    recipientPhoneNumber,
                    messageContent,
                    messageTag,
                    dlrUrl,
                    correlationId
            ),
            "target365"
        );
    }
    
    /**
     * Wrapper class to provide uniform interface for different SMS gateway commands
     */
    public static class SMSGatewayCommand {
        private final Object command;
        private final String provider;
        
        public SMSGatewayCommand(Object command, String provider) {
            this.command = command;
            this.provider = provider;
        }
        
        public String execute() {
            if (command instanceof CommandSendSMSToUser) {
                return ((CommandSendSMSToUser) command).execute();
            } else if (command instanceof CommandSendSMSToUserTarget365) {
                return ((CommandSendSMSToUserTarget365) command).execute();
            }
            throw new IllegalStateException("Unknown command type: " + command.getClass().getName());
        }
        
        public String getProvider() {
            return provider;
        }
    }
}