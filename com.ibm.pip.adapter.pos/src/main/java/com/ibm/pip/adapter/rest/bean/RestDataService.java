package com.ibm.pip.adapter.rest.bean;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import org.apache.mina.core.session.IoSession;

import com.alibaba.fastjson.JSON;
import com.ibm.pip.adapter.connection.redisclient.SessionMap;
import com.ibm.pip.adapter.exec.DefaultEventAppsExec;
import com.ibm.pip.adapter.rest.util.ResTemplete;
import com.ibm.pip.framework.buffer.AbstractMsgReceiveSend;
import com.ibm.pip.framework.buffer.IMsgBuffer;
import com.ibm.pip.framework.config.ConfigManager;
import com.ibm.pip.framework.exception.EsbException;
import com.ibm.pip.framework.handler.BaseEasHandlerUtil;
import com.ibm.pip.framework.messageobject.IBMMSG;
import com.ibm.pip.framework.messageobject.ServiceRequest;
import com.ibm.pip.framework.messageobject.ServiceResponse;
import com.ibm.pip.framework.messageobject.domparser.MsgObject;
import com.ibm.pip.lccommon.db.CommonDao;
import com.ibm.pip.lccommon.entity.Pipincomingrecord;
import com.ibm.pip.util.CommonUtils;
import com.ibm.pip.util.log.Log;

/**
 * @version 1.0
 * @author linjn
 * @WrittenDate:2016年1月6日
 * @ModifiedBy:
 * @ModifiedDate:
 */
public class RestDataService extends DefaultEventAppsExec {

	private static final String LOG_PROFIX = "RestDataService";

	protected SessionMap sessionMap = null;

	protected IMsgBuffer sendBuffer = null;
	
	

	public RestDataService() throws EsbException {
		super();
		this.sessionMap = (SessionMap) ConfigManager.getInstance().get("SessionMap.Instance");

		this.sendBuffer = AbstractMsgReceiveSend.getMsgBufferInstance("RESTSendBuffer.Instance");

	}

	public void executeBeforeSend(ServiceRequest servReq) throws EsbException {

		Log.getInstance().stdDebug(LOG_PROFIX + " executeBeforeSend start   " + servReq.getMo().getMID());

		Map mapType = JSON.parseObject(servReq.getMo().getRequestParameter("DATA"), Map.class);
		
		
		String reqAppId = null;
		try {
			reqAppId = (String)((Map)((Map)mapType.get("REQUEST")).get("RequestData")).get("ReqAppId");
		} catch (Exception e) {
			Log.getInstance().stdError(e);
			reqAppId = "";
		}
		String targetCon = (String)ConfigManager.getInstance().get(reqAppId);
		
		if(targetCon ==  null)
		{
			if("".equals(reqAppId))
				targetCon = (String)ConfigManager.getInstance().get("POS");
			else
				targetCon = "POS";
		}

		servReq.setTargetCon(targetCon);
		
		Log.getInstance().stdDebug(LOG_PROFIX + " executeBeforeSend end   " +  servReq.getMo().getMID());

	}

	public void executeAfterSend(ServiceResponse servReq) throws EsbException {

		Log.getInstance().stdDebug(LOG_PROFIX + " executeAfterSend start");

		Log.getInstance().stdError(LOG_PROFIX + " executeAfterSend end");

	}

}
