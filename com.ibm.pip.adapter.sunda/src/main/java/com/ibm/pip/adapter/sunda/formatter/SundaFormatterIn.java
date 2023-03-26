package com.ibm.pip.adapter.sunda.formatter;


import java.util.HashMap;

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


public class SundaFormatterIn extends InFormatter {

	protected String LOG_PREFIX = "SundaFormatter";
	protected XSLFormatter xslformatter = null;

	/**
	 * 覆盖本构造器，添加其他必要参数
	 * 
	 * @throws EsbException
	 */
	public SundaFormatterIn() throws EsbException {
		super();

		// 设置应用系统编号
		appSysID = (String) ConfigManager.getInstance().get(
				IDataFormatter.ADAPTER_SYSID);
		if (appSysID == null)
			throw new EsbException(FrameworkErr.MESSAGE_HEADER_ERRCODE,
					"-Lack appSysID");

	}

	public MsgObject format(byte[] clientRequest,
			HashMap<String, Object> hashmap) throws EsbException {
		Log.getInstance()
				.stdDebug("Adapter--" + LOG_PREFIX + "--format--Entry");
		
		MsgObject mo = null;
		try {
			mo = new MsgObject(DataProcParameter.SERVICE_TYPE_REQUESTER);// 服务请求方MO格式
		} catch (Exception ex) {
			Log.getInstance().stdError(ex);
			throw new EsbException(FrameworkErr.MO_INIT_ERRCODE, ex);
		}
		
		if (clientRequest == null) {
			throw new EsbException(FrameworkErr.MESSAGE_NULL_ERRCODE,
					":clientRequest");
	}
		Log.getInstance().bizDebug("request data : " + clientRequest, null);

		DataProcUtil.orgEsbService(mo, hashmap, appSysID, cityCode);
		String msg = new String(clientRequest);
		

		mo.setReqExtAttribute(IBMMSG.REQ_SERVICE_ID, (String)hashmap.get(IBMMSG.REQ_SERVICE_ID));

		try {			
			mo.setXmlToRequest(msg);
		} catch (Exception e) {
			Log.getInstance().stdError(e);
		}//end


		Log.getInstance().stdDebug("Adapter--" + LOG_PREFIX + "--format--Exit");
		return mo;
	}


}
