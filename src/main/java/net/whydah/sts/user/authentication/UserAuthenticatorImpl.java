package net.whydah.sts.user.authentication;


import net.whydah.sso.application.mappers.ApplicationTokenMapper;
import net.whydah.sso.application.types.ApplicationToken;
import net.whydah.sso.commands.adminapi.user.CommandGetUserAggregate;
import net.whydah.sso.commands.adminapi.user.CommandListUsers;
import net.whydah.sso.commands.adminapi.user.CommandUserExists;
import net.whydah.sso.user.helpers.UserTokenXpathHelper;
import net.whydah.sso.user.mappers.UserCredentialMapper;
import net.whydah.sso.user.mappers.UserTokenMapper;
import net.whydah.sso.user.types.UserCredential;
import net.whydah.sso.user.types.UserToken;
import net.whydah.sts.application.AuthenticatedApplicationTokenRepository;
import net.whydah.sts.config.AppConfig;
import net.whydah.sts.errorhandling.AuthenticationFailedException;
import net.whydah.sts.slack.SlackNotifications;
import net.whydah.sts.slack.SlackNotifier;
import net.whydah.sts.user.AuthenticatedUserTokenRepository;
import net.whydah.sts.user.UserTokenFactory;
import net.whydah.sts.user.authentication.commands.CommandCreateFBUser;
import net.whydah.sts.user.authentication.commands.CommandCreatePinUser;
import net.whydah.sts.user.authentication.commands.CommandVerifyUserCredential;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.exoreaction.notification.util.ContextMapBuilder;

import jakarta.inject.Inject;

import java.net.URI;
import java.util.List;
import java.util.UUID;

public class UserAuthenticatorImpl implements UserAuthenticator {
	private static final Logger log = LoggerFactory.getLogger(UserAuthenticatorImpl.class);


	private URI useradminservice;
	private final AppConfig appConfig = new AppConfig();

	@Inject SlackNotifier slackNotifier;

	@Inject
	public UserAuthenticatorImpl() {
		String useradminservice_prop = appConfig.getProperty("useradminservice");
		this.useradminservice = URI.create(useradminservice_prop);

	}

	public UserAuthenticatorImpl(String uasAdminUrl, AppConfig appConfig) {
		this.useradminservice = URI.create(uasAdminUrl);

	}

	@Override
	public UserToken logonUser(String applicationTokenId, String appTokenXml, final String userCredentialXml, long userTokenLifespan) throws AuthenticationFailedException {
		UserCredential userCredential = UserCredentialMapper.fromXml(userCredentialXml);
		if (userCredential != null) {
			log.trace("logonUser - Calling UserAdminService at " + useradminservice + " appTokenXml:" + appTokenXml + " userCredentialSafeXml:" + userCredential.toSafeXML());
		} else {
			log.trace("logonUser - Unable to map userCredentialXML - Calling UserAdminService at " + useradminservice + " appTokenXml:" + appTokenXml + " userCredentialXml:" + userCredentialXml);

		}

		UserToken uToken = new CommandVerifyUserCredential(useradminservice, appTokenXml, applicationTokenId, userCredentialXml).execute();
		if (uToken == null) {
			throw new AuthenticationFailedException("Authentication failed for user: %s, appTokenId: %s".formatted(userCredentialXml, applicationTokenId));
		}
		// credential check success
		UserToken existingUserToken = AuthenticatedUserTokenRepository.getUserTokenByUserName(userCredential.getUserName(), applicationTokenId);
		if (existingUserToken != null && existingUserToken.isValid()) {
			// re-use id from existing user-token in order to avoid invaliding existing sessions
			uToken.setUserTokenId(existingUserToken.getUserTokenId());
			// TODO figure out whether more fields should be copied from existing user-token, or whether we should just
			//  re-use and refresh existing token and throw away the one created from credential check.
		}
		return AuthenticatedUserTokenRepository.addUserToken(uToken, applicationTokenId, "usertokenid", userTokenLifespan);
	}

