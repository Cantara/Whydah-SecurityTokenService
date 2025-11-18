package net.whydah.sts.slack;

import java.util.Map;

import com.exoreaction.notification.helper.SlackNotificationHelper;

import net.whydah.sts.util.HK2ServiceLocator;


/**
 * Static utility class for sending Slack notifications from anywhere in the application.
 * Uses HK2 ServiceLocator to access the injected SlackNotifier instance.
 * 
 * This is useful for:
 * - Static methods that can't use dependency injection
 * - Legacy code that can't be easily refactored
 * - Utility classes that need to send notifications
 */
public class SlackNotifications {

	public static void sendAlarm(String error, Map<String, Object> context) {
		SlackNotifier sn = HK2ServiceLocator.getService(SlackNotifier.class);
		if(sn!=null) {
			sn.sendAlarm(error, context);	
		}
	}

	public static void sendAlarm(String error) {
		SlackNotifier sn = HK2ServiceLocator.getService(SlackNotifier.class);
		if(sn!=null) {
			sn.sendAlarm(error);	
		}
	}

	public static void handleException(Throwable e, String methodName, Map<String, Object> additionalContexts) {
		SlackNotifier sn = HK2ServiceLocator.getService(SlackNotifier.class);
		if(sn!=null) {
			sn.handleException(e, methodName, additionalContexts);	
		}
	}

	public static void handleException(Throwable e, String methodName, String message, Map<String, Object> additionalContexts) {
		SlackNotifier sn = HK2ServiceLocator.getService(SlackNotifier.class);
		if(sn!=null) {
			sn.handleException(e, methodName, message, additionalContexts);	
		}
	} 

	public static void handleException(Throwable e, String methodName, String message) {
		SlackNotifier sn = HK2ServiceLocator.getService(SlackNotifier.class);
		if(sn!=null) {
			sn.handleException(e, methodName, message, null);	
		}
	}

	public static void handleException(Throwable e, String methodName) {
		SlackNotifier sn = HK2ServiceLocator.getService(SlackNotifier.class);
		if(sn!=null) {
			sn.handleException(e, methodName);	
		}
	}

	public static void handleException(Throwable e) {
		SlackNotifier sn = HK2ServiceLocator.getService(SlackNotifier.class);
		if(sn!=null) {
			sn.handleException(e);	
		}
	}

	public static void handleExceptionAsWarning(Throwable e, String methodName, String message, Map<String, Object> contexts) {
		SlackNotifier sn = HK2ServiceLocator.getService(SlackNotifier.class);
		if(sn!=null) {
			sn.handleExceptionAsWarning(e, methodName, message, contexts);	
		}
	}

    public static void sendToChannel(String channel, String message, Map<String, Object> contexts) {
    	SlackNotifier sn = HK2ServiceLocator.getService(SlackNotifier.class);
		if(sn!=null) {
			sn.sendToChannel(channel, message, contexts);	
		}
    }
   
    public static void sendToChannel(String channel, String message) {
    	sendToChannel(channel, message, null);
    }
    
    public static void sendToChannel(String channel, String message, Map<String, Object> contexts, boolean isSuccess) {
    	SlackNotifier sn = HK2ServiceLocator.getService(SlackNotifier.class);
		if(sn!=null) {
			sn.sendToChannel(channel, message, contexts, isSuccess);	
		}
    }
    




}
