package com.ibm.pip.adapter.data.timer;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;

import org.apache.commons.lang.time.DateFormatUtils;

import com.ibm.pip.adapter.data.util.ResTemplete;
import com.ibm.pip.framework.buffer.AbstractMsgReceiveSend;
import com.ibm.pip.framework.buffer.IMsgBuffer;
import com.ibm.pip.framework.exception.EsbException;
import com.ibm.pip.framework.messageobject.ServiceResponse;
import com.ibm.pip.framework.timer.BaseJob;

public abstract class BaseDataTimer extends BaseJob {
	
	private String rfcName = null;
	private String tablename = null;
	private IMsgBuffer replyBuffer = null;
	
	public void init(String rfcName, String tablename, IMsgBuffer replyBuffer)
	{
		this.rfcName = rfcName;
		this.tablename = tablename;
		this.replyBuffer = replyBuffer;
		
	}

	@Override
	public void run() {
		
		String message  =  ResTemplete.restempleteData;
		String messageId = UUID.randomUUID().toString();
		message = message.replace("$Transaction_ID$", messageId);
		message = message.replace("$rfcName$", this.rfcName);
		message = message.replace("$tableName$", this.tablename);
		message = message.replace("$date$", DateFormatUtils.format(new Date(), "yyyy-MM-dd") );
		message = message.replace("$tableName$", this.tablename);
		message = message.replace("$isSyn$", "0");
		
		ServiceResponse res = new ServiceResponse();
		
		res.setMsgId(messageId.getBytes());
		res.setResponse(message.getBytes());
		send(res);
		
	}

	public abstract void send(ServiceResponse message);

}