	@Override
	public UserToken createAndLogonUser(String applicationTokenId, String appTokenXml, String userCredentialXml, String thirdpartyUserXML, long userTokenLifespan) throws AuthenticationFailedException {
		log.trace("createAndLogonUser - Calling UserAdminService at with appTokenXml:\n" + appTokenXml + "userCredentialXml:\n" + userCredentialXml + "thirdpartyUserXML:\n" + thirdpartyUserXML);
		UserToken userToken = new CommandCreateFBUser(useradminservice, appTokenXml, applicationTokenId, thirdpartyUserXML).execute();
		return AuthenticatedUserTokenRepository.addUserToken(userToken, applicationTokenId, "usertokenid", userTokenLifespan);
	}


	@Override
	public UserToken createAndLogonPinUser(String applicationTokenId, String appTokenXml, String adminUserTokenId, String cellPhone, String pin, String userJson, long userTokenLifespan) {
		if (ActivePinRepository.usePin(cellPhone, pin)) {
			try {
				//check if the user exists or not, better to avoid misused calls
				Boolean exists = new CommandUserExists(useradminservice, applicationTokenId, adminUserTokenId, cellPhone).execute();
				if(exists) {
					UserToken existingUserToken = AuthenticatedUserTokenRepository.getUserTokenByUserName(cellPhone, applicationTokenId);
					if(existingUserToken!=null) {
						return existingUserToken;
					} else {
						//check against UAS
						String usersQuery = cellPhone;
						String usersJson = new CommandListUsers(useradminservice, applicationTokenId, adminUserTokenId, usersQuery).execute();

						if (usersJson == null) {
							log.error("Unable to find any user from the query " + usersQuery);
							slackNotifier.sendAlarm("Unable to find any users from the CommandListUsers for the query " + usersQuery, 
									ContextMapBuilder.of(
											"location", "createAndLogonPinUser  method",
											"applicationtokenid", applicationTokenId, 
											"cellphone", cellPhone 
											));

							throw new Exception("Unexpected exception occured. We unable to find a user from the query " + usersQuery);

						} else {
							log.info("CommandListUsers for query {} found users {}", usersQuery, usersJson);
						}


						UserToken userTokenIdentity = getFirstMatch(usersJson, usersQuery);
						if (userTokenIdentity != null) {
							log.info("Found matching UserIdentity {}", userTokenIdentity);
							String userAggregateJson = new CommandGetUserAggregate(useradminservice, applicationTokenId, adminUserTokenId, userTokenIdentity.getUid()).execute();
							UserToken userToken = UserTokenMapper.fromUserAggregateJson(userAggregateJson);
							userToken.setSecurityLevel("0");  
							userToken.setTimestamp(String.valueOf(System.currentTimeMillis()));
							return AuthenticatedUserTokenRepository.addUserToken(userToken, applicationTokenId, "pin", userTokenLifespan);
						}
					}
				}

				UserToken userToken = new CommandCreatePinUser(useradminservice, appTokenXml, applicationTokenId, adminUserTokenId, userJson).execute();
				if (userToken == null) {
					throw new AuthenticationFailedException("Pin authentication failed. Status code ");
				} else {
					return AuthenticatedUserTokenRepository.addUserToken(userToken, applicationTokenId, "pin", userTokenLifespan);
				}
			} catch (Exception e) {
				log.error("createAndLogonPinUser - Problems connecting to %s".formatted(useradminservice), e);
				throw new AuthenticationFailedException("Unable to find a user matching the given phonenumber.");
			}
		}
		throw new AuthenticationFailedException("Pin authentication failed. Status code ");
	}

