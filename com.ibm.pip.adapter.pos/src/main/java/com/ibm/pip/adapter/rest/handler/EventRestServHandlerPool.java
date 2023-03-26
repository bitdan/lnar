package com.ibm.pip.adapter.rest.handler;

import com.ibm.pip.framework.exception.EsbException;
import com.ibm.pip.framework.handler.BaseEasHandlerPool;
import com.ibm.pip.framework.handler.IEasHandlerParameter;


/**
 * 
 * com.ibm.pip.ach.in.handler.EventRtgsServHandlerPool
 * @version 1.0
 * @author linjinnan
 * @WrittenDate:2012-8-7
 * @ModifiedBy:
 * @ModifiedDate:
 */
public class EventRestServHandlerPool extends BaseEasHandlerPool {
	
	public EventRestServHandlerPool() throws EsbException {
		super();
		LOG_PREFIX = "EventCipsServHandlerPool";
	}
	
	/**
	 * 初始化配置参�?
	 */
	protected IEasHandlerParameter initCfgData()throws EsbException{
		IEasHandlerParameter para = new EventRestServHandlerParameter();
		return para;
	}	
	
} // end of class
