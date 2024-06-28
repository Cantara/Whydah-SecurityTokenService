package net.whydah.sts.util;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import kong.unirest.Unirest;
import net.whydah.sso.user.types.UserIdentity;
import net.whydah.sso.user.types.UserToken;
import net.whydah.sts.config.AppConfig;

public class LogonTimeRepoter {
	public static final Logger log = LoggerFactory.getLogger(LogonTimeRepoter.class);
	ScheduledExecutorService logontime_update_scheduler;
	int UPDATE_CHECK_INTERVAL_IN_SECONDS = 30;
	int BATCH_UPDATE_SIZE = 10;
	
	private Queue<UserIdentity> _queues = new LinkedList<>();

	private String USS_URL = new AppConfig().getProperty("uss.url");
	private String USS_ACCESSTOKEN = new AppConfig().getProperty("uss.accesstoken");
			  
	public LogonTimeRepoter() {
		
		logontime_update_scheduler = Executors.newScheduledThreadPool(1);
		logontime_update_scheduler.scheduleWithFixedDelay(() -> {
			try {

				List<UserIdentity> list = new ArrayList<UserIdentity>();
				while (!_queues.isEmpty() && list.size() < BATCH_UPDATE_SIZE) {
					try {
						UserIdentity n = _queues.poll();

						list.add(n);
						
					} catch (Exception ex) {						
					}
				}
				if(list.size()>0) {
					Unirest.post(USS_URL + "api/" + USS_ACCESSTOKEN + "/update").body(list).asEmpty();
				}

			} catch (Exception e) {
				e.printStackTrace();
				log.error("unexpected error", e);
			}

		}, 5, UPDATE_CHECK_INTERVAL_IN_SECONDS, TimeUnit.SECONDS);

	}

	public void update(UserToken user) {

		UserIdentity u = new UserIdentity();
		u.setCellPhone(user.getCellPhone());
		u.setEmail(user.getEmail());
		u.setFirstName(user.getFirstName());
		u.setLastName(user.getLastName());
		u.setPersonRef(user.getPersonRef());
		u.setUid(user.getUid());
		u.setUsername(user.getUserName());

		_queues.offer(u);
	}

}