	public UserToken getRefreshedUserToken(String usertokenid) {
		try {
			ApplicationToken stsApplicationToken = AuthenticatedApplicationTokenRepository.getSTSApplicationToken();
			String user = appConfig.getProperty("whydah.adminuser.username");
			String password = appConfig.getProperty("whydah.adminuser.password");
			UserCredential userCredential = new UserCredential(user, password);
			UserToken whydahUserAdminUserToken = logonUser(stsApplicationToken.getApplicationTokenId(), ApplicationTokenMapper.toXML(stsApplicationToken), userCredential.toXML());

			UserToken oldUserToken = AuthenticatedUserTokenRepository.getUserToken(usertokenid, stsApplicationToken.getApplicationTokenId());

			String userAggregateJson = new CommandGetUserAggregate(useradminservice, stsApplicationToken.getApplicationTokenId(), whydahUserAdminUserToken.getUserTokenId(), oldUserToken.getUid()).execute();

			UserToken refreshedUserToken = UserTokenMapper.fromUserAggregateJson(userAggregateJson);

			refreshedUserToken.setTimestamp(oldUserToken.getTimestamp());
			refreshedUserToken.setLifespan(oldUserToken.getLifespan());
			refreshedUserToken.setUserTokenId(usertokenid);

			return refreshedUserToken;
		} catch (Exception e) {
			log.warn("Unable to use STScredentials to refresh usertoken", e);
		}
		return null;

	}

	@Override
	public UserToken logonPinUser(String applicationtokenid, String appTokenXml, String adminUserTokenId, String cellPhone, String pin, long userTokenLifespan) {
		log.info("logonPinUser() called with " + "applicationtokenid = [" + applicationtokenid + "], appTokenXml = [" + appTokenXml + "], cellPhone = [" + cellPhone + "], pin = [" + pin + "]");
		if (ActivePinRepository.usePin(cellPhone, pin)) {
			String usersQuery = cellPhone;
			// produserer userJson. denne kan inneholde fler users dette er json av
			String usersJson = new CommandListUsers(useradminservice, applicationtokenid, adminUserTokenId, usersQuery).execute();

			if (usersJson == null) {
				log.error("Unable to find a user matching the given phonenumber.");

				slackNotifier.sendAlarm("Unable to find any user from the query " + usersQuery, 
						ContextMapBuilder.of(
								"location", "logonPinUser  method",
								"applicationtokenid", applicationtokenid, 
								"cellphone", cellPhone 
								));

				throw new AuthenticationFailedException("Unexpected exception occured. We unable to find a user from the query " + usersQuery);

			} else {
				log.info("CommandListUsers for query {} found users {}", usersQuery, usersJson);
			}



			UserToken userTokenIdentity = getFirstMatch(usersJson, usersQuery);
			if (userTokenIdentity != null) {
				log.info("Found matching UserIdentity {}", userTokenIdentity);

				String userAggregateJson = new CommandGetUserAggregate(useradminservice, applicationtokenid, adminUserTokenId, userTokenIdentity.getUid()).execute();

				UserToken userToken = UserTokenMapper.fromUserAggregateJson(userAggregateJson);
				userToken.setSecurityLevel("0");  // UserIdentity as source = securitylevel=0
				userToken.setTimestamp(String.valueOf(System.currentTimeMillis()));

				return AuthenticatedUserTokenRepository.addUserToken(userToken, applicationtokenid, "pin", userTokenLifespan);

			} else {
				log.error("Unable to find a user matching the given phonenumber.");
				throw new AuthenticationFailedException("Unable to find a user matching the given phonenumber.");
			}
		} else {
			log.warn("logonPinUser, illegal pin attempted - pin not registered");
			throw new AuthenticationFailedException("Pin authentication failed. Status code ");
		}

	}


	/**
	 * Method to enable pin-logon for whydah users
	 * Implements the following prioritizing
	 * a)  userName+cellPhone = number
	 * b)  userName = number
	 * c)  cellPhone=number
	 *
	 * @param usersJson
	 * @param cellPhone
	 * @return
	 */
	private UserToken getFirstMatch(String usersJson, String cellPhone) {
		log.info("Searching for: ", cellPhone);
		log.info("Searching in: ", usersJson);

		List<UserToken> userTokens = UserTokenFactory.fromUsersIdentityJson(usersJson);
		// First lets find complete matches
		for (UserToken userIdentity : userTokens) {
			if (cellPhone.equals(userIdentity.getCellPhone()) && cellPhone.equals(userIdentity.getUserName())) {
				return userIdentity;
			}
		}
		// The prioritize userName
		for (UserToken userIdentity : userTokens) {
			log.info("getFirstMatch: getUserName: " + userIdentity.getUserName());
			if (cellPhone.equals(userIdentity.getUserName())) {
				return userIdentity;
			}
		}
		// The and finally cellPhone users
		for (UserToken userIdentity : userTokens) {
			log.info("getFirstMatch: cellPhone: " + userIdentity.getCellPhone());
			if (cellPhone.equals(userIdentity.getCellPhone())) {
				return userIdentity;
			}
		}
		return null;
	}


