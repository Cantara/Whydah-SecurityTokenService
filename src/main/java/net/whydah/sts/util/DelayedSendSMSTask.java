package net.whydah.sts.util;

import net.whydah.sso.commands.adminapi.user.CommandSendSMSToUser;
import net.whydah.sts.smsgw.SMSGatewayCommandFactory;
import net.whydah.sts.user.authentication.ActivePinRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;


public class DelayedSendSMSTask {
    Toolkit toolkit;

    Timer timer;

    String cellNo;
    String smsMessage;
    String tag;
    private static final Logger log = LoggerFactory.getLogger(DelayedSendSMSTask.class);

    public DelayedSendSMSTask(long timestamp, String cellNo, String smsMessage, String tag) {

        this.cellNo = cellNo;
        this.smsMessage = smsMessage;
        this.tag = tag;
        
        toolkit = Toolkit.getDefaultToolkit();
        timer = new Timer();
        long milliseconds = timestamp - new Date().getTime();
        log.debug("Task started, waiting {} milliseconds", milliseconds);
        timer.schedule(new RemindTask(), milliseconds);
    }

    class RemindTask extends TimerTask {
        public void run() {
            log.debug("Task completed. cellNo:{}   message:{}  time:{}", cellNo, smsMessage, new Date().toString());
            String response = SMSGatewayCommandFactory.getInstance().createSendSMSCommand(cellNo, smsMessage, tag).execute();
            if(response!=null && !response.isEmpty()) {
            	log.trace("Answer from smsgw: " + response);
            	ActivePinRepository.setDLR(cellNo, response);
            }
            
            timer.cancel();
        }
    }

}