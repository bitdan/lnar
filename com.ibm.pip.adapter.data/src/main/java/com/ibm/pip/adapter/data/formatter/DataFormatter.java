package com.ibm.pip.adapter.data.formatter;


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


public class DataFormatter extends InFormatter {

	protected String LOG_PREFIX = "DataFormatter";
	protected XSLFormatter xslformatter = null;

	/**
	 * 瑕嗙洊鏈瀯閫犲櫒锛屾坊鍔犲叾浠栧繀瑕佸弬鏁�
	 * 
	 * @throws EsbException
	 */
	public DataFormatter() throws EsbException {
		super();

		// 璁剧疆搴旂敤绯荤粺缂栧彿
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
			throw new EsbException(FrameworkErr.MO_INIT_ERRCODE, ex);
		}

		if (clientRequest == null) {
			throw new EsbException(FrameworkErr.MESSAGE_NULL_ERRCODE,
					":clientRequest");
		}
		Log.getInstance().bizDebug("request data : " + clientRequest, null);
		
		DataProcUtil.orgEsbService(mo, hashmap, appSysID, cityCode);
		String msg = new String(clientRequest);
		if(!msg.startsWith("<DATA>"))
		{

			msg = msg.replaceAll("&", "&amp;");
			msg = msg.replaceAll("<", "&lt;");
			msg = msg.replaceAll(">", "&gt;");
			msg = msg.replaceAll("'", "&apos;");
			msg = msg.replaceAll("\"", "&quot;");
			
			msg = "<DATA>" + msg +"</DATA>";
		}
		String haid = msg.substring(msg.indexOf("@$haidbg")+8,msg.indexOf("haidbg$@"));
		Log.getInstance().stdDebug("haid ============ " + haid);
		mo.setReqExtAttribute(IBMMSG.REQ_SERVICE_ID, (String)hashmap.get(IBMMSG.REQ_SERVICE_ID));
		mo.setRequesterHaID(haid);
		
		try {			
			mo.setXmlToRequest(msg);
		} catch (Exception e) {
			e.printStackTrace();
		}//end
		

		Log.getInstance().stdDebug("Adapter--" + LOG_PREFIX + "--format--Exit");
		return mo;
	}
	

}