	private static String generateID() {
		return UUID.randomUUID().toString();
	}

	@Override
	public UserToken logonWithTrustedUser(
			String applicationtokenid, 
			String appTokenXml, 
			String adminUserTokenId,
			String cellPhone, 
			String clientid) {

		log.info("logonWithTrustedUser() called with " + "applicationtokenid = [" + applicationtokenid + "], appTokenXml = [" + appTokenXml + "], cellPhone = [" + cellPhone + "], cientid = [" + clientid + "]");
		if (ActivePinRepository.isTrustedClientRegistered(clientid, cellPhone)) {
			String usersQuery = cellPhone;
			// produserer userJson. denne kan inneholde fler users dette er json av
			String usersJson = new CommandListUsers(useradminservice, applicationtokenid, adminUserTokenId, usersQuery).execute();


			if (usersJson == null) {
				log.error("Unable to find a user matching the given phonenumber.");

				slackNotifier.sendAlarm("Unable to find any user from the query " + usersQuery, 
						ContextMapBuilder.of(
								"location", "logonWithTrustedUser  method",
								"applicationtokenid", applicationtokenid, 
								"cellphone", cellPhone 
								));

				throw new AuthenticationFailedException("Unexpected exception occured. We unable to find a user from the query " + usersQuery);

			} else {
				log.info("CommandListUsers for query {} found users {}", usersQuery, usersJson);
			}

			UserToken userTokenIdentity = getFirstMatch(usersJson, usersQuery);
			if (userTokenIdentity != null) {
				log.info("Found matching UserIdentity {}", userTokenIdentity);

				String userAggregateJson = new CommandGetUserAggregate(useradminservice, applicationtokenid, adminUserTokenId, userTokenIdentity.getUid()).execute();

				UserToken userToken = UserTokenMapper.fromUserAggregateJson(userAggregateJson);
				userToken.setSecurityLevel("0");  // UserIdentity as source = securitylevel=0
				userToken.setTimestamp(String.valueOf(System.currentTimeMillis()));

				return AuthenticatedUserTokenRepository.addUserToken(userToken, applicationtokenid, "pin", 0);

			} else {
				log.error("Unable to find a user matching the given phonenumber.");
				throw new AuthenticationFailedException("Unable to find a user matching the given phonenumber.");
			}
		} else {
			log.warn("logonPinUser, illegal pin attempted - pin not registered");
			throw new AuthenticationFailedException("Pin authentication failed. Status code ");
		}

	}

