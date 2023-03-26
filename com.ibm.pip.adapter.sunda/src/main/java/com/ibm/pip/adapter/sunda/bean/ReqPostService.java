package com.ibm.pip.adapter.sunda.bean;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.alibaba.fastjson.JSON;
import com.ibm.pip.adapter.exec.DefaultEventAppsExec;
import com.ibm.pip.adapter.sunda.utils.sap.RfcManager;
import com.ibm.pip.adapter.sunda.utils.sendmq.MQUtil;
import com.ibm.pip.framework.exception.EsbException;
import com.ibm.pip.framework.messageobject.ServiceRequest;
import com.ibm.pip.framework.messageobject.ServiceResponse;
import com.ibm.pip.util.log.Log;
import com.sap.conn.jco.JCoFunction;
import com.sap.conn.jco.JCoParameterList;
import com.sap.conn.jco.JCoStructure;
import com.sap.conn.jco.JCoTable;

public class ReqPostService extends DefaultEventAppsExec {
	private static final String LOG_PREFIX = "ReqPostService";

	public ReqPostService() throws EsbException {
		super();

	}

	@SuppressWarnings("unchecked")
	public void executeBeforeSend(ServiceRequest servReq) throws EsbException {

		Log.getInstance().stdDebug(LOG_PREFIX + " executeBeforeSend start");

		servReq.setTargetCon("SAP");
		
		
		Log.getInstance().stdDebug(LOG_PREFIX + " executeBeforeSend start");

	}

	public void executeAfterSend(ServiceResponse servResp) throws EsbException {

		Log.getInstance().stdDebug(LOG_PREFIX + " executeBeforeSend start");

		servResp.getMo().setServiceID("00020000000001");
		
		

		Log.getInstance().stdDebug(LOG_PREFIX + " executeBeforeSend start");

	}

	public void handleBeforeSendException(ServiceRequest servReq) {

	}

	public void handleAfterSendException(ServiceResponse servResp) {

	}

}
