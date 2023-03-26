package com.ibm.pip.adapter.sunda.utils.sap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.ibm.pip.adapter.sunda.utils.ConstantUtil;
import com.ibm.pip.framework.config.ConfigManager;
import com.ibm.pip.framework.exception.EsbException;
import com.ibm.pip.util.log.Log;
import com.sap.conn.jco.JCoDestination;
import com.sap.conn.jco.JCoDestinationManager;
import com.sap.conn.jco.JCoException;
import com.sap.conn.jco.JCoField;
import com.sap.conn.jco.JCoFieldIterator;
import com.sap.conn.jco.JCoFunction;
import com.sap.conn.jco.JCoFunctionTemplate;
import com.sap.conn.jco.JCoParameterList;
import com.sap.conn.jco.JCoRecordFieldIterator;
import com.sap.conn.jco.JCoRepository;
import com.sap.conn.jco.JCoStructure;
import com.sap.conn.jco.JCoTable;
import com.sap.conn.jco.ext.Environment;

import redis.clients.jedis.Jedis;

public class RfcManager {
	private  final String ABAP_AS_POOLED = "ABAP_AS_POOL";
    private  JCOProvider provider;
    private  JCoDestination destination;
    
    private  static RfcManager Irfcmanager = null;
    
    private ConfigManager cm = null;
    
    private String LOG_PREFIX = "RfcManager";

	public static RfcManager getInstance() throws EsbException{
		
		if(Irfcmanager == null)
		{
			Irfcmanager = new RfcManager();
		}

		return Irfcmanager;
		
	}
	
	public RfcManager() throws EsbException
	{
		try {
			cm = ConfigManager.getInstance();
			Properties properties = loadProperties(cm);

			Log.getInstance().stdDebug(LOG_PREFIX + " new JCOProvider() start");
			provider = new JCOProvider();
			Log.getInstance().stdDebug(LOG_PREFIX + " new JCOProvider() end");

			Log.getInstance().stdDebug(LOG_PREFIX + " registerDestinationDataProvider start");
			Environment.registerDestinationDataProvider(provider);
			Log.getInstance().stdDebug(LOG_PREFIX + " registerDestinationDataProvider end");

			Log.getInstance().stdDebug(LOG_PREFIX + " changePropertiesForABAP_AS start");
			provider.changePropertiesForABAP_AS(ABAP_AS_POOLED, properties);
			Log.getInstance().stdDebug(LOG_PREFIX + " changePropertiesForABAP_AS end");
		} catch (IllegalStateException e) {
			Log.getInstance().stdDebug(LOG_PREFIX + " IllegalStateException error"+e.toString());
			Log.getInstance().stdError(e);
		} catch (Exception e) {
			Log.getInstance().stdDebug(LOG_PREFIX + " Exception error"+e.toString());
			Log.getInstance().stdError(e);
		}
	}
	

