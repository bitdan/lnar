package com.ibm.pip.adapter.rest.formatter;

import java.util.HashMap;
import java.util.Map;

import com.alibaba.fastjson.JSON;
import com.ibm.pip.adapter.formatter.AbstractFormatter;
import com.ibm.pip.adapter.formatter.DataProcParameter;
import com.ibm.pip.adapter.formatter.DataProcUtil;
import com.ibm.pip.adapter.formatter.DataTypeProcess;
import com.ibm.pip.adapter.formatter.IDataFormatter;
import com.ibm.pip.adapter.formatter.XSLFormatter;
import com.ibm.pip.framework.cache.CacheConstants;
import com.ibm.pip.framework.cache.vo.RtnCodeMsg;
import com.ibm.pip.framework.config.ConfigManager;
import com.ibm.pip.framework.exception.EsbException;
import com.ibm.pip.framework.exception.FrameworkErr;
import com.ibm.pip.framework.messageobject.IBMMSG;
import com.ibm.pip.framework.messageobject.ServiceRequest;
import com.ibm.pip.framework.messageobject.domparser.MsgObject;
import com.ibm.pip.lccommon.formatter.InFormatter;
import com.ibm.pip.util.log.Log;

public class RestInFormatter extends InFormatter {

	protected String LOG_PREFIX = "RestInFormatter";

	/**
	 * 覆盖本构造器，添加其他必要参数
	 * 
	 * @throws EsbException
	 */
	public RestInFormatter() throws EsbException {
		super();

		// 设置应用系统编号
		appSysID = (String) ConfigManager.getInstance().get(IDataFormatter.ADAPTER_SYSID);
		if (appSysID == null)
			throw new EsbException(FrameworkErr.MESSAGE_HEADER_ERRCODE, "-Lack appSysID");

	}

	public MsgObject format(byte[] clientRequest, HashMap<String, Object> hashmap) throws EsbException {
		Log.getInstance().stdDebug("Adapter--" + LOG_PREFIX + "--format--Entry");

		MsgObject mo = null;
		try {
			mo = new MsgObject(DataProcParameter.SERVICE_TYPE_REQUESTER);// 服务请求方MO格式
		} catch (Exception ex) {
			throw new EsbException(FrameworkErr.MO_INIT_ERRCODE, ex);
		}

		if (clientRequest == null) {
			throw new EsbException(FrameworkErr.MESSAGE_NULL_ERRCODE, ":clientRequest");
		}
		Log.getInstance().bizDebug("request data : " + clientRequest, null);

		DataProcUtil.orgEsbService(mo, hashmap, appSysID, cityCode);
		
		String msg = new String(clientRequest);
		Map mapType = JSON.parseObject(msg, Map.class);
		
		String applicationId = (String)((Map)((Map)mapType.get("REQUEST")).get("ESB_ATTRS")).get("Application_ID");
		String transactionId = (String)((Map)((Map)mapType.get("REQUEST")).get("ESB_ATTRS")).get("Transaction_ID");
		
		
		mo.setReqExtAttribute(IBMMSG.REQ_SERVICE_ID, applicationId);
		mo.setMID(applicationId + transactionId);
		

		msg = msg.replaceAll("&", "&amp;");
		msg = msg.replaceAll("<", "&lt;");
		msg = msg.replaceAll(">", "&gt;");
		msg = msg.replaceAll("'", "&apos;");
		msg = msg.replaceAll("\"", "&quot;");
		

		try {
			Log.getInstance().stdDebug("request data msg  == " + msg);
			mo.setXmlToRequest("<DATA>"+msg+"</DATA>");
		} catch (Exception e) {
			Log.getInstance().bizDebug("request data setXmlToRequest ", e);
		} // end

		Log.getInstance().stdDebug("Adapter--" + LOG_PREFIX + "--format--Exit");
		return mo;
	}

}
