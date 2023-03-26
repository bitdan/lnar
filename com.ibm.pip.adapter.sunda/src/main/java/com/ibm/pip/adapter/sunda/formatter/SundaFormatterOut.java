package com.ibm.pip.adapter.sunda.formatter;

import java.util.HashMap;
import java.util.List;

import com.ibm.pip.adapter.formatter.DataProcParameter;
import com.ibm.pip.adapter.formatter.DataProcUtil;
import com.ibm.pip.adapter.formatter.DataTypeProcess;
import com.ibm.pip.adapter.formatter.IDataFormatter;
import com.ibm.pip.framework.cache.vo.FieldProperty;
import com.ibm.pip.framework.cache.vo.MessageStructure;
import com.ibm.pip.framework.config.ConfigManager;
import com.ibm.pip.framework.exception.EsbException;
import com.ibm.pip.framework.exception.FrameworkErr;

import com.ibm.pip.framework.messageobject.IBMMSG;

import com.ibm.pip.framework.messageobject.domparser.MsgObject;
import com.ibm.pip.lccommon.formatter.OutFormatter;

import com.ibm.pip.util.log.Log;

public class SundaFormatterOut extends OutFormatter {

	protected String LOG_PREFIX = "RestFormatter";

	/**
	 * 覆盖本构造器，添加其他必要参数
	 * 
	 * @throws EsbException
	 */
	public SundaFormatterOut() throws EsbException {
		super();

		// 设置应用系统编号
		appSysID = (String) ConfigManager.getInstance().get(
				IDataFormatter.ADAPTER_SYSID);
		if (appSysID == null)
			throw new EsbException(FrameworkErr.MESSAGE_HEADER_ERRCODE,
					"-Lack appSysID");

	}
	@Override
	/**
	 * 主要用于提供方适配器
	 * 用来格式化请求数据，以符合后台的数据格式
	 * 要求解析报文时不要抛出异常,返回的结果为byte[]，或者异常为null
	 */
	public byte[] format(MsgObject mo) throws EsbException {
		Log.getInstance().stdDebug(LOG_PREFIX + "--provider--format--Entry");

		byte[] result = null;

		// 读取服务提供方的相应报文service id从mo
		String Service_ID = mo.getCurrentProcessProperty(IBMMSG.SERVICE_ID);
		if (Service_ID == null)
			throw new EsbException(FrameworkErr.MESSAGE_HEADER_ERRCODE,
					"--缺少service_id");


		String pMntmsg = null;

		try {
			pMntmsg = mo.getRequestParameter("DATA");
			result = pMntmsg.getBytes();
		} catch (Exception e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
			Log.getInstance().stdError("Formatter get request message error");
			Log.getInstance().stdError(e1);
		}
		
		
		Log.getInstance().stdDebug(
				"Adapter--" + LOG_PREFIX + "--format(mo)--Exit");
		return result;

	}
	
	public void unformat(MsgObject mo, byte[] hostReply) throws EsbException {

		Log.getInstance().stdDebug(
				"Adapter--" + LOG_PREFIX + "--unformat(mo,hostReply)--Entry");

		mo.setRequestParameter("RESDATA", new String(hostReply));
		mo.setRequestParameter("RESCODE", "00000000");
		
		Log.getInstance().stdDebug(
				"Adapter--" + LOG_PREFIX + "--unformat(mo,hostReply)--Exit!");
	
		
		
	
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
