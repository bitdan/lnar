package com.ibm.pip.adapter.sunda.connect;

import java.util.Hashtable;

import com.ibm.pip.framework.connection.client.BaseClientConPool;
import com.ibm.pip.framework.connection.client.ClientConConstants;
import com.ibm.pip.framework.exception.EsbException;
/**

 */
public class SAPConnectionPool extends BaseClientConPool{
	
	/**
	 * 构造函数，调用父类构造函数
	 * @throws EsbException
	 */
	public SAPConnectionPool() throws EsbException {
		super("SAP");
	}
	
	/**
	 * 构造函数，调用父类构造函数
	 * @throws EsbException
	 */
	public SAPConnectionPool(String prefix) throws EsbException {
		super(prefix);
	}
	
	/**
	 * 使配置指向Config文件中的MQConnectionPool.Capability，MQConnection.ClassName节点<br>
	 * @return props
	 */
	protected Hashtable<String, Object> initCfgData(){
		Hashtable<String, Object> props = new Hashtable<String, Object>();
		props.put(ClientConConstants.CLIENTCONPOOLCAPABILITY, prefix+"ConnectionPool.Capability");
		props.put(ClientConConstants.CLIENTCONCLASSNAME, prefix+"Connection.ClassName");
		return props;
	}
		
}//end of class
