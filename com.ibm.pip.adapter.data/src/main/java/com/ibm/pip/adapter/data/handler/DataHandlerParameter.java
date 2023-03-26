/*
 * 创建日期 2008-01-20
 *
 * 更改所生成文件模板为
 * 窗口 > 首选项 > Java > 代码生成 > 代码和注释
 */
package com.ibm.pip.adapter.data.handler;

import com.ibm.pip.framework.config.ConfigManager;
import com.ibm.pip.framework.exception.EsbException;
import com.ibm.pip.framework.exception.FrameworkErr;
import com.ibm.pip.framework.handler.BaseEasHandlerParameter;
import com.ibm.pip.util.log.Log;



public class DataHandlerParameter extends BaseEasHandlerParameter {

	public DataHandlerParameter() throws EsbException {
		super();
	}


	/**
	 * 以下为配置信息的名字的定义，包括类的名字
	 */
	public static final String EVENTAPPSSERV_HANDLER = "DataHandlerPool.HandlerNum";
	
	public static final String STOP_WAIT_TIME = "DataHandlerPool.StopWaitTime";
	
	public static final String Handler_ClassName = "DataHandlerPool.HandlerClassName";

	protected void initCfgData() throws EsbException {
		try {
			this.handlerNumber = Integer.parseInt((String) ConfigManager
					.getInstance().get(EVENTAPPSSERV_HANDLER));
			this.handlerStopWaitTime = Integer.parseInt((String) ConfigManager
					.getInstance().get(STOP_WAIT_TIME));
			this.handlerClassName = (String) ConfigManager
					.getInstance().get(Handler_ClassName);
		} catch (Exception e) {
			Log.getInstance().stdError(e);
			throw new EsbException(
					FrameworkErr.HANDLER_PARAMETER_ERRCODE);
		}
	}
}// end of class
