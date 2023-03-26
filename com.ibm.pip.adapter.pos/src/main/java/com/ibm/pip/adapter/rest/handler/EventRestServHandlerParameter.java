package com.ibm.pip.adapter.rest.handler;

import com.ibm.pip.framework.config.ConfigManager;
import com.ibm.pip.framework.exception.EsbException;
import com.ibm.pip.framework.exception.FrameworkErr;
import com.ibm.pip.framework.handler.BaseEasHandlerParameter;
import com.ibm.pip.util.log.Log;

/**
 * 
 * com.ibm.pip.ach.handler.EventSepaServHandlerParameter
 * @version 1.0
 * @author linjinnan
 * @WrittenDate:2012-8-7上午09:52:50
 * @ModifiedBy:
 * @ModifiedDate:
 */
public class EventRestServHandlerParameter extends BaseEasHandlerParameter {

	public EventRestServHandlerParameter() throws EsbException {
		super();
	}

	/**
	 * 以下为配置信息的名字的定义，包括类的名字
	 */
	public static final String EVENTAPPSSERV_HANDLER = "EventRestServHandlerPool.HandlerNum";
	
	public static final String STOP_WAIT_TIME = "EventRestServHandlerPool.StopWaitTime";
	
	public static final String Handler_ClassName = "EventRestServHandlerPool.HandlerClassName";

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
			throw new EsbException(FrameworkErr.HANDLER_PARAMETER_ERRCODE);
		}
	}
}
