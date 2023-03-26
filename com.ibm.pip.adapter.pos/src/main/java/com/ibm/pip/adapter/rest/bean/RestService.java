package com.ibm.pip.adapter.rest.bean;


import java.sql.SQLException;
import java.util.HashMap;

import com.ibm.pip.adapter.connection.redisclient.SessionMap;
import com.ibm.pip.adapter.exec.DefaultEventAppsExec;
import com.ibm.pip.adapter.formatter.IDataFormatter;
import com.ibm.pip.framework.buffer.AbstractMsgReceiveSend;
import com.ibm.pip.framework.buffer.IMsgBuffer;
import com.ibm.pip.framework.config.ConfigManager;
import com.ibm.pip.framework.exception.EsbException;
import com.ibm.pip.framework.exception.FrameworkErr;
import com.ibm.pip.framework.messageobject.IBMMSG;
import com.ibm.pip.framework.messageobject.ServiceRequest;
import com.ibm.pip.framework.messageobject.ServiceResponse;
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
public class RestService extends DefaultEventAppsExec {

	private static final String LOG_PROFIX = "RestService";

	protected SessionMap sessionMap = null;
	
	protected String appSysID = null;
	protected String adapterName = null;
	protected String instID = null;
	
	public RestService() throws EsbException {
		super();
		this.sessionMap = (SessionMap) ConfigManager.getInstance().get("SessionMap.Instance");
		
		appSysID = (String) ConfigManager.getInstance().get(IDataFormatter.ADAPTER_SYSID);
		if(appSysID == null)
			throw new EsbException(FrameworkErr.CONFIGMGR_LACK_PARAMETER_EERRCODE,"-Lack appSysID");

		
		instID = (String) ConfigManager.getInstance().get(IDataFormatter.ADAPTER_INSTID);
		if (instID == null)
			throw new EsbException(FrameworkErr.CONFIGMGR_LACK_PARAMETER_EERRCODE,"-Lack instID");
		
	}
	
	public void executeBeforeSend(ServiceRequest servReq)
			throws EsbException {
		
		Log.getInstance().stdDebug(LOG_PROFIX +" executeBeforeSend start");
		String applicationId = null;
		if(servReq.getOrginalCon().startsWith("HttpServer"))
		{
			applicationId =  "00010000000001";
			sessionMap.set(servReq.getMo().getMID(), servReq.getIOSession());
			sessionMap.set(String.valueOf(servReq.getIOSession().getId()), servReq.getMo().getMID());
			servReq.getMo().setHeaderParameter("ha_id", appSysID+instID);
			Log.getInstance().stdDebug(LOG_PROFIX +" executeBeforeSend 00010000000001");
			Log.getInstance().stdDebug(servReq.getMo().toString());
		}
		if(servReq.getOrginalCon().startsWith("PROVIDER_MQServer_RealTime"))
		{
			applicationId =  "00010000000001";
			servReq.getMo().setHeaderParameter("ha_id", appSysID+instID);
			Log.getInstance().stdDebug(LOG_PROFIX +" executeBeforeSend 00010000000001");
			Log.getInstance().stdDebug(servReq.getMo().toString());
		}
		else
		{
			applicationId =  "00030000000001";
			servReq.getMo().setHeaderParameter("ha_id", appSysID+instID);
			Log.getInstance().stdDebug(LOG_PROFIX +" executeBeforeSend 00030000000001");
		}
		
		//servReq.getMo().removeExtAttributes(IBMMSG.REQ_SERVICE_ID);
		servReq.getMo().setServiceID(applicationId);
		servReq.setExecId(applicationId);
			
		
			
		Log.getInstance().stdError(LOG_PROFIX + " executeBeforeSend end");

	}
	
	
	public void executeAfterSend(ServiceResponse servReq)
			throws EsbException {
		
		Log.getInstance().stdDebug(LOG_PROFIX + " executeAfterSend start");

		
		Log.getInstance().stdError(LOG_PROFIX + " executeAfterSend end");
		
	}
	
	
	
}
