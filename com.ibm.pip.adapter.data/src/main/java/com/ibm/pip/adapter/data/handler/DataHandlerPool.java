/*
 * 创建日期 2008-01-20
 *
 * 更改所生成文件模板为
 * 窗口 > 首选项 > Java > 代码生成 > 代码和注释
 */
package com.ibm.pip.adapter.data.handler;

import com.ibm.pip.framework.exception.EsbException;
import com.ibm.pip.framework.handler.BaseEasHandlerPool;
import com.ibm.pip.framework.handler.IEasHandlerParameter;

public class DataHandlerPool extends BaseEasHandlerPool {
	
	public DataHandlerPool() throws EsbException {
		super();
		LOG_PREFIX = "DataHandlerPool";
	}
	
	/**
	 * 初始化配置参数
	 */
	protected IEasHandlerParameter initCfgData()throws EsbException{
		IEasHandlerParameter para = new DataHandlerParameter();
		return para;
	}	
	
} // end of class
