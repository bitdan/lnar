package com.ibm.pip.adapter.data.timer;

import com.ibm.pip.framework.buffer.AbstractMsgReceiveSend;
import com.ibm.pip.framework.buffer.IMsgBuffer;
import com.ibm.pip.framework.exception.EsbException;
import com.ibm.pip.framework.messageobject.ServiceResponse;
import com.ibm.pip.util.log.Log;

public class TableDataTimer extends BaseDataTimer {
	
	private static IMsgBuffer replyBuffer = null;
	
	public static IMsgBuffer getBufferInstance()
	{
		if(replyBuffer == null )
			try {
				replyBuffer = AbstractMsgReceiveSend.getMsgBufferInstance("SendBuffer.Instance");
			} catch (EsbException e) {			
				Log.getInstance().stdError(e);
			}
		return replyBuffer;
	}
	
	public TableDataTimer() throws EsbException
	{
		String  rpcName = "Z_RFC_STORE_01";
		
		super.init(rpcName, "IT_MAKT", replyBuffer);
	}

	@Override
	public void send(ServiceResponse message) {
		// TODO Auto-generated method stub
		message.setReplyToManagerName("PIP_OUT");
		message.setReplyToQueueName("Q_BANCS_OUT");
		this.getBufferInstance().put(message);
	}

}