	/**
	 * SAP连接配置
	 * @param cm
	 * @return
	 */
    public  Properties loadProperties(ConfigManager cm) {
        Properties props=new Properties();
        
//        props.setProperty("jco.client.user",(String)cm.get("JCO_USER"));
//        props.setProperty("jco.client.passwd",(String)cm.get("JCO_PASS"));
//        props.setProperty("jco.client.lang", (String)cm.get("JCO_LANG"));
//        props.setProperty("jco.client.client", (String)cm.get("JCO_CLIENT"));
//        props.setProperty("jco.client.sysnr", (String)cm.get("JCO_SYSNR"));
//        props.setProperty("jco.client.ashost", (String)cm.get("JCO_ASHOST"));
//        props.setProperty("jco.destination.peak_limit", (String)cm.get("JCO_PEAK_LIMIT"));
//        props.setProperty("jco.destination.pool_capacity", (String)cm.get("JCO_POOL_CAPACITY"));
//        props.setProperty("jco.client.r3name", (String)cm.get("JCO_R3NAME"));

        String redisPre = ConstantUtil.REDIS_PRE_DICT + ":" + ConstantUtil.REDIS_PRE_DICT_TYPE_SAP_CON + ":";
		if (ConstantUtil.configMap.get(redisPre + "JCO_USER")!=null) {

			props.setProperty("jco.client.user", ConstantUtil.configMap.get(redisPre + "JCO_USER"));
			props.setProperty("jco.client.passwd", ConstantUtil.configMap.get(redisPre + "JCO_PASS"));
			props.setProperty("jco.client.lang", ConstantUtil.configMap.get(redisPre + "JCO_LANG"));
			props.setProperty("jco.client.client", ConstantUtil.configMap.get(redisPre + "JCO_CLIENT"));
			props.setProperty("jco.client.sysnr", ConstantUtil.configMap.get(redisPre + "JCO_SYSNR"));
			props.setProperty("jco.client.ashost", ConstantUtil.configMap.get(redisPre + "JCO_ASHOST"));
			props.setProperty("jco.destination.peak_limit", ConstantUtil.configMap.get(redisPre + "JCO_PEAK_LIMIT"));
			props.setProperty("jco.destination.pool_capacity", ConstantUtil.configMap.get(redisPre + "JCO_POOL_CAPACITY"));
			props.setProperty("jco.client.r3name", ConstantUtil.configMap.get(redisPre + "JCO_R3NAME"));
		} else {
			Jedis jedis = new Jedis((String) cm.get("redis.host"));
			jedis.auth((String) cm.get("redis.password"));
			props.setProperty("jco.client.user", jedis.get(redisPre + "JCO_USER"));
			props.setProperty("jco.client.passwd", jedis.get(redisPre + "JCO_PASS"));
			props.setProperty("jco.client.lang", jedis.get(redisPre + "JCO_LANG"));
			props.setProperty("jco.client.client", jedis.get(redisPre + "JCO_CLIENT"));
			props.setProperty("jco.client.sysnr", jedis.get(redisPre + "JCO_SYSNR"));
			props.setProperty("jco.client.ashost", jedis.get(redisPre + "JCO_ASHOST"));
			props.setProperty("jco.destination.peak_limit", jedis.get(redisPre + "JCO_PEAK_LIMIT"));
			props.setProperty("jco.destination.pool_capacity", jedis.get(redisPre + "JCO_POOL_CAPACITY"));
			props.setProperty("jco.client.r3name", jedis.get(redisPre + "JCO_R3NAME"));
			jedis.close();
		}

		Log.getInstance().stdDebug("SAP Connection Config:" + JSON.toJSONString(props, SerializerFeature.WriteMapNullValue));
        return props;
    }
 
    public  JCoDestination getDestination() throws JCoException {
        if (destination == null) {
        	Log.getInstance().stdDebug(LOG_PREFIX + " getDestination: ================================");
            destination = JCoDestinationManager.getDestination(ABAP_AS_POOLED);
        }
        return destination;
    }
 
    public  void execute(JCoFunction function) {
    	Log.getInstance().stdDebug(LOG_PREFIX + " SAP Function Name : " + function.getName());

        try {
            function.execute(getDestination());
        } catch (JCoException e) {
            e.printStackTrace();
            Log.getInstance().stdDebug("RfcManager execute JCoException."+e.toString());
            Log.getInstance().stdError(e);
        }
        finally
        {
        	Log.getInstance().stdDebug(LOG_PREFIX + " SAP Function Name : " + function.getName() + " end");
        }
        
    }
 
	public  JCoFunction getFunction(String functionName) {
        JCoFunction function = null;
        try {
        	
        	Log.getInstance().stdDebug(LOG_PREFIX + " getDestination start");
        	JCoDestination tmp = getDestination();
        	Log.getInstance().stdDebug(LOG_PREFIX + " getDestination end");
        	
        	Log.getInstance().stdDebug(LOG_PREFIX + " getRepository start");
        	JCoRepository tmpre = tmp.getRepository();
        	Log.getInstance().stdDebug(LOG_PREFIX + " getRepository end");
        	
        	Log.getInstance().stdDebug(LOG_PREFIX + " getFunctionTemplate start");
        	JCoFunctionTemplate tmpfuntemplate = tmpre.getFunctionTemplate(functionName);
        	Log.getInstance().stdDebug(LOG_PREFIX + " getFunctionTemplate end");
        	
        	Log.getInstance().stdDebug(LOG_PREFIX + " getFunction start");
            function = tmpfuntemplate.getFunction();
            Log.getInstance().stdDebug(LOG_PREFIX + " getFunction end");
        } catch (JCoException e) {
        	Log.getInstance().stdDebug(LOG_PREFIX + " JCoException : "+e.toString());
        	Log.getInstance().stdError(e);
        } catch (NullPointerException e) {
        	Log.getInstance().stdDebug(LOG_PREFIX + " NullPointerException : "+e.toString());
        	Log.getInstance().stdError(e);
        }
        return function;
    }

