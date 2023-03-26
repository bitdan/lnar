package com.ibm.pip.adapter.sunda.utils;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.apache.commons.collections.map.HashedMap;

import com.ibm.pip.framework.config.ConfigManager;
import com.ibm.pip.framework.exception.EsbException;
import com.ibm.pip.util.log.Log;

import redis.clients.jedis.Jedis;

/**
 * 常量定义
 * 
 * @author xuhk
 *
 */
public final class ConstantUtil {

	/**
	 * 消息内容最大长度
	 */
	public static int MSG_MAX_LENGTH = 20000;

	/**
	 * 日志里显示记录的条数
	 */
	public static int LOG_MAX_ROWS = 3;

	/**
	 * \n替换符
	 */
	public static String SEPARATOR = "／@&@／";

	/**
	 * %替换符
	 */
	public static String PERCENT_SEP = "／#@#／";

	/**
	 * 需要缓存的REST接口
	 */
	public static String[] CACHE_OPERATION = {};//{"Z_TMS_SAVE_ORDERINFO","Z_TMS_UPDATE_SPINFO","Z_TMS_UPDATE_ORDERINFO","Z_TMS_CANCEL_ORDERINFO"};
	

	/**
	 * Redis前缀
	 */
	public static final String REDIS_PRE_DATA_FLOW = "dataflow";// 数据流向
	public static final String REDIS_PRE_DICT = "dict";// 字典
	public static final String REDIS_PRE_DICT_TYPE_SAP_CON = "SAP_CON";// SAP连接配置
	public static final String REDIS_PRE_DICT_TYPE_URL_MDM = "URL_MDM";// MDM接口配置
	public static final String REDIS_PRE_DICT_TYPE_URL_SRM = "URL_SRM";// SRM接口配置
	public static final String REDIS_PRE_DICT_TYPE_URL_TMS = "URL_TMS";// TMS接口配置
	public static final String REDIS_PRE_DICT_TYPE_URL_SPMS = "URL_SPMS";// SPMS接口配置

	/**
	 * ESB用的函数
	 */
	public static final String Z_ESB_CON_CHECK_REST = "Z_ESB_CON_CHECK_REST";// 系统自身校验
	public static final String Z_ESB_UPDATE_CONFIG = "Z_ESB_UPDATE_CONFIG";// 更新配置文件

	/**
	 * 常用配置
	 */
	public static Map<String, String> configMap = new HashedMap();

	/**
	 * 如果有配置，读取配置值。以下代码要放最后
	 * 
	 */
	static {
		try {
			ConfigManager configManager = ConfigManager.getInstance();

			MSG_MAX_LENGTH = Integer.valueOf((String) configManager.get("MSG_MAX_LENGTH"));
			LOG_MAX_ROWS = Integer.valueOf((String) configManager.get("LOG_MAX_ROWS"));

			Jedis jedis = new Jedis((String) configManager.get("redis.host"));
			jedis.auth((String) configManager.get("redis.password"));

			Set set = jedis.keys("*");
			Iterator it = set.iterator();
			String key;
			while (it.hasNext()) {
				key = (String) it.next();
				configMap.put(key, jedis.get(key));
			}
			jedis.close();
		} catch (EsbException e) {
			Log.getInstance().stdError(e);
		}
	}
}