	@Override
	public UserToken logonPinUserForTrustedUser(
			String applicationtokenid, 
			String appTokenXml,
			String adminUserTokenId, 
			String cellPhone, 
			String clientId, 
			String pin) {
		log.info("logonPinUserForTrustedUser() called with " + "applicationtokenid = [" + applicationtokenid + "], appTokenXml = [" + appTokenXml + "], cellPhone = [" + cellPhone + "], clientid = [" + clientId + "]");
		if (ActivePinRepository.usePinForTrustedClient(clientId, cellPhone, pin)) {
			String usersQuery = cellPhone;

			String usersJson = new CommandListUsers(useradminservice, applicationtokenid, adminUserTokenId, usersQuery).execute();

			if (usersJson == null) {
				log.error("Unable to find a user matching the given phonenumber.");

				slackNotifier.sendAlarm("Unable to find any user from the query " + usersQuery, 
						ContextMapBuilder.of(
								"location", "logonPinUserForTrustedUser  method",
								"applicationtokenid", applicationtokenid, 
								"cellphone", cellPhone 
								));

				throw new AuthenticationFailedException("Unexpected exception occured. We unable to find a user from the query " + usersQuery);

			} else {
				log.info("CommandListUsers for query {} found users {}", usersQuery, usersJson);
			}

			UserToken userTokenIdentity = getFirstMatch(usersJson, usersQuery);
			if (userTokenIdentity != null) {
				log.info("Found matching UserIdentity {}", userTokenIdentity);

				String userAggregateJson = new CommandGetUserAggregate(useradminservice, applicationtokenid, adminUserTokenId, userTokenIdentity.getUid()).execute();

				UserToken userToken = UserTokenMapper.fromUserAggregateJson(userAggregateJson);
				userToken.setSecurityLevel("0");  // UserIdentity as source = securitylevel=0
				userToken.setTimestamp(String.valueOf(System.currentTimeMillis()));

				return AuthenticatedUserTokenRepository.addUserToken(userToken, applicationtokenid, "pin", 0);

			} else {
				log.error("Unable to find a user matching the given phonenumber.");
				throw new AuthenticationFailedException("Unable to find a user matching the given phonenumber.");
			}
		} else {
			log.warn("logonPinUser, illegal pin attempted - pin not registered");
			throw new AuthenticationFailedException("Pin authentication failed. Status code ");
		}
	}

	@Override
	public UserToken logonUserUsingSharedSTSSecret(String applicationtokenid, String appTokenXml, String adminUserTokenId,
			String cellPhone, String secret, long userTokenLifespan) {
		log.info("logonUserUsingSharedSTSSecret() called with " + "applicationtokenid = [" + applicationtokenid + "], appTokenXml = [" + appTokenXml + "], cellPhone = [" + cellPhone + "], secrect = [" + secret + "]");
		if (AppConfig.getProperty("ssolwa_sts_shared_secrect").equals(secret)) {
			String usersQuery = cellPhone;

			String usersJson = null;
			int maxAttempts = 5;

			for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			    usersJson = new CommandListUsers(useradminservice, applicationtokenid, adminUserTokenId, usersQuery).execute();
			    
			    if (usersJson != null) {
			        break; // Success!
			    }
			    
			    if (attempt < maxAttempts) {
			        try {
			            Thread.sleep(100);
			        } catch (InterruptedException e) {
			            Thread.currentThread().interrupt();
			            log.warn("Retry interrupted at attempt {}", attempt);
			            break;
			        }
			    }
			}

			if (usersJson == null) {
				log.error("Unable to find a user matching the given phonenumber.");

				slackNotifier.sendAlarm("Unable to find any user from the query " + usersQuery, 
						ContextMapBuilder.of(
								"location", "logonUserUsingSharedSTSSecret  method",
								"applicationtokenid", applicationtokenid, 
								"cellphone", cellPhone 
								));

				throw new AuthenticationFailedException("Unexpected exception occured. We unable to find a user from the query " + usersQuery);

			} else {
				log.info("CommandListUsers for query {} found users {}", usersQuery, usersJson);
			}

			UserToken userTokenIdentity = getFirstMatch(usersJson, usersQuery);
			if (userTokenIdentity != null) {
				log.info("Found matching UserIdentity {}", userTokenIdentity);

				String userAggregateJson = new CommandGetUserAggregate(useradminservice, applicationtokenid, adminUserTokenId, userTokenIdentity.getUid()).execute();

				UserToken userToken = UserTokenMapper.fromUserAggregateJson(userAggregateJson);
				userToken.setSecurityLevel("2");  // UserIdentity as source = securitylevel=0
				userToken.setTimestamp(String.valueOf(System.currentTimeMillis()));

				return AuthenticatedUserTokenRepository.addUserToken(userToken, applicationtokenid, "pin", userTokenLifespan);

			} else {
				log.error("Unable to find a user matching the given phonenumber.");
				throw new AuthenticationFailedException("Unable to find a user matching the given phonenumber.");
			}
		} else {
			log.warn("logonUserUsingSharedSTSSecret, illegal attempted");
			throw new AuthenticationFailedException("Pin authentication failed. Status code ");
		}
	}


}
