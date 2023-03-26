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
public class RestSapService extends DefaultEventAppsExec {

	private static final String LOG_PROFIX = "RestSapService";

	protected SessionMap sessionMap = null;

	protected IMsgBuffer sendBuffer = null;
	
	

	public RestSapService() throws EsbException {
		super();
		this.sessionMap = (SessionMap) ConfigManager.getInstance().get("SessionMap.Instance");

		this.sendBuffer = AbstractMsgReceiveSend.getMsgBufferInstance("RESTSendBuffer.Instance");

	}

	public void executeBeforeSend(ServiceRequest servReq) throws EsbException {

		Log.getInstance().stdDebug(LOG_PROFIX + " executeBeforeSend start");


		servReq.setTargetCon("PSEUDO");

		ServiceResponse res = BaseEasHandlerUtil.getServiceResponse(servReq);

		try {
			
			Map mapType = JSON.parseObject(res.getMo().getRequestParameter("DATA"), Map.class);
			
			
			String tmp = res.getMo().getRequestParameter("RESDATA");
			
			String errorinfo = "";
			
			if(res.getMo().getRequestParameter("ISERROR") != null)
			{
				errorinfo = tmp.substring(0, tmp.indexOf("["));
				tmp = tmp.substring(tmp.indexOf("[")+1, tmp.indexOf("]"));
				if(!tmp.startsWith("{") ||  !tmp.endsWith("}"))
				{
					tmp = "\""+tmp + "\"";
				}
			}
				
			String resMsg = ResTemplete.restemplete.replace("$ResTemplete$", tmp);
			resMsg = resMsg.replace("$ResDesc$", errorinfo);
			if(res.getMo().getRequestParameter("RESCODE") != null)
			{
				resMsg = resMsg.replace("$ResCode$", res.getMo().getRequestParameter("RESCODE"));
				String type = (String)((Map)((Map)mapType.get("REQUEST")).get("REQUEST_DATA")).get("Type");
				if(type != null)
					resMsg = resMsg.replace("$Type$", type);
				else
					resMsg = resMsg.replace("$Type$", "not have type");
				resMsg = resMsg.replace("$Transaction_ID$", (String)((Map)((Map)mapType.get("REQUEST")).get("ESB_ATTRS")).get("Transaction_ID"));
				resMsg = resMsg.replace("$Application_ID$", (String)((Map)((Map)mapType.get("REQUEST")).get("ESB_ATTRS")).get("Application_ID"));
			}
			else
			{
				resMsg = resMsg.replace("$ResCode$", "1000001") ;
			}
			if (sessionMap != null) {
				IoSession ioSession = (IoSession) this.sessionMap.getAndRemove(servReq.getMo().getMID());
				if(ioSession != null)
					this.sessionMap.getAndRemove(String.valueOf(ioSession.getId()));
				res.setIOSession(ioSession);
			}
			else
			{
				throw new Exception("sessionMap = null");
			}
			
			Map aa = JSON.parseObject(resMsg, Map.class);
			
			
			res.setResponse(resMsg.getBytes());
		} catch (Exception e1) {
			// TODO Auto-generated catch block
			Log.getInstance().stdError(LOG_PROFIX + " executeBeforeSend end", e1);
			Log.getInstance().stdError(e1);
			return;
		}

		if(res.getIOSession() == null || res.getIOSession().isClosing())
		{
			Log.getInstance().stdDebug(LOG_PROFIX + " IOSession is Closing not send message executeBeforeSend end");
		}
		this.sendBuffer.put(res);
		Log.getInstance().stdDebug(LOG_PROFIX + " executeBeforeSend end");

	}

	public void executeAfterSend(ServiceResponse servReq) throws EsbException {

		Log.getInstance().stdDebug(LOG_PROFIX + " executeAfterSend start");

		Log.getInstance().stdError(LOG_PROFIX + " executeAfterSend end");

	}

}
