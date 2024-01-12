package net.whydah.sts.errorhandling;

import jakarta.ws.rs.core.Response.Status;




public class AppExceptionCode {

	//USER EXCEPTIONS
	public static final AppException USER_AUTHENTICATION_FAILED_6000 = new AppException(Status.FORBIDDEN, 6000, "Authentication failed", "Authentication failed", "");
	public static final AppException USER_VALIDATE_FAILED_6001 = new AppException(Status.UNAUTHORIZED, 6001, "UserToken is invalid", "validateUserTokenXML failed", "");
	public static final AppException USER_INVALID_USERTOKENID_6002 = new AppException(Status.NOT_ACCEPTABLE, 6002, "Attempt to access with non acceptable usertokenid", "Attempt to access with non acceptable usertokenid", "");
	public static final AppException USER_USERTICKET_NOTFOUND_6003 = new AppException(Status.GONE, 6003, "Attempt to resolve non-existing userticket", "Attempt to resolve non-existing userticket", "");
	public static final AppException USER_LOGIN_PIN_FAILED_6004 = new AppException(Status.NOT_ACCEPTABLE, 6004, "Attempt to access with non acceptable username/phoneno", "Attempt to access with non acceptable username/phoneno", "");
	public static final AppException USER_USER_EMAIL_NOTFOUND_6005 = new AppException(Status.NOT_ACCEPTABLE, 6005, "Attempt to access with non acceptable user's email", "Attempt to access with non acceptable user's email", "");
	public static final AppException USER_INVALID_PINCODE_6006 = new AppException(Status.NOT_ACCEPTABLE, 6006, "Invalid pin code", "Invalid pin code", "");
			
	//APPLICATION EXCEPTIONS
	public static final AppException APP_ILLEGAL_7000 = new AppException(Status.FORBIDDEN, 7000, "Illegal Application.", "Application is invalid", "");
	
	//MISC
	public static final AppException MISC_MISSING_PARAMS_9998 = new AppException(Status.BAD_REQUEST, 9998, "Missing required parameters", "Missing required parameters", "");
	public static final AppException MISC_INTERNAL_ERROR_PARAMS_9999 = new AppException(Status.INTERNAL_SERVER_ERROR, 9999, "Internal server exception: %s", "", "");

	
}
