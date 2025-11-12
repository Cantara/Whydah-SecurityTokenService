package net.whydah.sts.util;


import java.util.Date;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

public class DelayedTaskTest {


	@Disabled
    @Test
    public void testDelayedTask() throws Exception {
        System.out.println("About to schedule task." + new Date().toString());
        long timestamp = new Date().getTime() + 3000;
        new DelayedSendSMSTask(timestamp,"", "", "");  // Will fail sms commands, but runs as a test
        System.out.println("Task scheduled." + new Date().toString());

        Thread.sleep(8000);
    }

}