	public String getReqParameter(JCoFunction function) {
		String res = null;
		try {
			Map<String, String> para = new HashMap<>();
			para.put("FUNCTION", function.getName());

			JCoParameterList list = function.getImportParameterList();
			if (list != null) {
				for (JCoField jCoField : list) {
					para.put(jCoField.getName(), jCoField.getValue() + "");
				}
			}

			JCoParameterList tableParameterList = function.getTableParameterList();
			if (tableParameterList != null) {
				Iterator<JCoField> iterator = tableParameterList.iterator();
				if (iterator != null) {
					for (; iterator.hasNext();) {
						para = traversalTable(para, iterator);
					}
				}
			}

			res = JSON.toJSONString(para, SerializerFeature.WriteMapNullValue);
		} catch (Exception e) {
			Log.getInstance().stdError(e);
		}
		return res;
	}
	
	public String getResultMsg(JCoFunction function) {
		String result = getResultMsg(function, true);
		return result;
	}

	public String getResultMsg(JCoFunction function, boolean isAllData) {
		String result = null;
		try {
			Map<String, String> resMap = new HashMap<>();
			resMap.put("FUNCTION", function.getName());

			JCoParameterList exportParameterList = function.getExportParameterList();
			if (exportParameterList != null) {
				Iterator<JCoField> iterator = exportParameterList.iterator();
				if (iterator != null) {
					for (; iterator.hasNext();) {
						resMap = traversalTable(resMap, iterator);
					}
				}
			}

			if (!isAllData) {
				resMap.put("TableData", "略...");
				result = JSON.toJSONString(resMap, SerializerFeature.WriteMapNullValue);
				return result;
			}

			JCoParameterList tableParameterList = function.getTableParameterList();
			if (tableParameterList != null) {
				Iterator<JCoField> iterator = tableParameterList.iterator();
				if (iterator != null) {
					for (; iterator.hasNext();) {
						resMap = traversalTable(resMap, iterator);
					}
				}
			}

			result = JSON.toJSONString(resMap, SerializerFeature.WriteMapNullValue);
		} catch (Exception e) {
			Log.getInstance().stdError(e);
		}

		return result;
	}

	private Map traversalTable(Map resultMap, Iterator<JCoField> iterator) {
		JCoField jCoField = iterator.next();
//		System.out.println("****************LOG_MAX_ROWS="+ConstantUtil.LOG_MAX_ROWS+"*************");
		if (jCoField.isTable()) {
			JCoTable table = jCoField.getTable();
			List resultList = new ArrayList();
			int len = table.getNumRows();
			for (int i = 0; i < len; i++) {
				if (i >= ConstantUtil.LOG_MAX_ROWS) {
					break;
				}

				Map retMap = new HashMap();
				table.setRow(i);
				for (JCoRecordFieldIterator jCoRecordFieldIterator = table.getRecordFieldIterator(); jCoRecordFieldIterator.hasNextField();) {
					JCoField field = jCoRecordFieldIterator.nextRecordField();
					retMap.put(field.getName(), field.getValue());

				}
				resultList.add(retMap);
			}
			resultMap.put(jCoField.getName(), resultList);
			if (len > ConstantUtil.LOG_MAX_ROWS) {
				resultMap.put("表" + jCoField.getName() + "日志说明", "共获得" + len + "条记录,超过" + ConstantUtil.LOG_MAX_ROWS + "的记录略。");
			}
		} else if (jCoField.isStructure()) {
			JCoStructure jCoStructure = jCoField.getStructure();
			Map resultStructureMap = new HashMap();
			for (JCoFieldIterator jCoFieldIterator = jCoStructure.getFieldIterator(); jCoFieldIterator.hasNextField();) {
				JCoField jcf = jCoFieldIterator.nextField();
				resultStructureMap.put(jcf.getName(), jcf.getValue());
			}
			resultMap.put(jCoField.getName(), resultStructureMap);
		} else {
			resultMap.put(jCoField.getName(), jCoField.getValue());
		}
		return resultMap;
	}
}
