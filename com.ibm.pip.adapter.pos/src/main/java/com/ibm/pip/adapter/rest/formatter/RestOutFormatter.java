package com.ibm.pip.adapter.rest.formatter;

import com.ibm.pip.adapter.formatter.IDataFormatter;

import com.ibm.pip.framework.config.ConfigManager;
import com.ibm.pip.framework.exception.EsbException;
import com.ibm.pip.framework.exception.FrameworkErr;

import com.ibm.pip.framework.messageobject.IBMMSG;

import com.ibm.pip.framework.messageobject.domparser.MsgObject;
import com.ibm.pip.lccommon.formatter.OutFormatter;

import com.ibm.pip.util.log.Log;

public class RestOutFormatter extends OutFormatter {

	protected String LOG_PREFIX = "RestOutFormatter";

	/**
	 * 覆盖本构造器，添加其他必要参数
	 * 
	 * @throws EsbException
	 */
	public RestOutFormatter() throws EsbException {
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
//		String eXternalSysFields=null;

		try {
			result = mo.getRequestParameter("DATA").getBytes();
		} catch (Exception e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
			Log.getInstance().stdError("Formatter get request message error");
		}
		
		
		Log.getInstance().stdDebug(
				"Adapter--" + LOG_PREFIX + "--format(mo)--Exit");
		return result;

	}

}
