package com.ibm.pip.adapter.sunda.bean;

import java.io.IOException;

import com.ibm.pip.adapter.exec.DefaultEventAppsExec;
import com.ibm.pip.framework.exception.EsbException;
import com.ibm.pip.framework.messageobject.ServiceRequest;
import com.ibm.pip.framework.messageobject.ServiceResponse;
import com.ibm.pip.framework.messageobject.domparser.MsgObject;
import com.ibm.pip.util.log.Log;



public class ReqService extends DefaultEventAppsExec {
	private static final String LOG_PREFIX = "ReqService";
	

	public ReqService() throws EsbException {
		super();
		
	}

	public void executeBeforeSend(ServiceRequest servReq) throws EsbException {

		Log.getInstance().stdDebug(LOG_PREFIX + " executeBeforeSend start");
		
		servReq.getMo().setServiceID("00030000000001");
		
		Log.getInstance().stdDebug(LOG_PREFIX + " executeBeforeSend start");
		
		
	}

	public void executeAfterSend(ServiceResponse servResp) throws EsbException {

	}

	public void handleBeforeSendException(ServiceRequest servReq) {

	}

	public void handleAfterSendException(ServiceResponse servResp) {

	}


	
}
