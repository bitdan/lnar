package com.ibm.pip.adapter.sunda.timer;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.quartz.JobExecutionContext;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.ibm.pip.adapter.sunda.bean.InterMsgData;
import com.ibm.pip.adapter.sunda.utils.ConstantUtil;
import com.ibm.pip.adapter.sunda.utils.DateUtil;
import com.ibm.pip.adapter.sunda.utils.NetworkUtil;
import com.ibm.pip.adapter.sunda.utils.sap.RfcManager;
import com.ibm.pip.adapter.sunda.utils.sendmq.MQUtil;
import com.ibm.pip.adapter.sunda.utils.sendmq.RfcTableMappingUtil;
import com.ibm.pip.framework.buffer.AbstractMsgReceiveSend;
import com.ibm.pip.framework.buffer.MsgBuffer;
import com.ibm.pip.framework.config.ConfigManager;
import com.ibm.pip.framework.exception.EsbException;

import com.ibm.pip.framework.messageobject.ServiceRequest;

import com.ibm.pip.framework.timer.BaseJob;
import com.ibm.pip.framework.timer.BaseJobParam;
import com.ibm.pip.util.log.Log;
import com.sap.conn.jco.JCoField;
import com.sap.conn.jco.JCoFieldIterator;
import com.sap.conn.jco.JCoFunction;
import com.sap.conn.jco.JCoMetaData;
import com.sap.conn.jco.JCoParameterList;
import com.sap.conn.jco.JCoRecordFieldIterator;
import com.sap.conn.jco.JCoStructure;
import com.sap.conn.jco.JCoTable;

import redis.clients.jedis.Jedis;

public class CommunicationTimer extends BaseJobParam {

	// private String funStrs = "Z_RFC_STORE_01";
	private String LOG_PREFIX = "CommunicationTimer";
	public static String NEW_LINE = System.getProperty("line.separator");
	public static String separator = "$,$";
	private static final Map<String, String> myMap;
	public static ConfigManager cm;
	static {
		myMap = new HashMap<String, String>();
		myMap.put("Z_RFC_STORE_01", "b");
		myMap.put("Z_RFC_STORE_05", "b");
		myMap.put("Z_RFC_STORE_04", "b");
		myMap.put("Z_RFC_STORE_07", "b");
		myMap.put("Z_RFC_STORE_08", "b");
		myMap.put("Z_RFC_STORE_10", "b");
		myMap.put("Z_RFC_STORE_11", "b");
		myMap.put("Z_RFC_STORE_13", "b");
		myMap.put("Z_RFC_STORE_14", "b");
		try {
			cm = ConfigManager.getInstance();
		} catch (EsbException e) {
			Log.getInstance().stdError(e);
		}
	}
	
	private void sendMQInit(String str, String fileName, String tablename, boolean isSend) {
		sendMQInit(str, fileName, tablename, isSend, "");
	}

	/**
	 * 发送MQ消息
	 * @param str
	 * @param fileName
	 * @param tablename
	 * @param isSend
	 * @param runDate
	 */
	private void sendMQInit(String str, String fileName, String tablename, boolean isSend,String runDate) {
		// TODO Auto-generated method stub
		Log.getInstance().stdDebug("send file use internal buffer " + isSend);

		if (isSend) {

			ServiceRequest servReq = new ServiceRequest();

			Map<String, String> tmp = new HashMap<String, String>();
			tmp.put("reqData", str);
			tmp.put("reqFileName", fileName);
			tmp.put("reqTableName", tablename);
			tmp.put("runDate", runDate);

			String json = JSON.toJSONString(tmp, SerializerFeature.WriteMapNullValue);
			Log.getInstance().stdDebug("sendmessage json ====" + json);

			json = json.replaceAll("&", "&amp;");
			json = json.replaceAll("<", "&lt;");
			json = json.replaceAll(">", "&gt;");
			json = json.replaceAll("'", "&apos;");
			json = json.replaceAll("\"", "&quot;");

			String sendmessage = "<DATA>" + json + "</DATA>";


			servReq.setRequest(sendmessage.getBytes());

			try {
				servReq.setTargetCon((String) ConfigManager.getInstance().get("targetcon"));
			} catch (EsbException e) {
				Log.getInstance().stdError("setTargetCon failed",e);
			}
			// messageType=0 stand for standard request process
			servReq.setMessageType(0);
			servReq.setExecId("00010000000002");
			MsgBuffer receiveBuffer;
			try {
				receiveBuffer = AbstractMsgReceiveSend
						.getMsgBufferInstance((String) ConfigManager.getInstance().get("BUFFERNAME"));
				receiveBuffer.put(servReq);
			} catch (EsbException e1) {
				// TODO Auto-generated catch block
				Log.getInstance().stdError("send file use internal buffer error", e1);
			}
			Log.getInstance().stdDebug("send file use internal buffer ing");
		}
		Log.getInstance().stdDebug("send file use internal buffer end ");

	}

	/**
	 * 处理datacache过来的MQ请求,向SAP捞取数据
	 * @param mqRequest
	 */
	public void runMQRequest(String mqRequest) {

		Log.getInstance().stdDebug(LOG_PREFIX + " CommunicationTimer = start. runMQRequest mqRequest="+mqRequest);
		
		String funStrs = mqRequest;
		InterMsgData interMsgData=new InterMsgData();
		if (mqRequest.startsWith("{")) {
			interMsgData=JSON.parseObject(funStrs, InterMsgData.class);
			interMsgData.setClassName("CommunicationTimer");
			funStrs = interMsgData.getFunStrs();
		}else{
			Log.getInstance().stdDebug(LOG_PREFIX + "Warning: msg is not InterMsgData Object.mqRequest=" + mqRequest);
		}
		String transactionID = interMsgData.getTransactionID();

		String tmp[] = funStrs.split(":");
		funStrs = tmp[0];
		boolean isAll = tmp.length > 1 && tmp[1].equals("all");
//		boolean isCurrent = tmp.length > 1 && tmp[1].equals("current");

		for (String funStr : funStrs.split(";")) {
			try {

				Log.getInstance().stdDebug(LOG_PREFIX + " funStr = ======== " + funStr);
				JCoFunction function = null;
				boolean isSend = false;
				try {
					if (funStr.contains("Z_ZXD_VENDOR_GET_INVOICE")) {
						String params[] = funStr.split("[$]");
						function = RfcManager.getInstance().getFunction(params[0]);
						function.getImportParameterList().setValue("IM_ERDAT_FROM", params[1].trim());
						function.getImportParameterList().setValue("IM_ERDAT_TO", params[2].trim());
						function.getImportParameterList().setValue("IM_XDNUM", params[3].trim());
					}else if (funStr.contains("Z_SRM_PP_PO_PLAN_REQ")) {
						String params[] = funStr.split("[$]");
						function = RfcManager.getInstance().getFunction(params[0]);
						JCoStructure st = (JCoStructure) function.getImportParameterList().getValue("I_ZSPP0001");
						st.setValue("WERKS", params[1].trim());
						st.setValue("LFDAT_B", params[2].trim());
						st.setValue("LFDAT_E", params[3].trim());
					} else if (funStr.contains("Z_SPMS_GETDN_MQ")) {
						String params[] = funStr.split("[$]");
						// 针对此接口，SPMS端分REST MQ两种调用形式，SAP端不分，函数名为Z_SPMS_GETDN
						function = RfcManager.getInstance().getFunction("Z_SPMS_GETDN");
						function.getImportParameterList().setValue("I_SDATE", params[1].trim());
						function.getImportParameterList().setValue("I_EDATE", params[2].trim());
					} else if (funStr.contains("Z_SPMS_READ_STOCK")) {
						String params[] = funStr.split("[$]");
						function = RfcManager.getInstance().getFunction(params[0]);
						function.getImportParameterList().setValue("I_WERKS", params[1].trim());
					} else if (funStr.contains("Z_SPMS_GETBOXCODE")) {
						String params[] = funStr.split("[$]");
						function = RfcManager.getInstance().getFunction(params[0]);
						function.getImportParameterList().setValue("I_PACKAGE_NO", params[1].trim());
					} else if (funStr.contains("Z_TMS_TEST_MQ")) {
						interMsgData.setMsgContent("MQ-OK");
						interMsgData.setMsgOrder(interMsgData.getMsgOrder() + 1);
						sendMQInit(JSON.toJSONString(interMsgData, SerializerFeature.WriteMapNullValue), "Z_TMS_TEST_MQ", "Z_TMS_TEST_MQ", true);
						return;
					} else if (funStr.contains("Z_TMS_READ_SKU_HSCODE")) {
						String params[] = funStr.split("[$]");
						function = RfcManager.getInstance().getFunction(params[0]);
						function.getImportParameterList().setValue("I_LAEDA", (params.length > 1) ? params[1].trim() : "");
					} else if (funStr.contains("Z_SPMS_HWCSH")) {
						String params[] = funStr.split("[$]");
						function = RfcManager.getInstance().getFunction(params[0]);
						
						function.getImportParameterList().setValue("I_CURRDATE", (params.length > 1) ? params[1].trim() : "");
						function.getImportParameterList().setValue("I_VBELN", (params.length > 2) ? params[2].trim() : "");
						function.getImportParameterList().setValue("I_XDNUM", (params.length > 3) ? params[3].trim() : "");
						function.getImportParameterList().setValue("I_TRAID", (params.length > 4) ? params[4].trim() : "");
						function.getImportParameterList().setValue("I_SYNC_DATE", (params.length > 5) ? params[5].trim() : "");
					}  else{
						function = RfcManager.getInstance().getFunction(funStr);
					}

					if (funStr.equals("Z_POS_GET_ORDER")) {
						function.getImportParameterList().setValue("IM_LAND", "GH");
					}

					if (isAll && function.getImportParameterList() != null && this.myMap.get(funStr) != null)
						function.getImportParameterList().setValue("I_CURRDATE", "20130101");
//					if (isCurrent && function.getImportParameterList() != null && this.myMap.get(funStr) != null) {
//						function.getImportParameterList().setValue("I_CURRDATE", DateUtil.getCurrDate("yyyyMMdd"));
//					}
					
					if (mqRequest.startsWith("{")) {
						String reqParameter = RfcManager.getInstance().getReqParameter(function);
						interMsgData.setMsgFrom("ESB");
						interMsgData.setMsgTo("SAP");
						interMsgData.setIp(NetworkUtil.getIP());
						interMsgData.setOperation(URLEncoder.encode("向SAP发送函数调用请求", "utf-8"));
						interMsgData.setMsgContent(reqParameter);
						interMsgData.setCreateTime(new Date());
						interMsgData.setMsgOrder(interMsgData.getMsgOrder()+1);
						sendMQInit(JSON.toJSONString(interMsgData, SerializerFeature.WriteMapNullValue), "ReqLog", "ReqLog", true);
					}
					long startTime = System.currentTimeMillis();

					RfcManager.getInstance().execute(function);

					long endTime = System.currentTimeMillis(); //获取结束时间
					int time = (int)(endTime - startTime);
					if (mqRequest.startsWith("{")) {
						interMsgData.setMsgFrom("SAP");
						interMsgData.setMsgTo("ESB");
						interMsgData.setTime(time);
						String resultMsg = RfcManager.getInstance().getResultMsg(function);
						if (resultMsg.length() > ConstantUtil.MSG_MAX_LENGTH) {
							Log.getInstance().stdDebug("消息内容大于指定长度" + ConstantUtil.MSG_MAX_LENGTH +",TransactionID="+interMsgData.getTransactionID()+ "，resultMsg=" + resultMsg);
							resultMsg = resultMsg.substring(0, ConstantUtil.MSG_MAX_LENGTH) + "...";
						}
						interMsgData.setOperation("收到SAP返回结果");
						interMsgData.setMsgContent(resultMsg);
						interMsgData.setCreateTime(new Date());
						interMsgData.setMsgOrder(interMsgData.getMsgOrder()+1);
						sendMQInit(JSON.toJSONString(interMsgData, SerializerFeature.WriteMapNullValue), "ResLog", "ResLog", true);
					}
				} catch (EsbException e) {

					Log.getInstance().stdError(LOG_PREFIX + " EsbException error", e);
					interMsgData.setMsgContent(LOG_PREFIX + "EsbException." + e.toString());
					sendMQInit(JSON.toJSONString(interMsgData, SerializerFeature.WriteMapNullValue), "SAPResLog", "SAPResLog", true);
				}

				JCoParameterList outputParam = function.getTableParameterList();
				JCoMetaData md = outputParam.getMetaData();

				SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd");
				String date = df.format(new Date());
				Log.getInstance().stdDebug(LOG_PREFIX + " md.getFieldCount() = ======== " + md.getFieldCount());

				if (funStr.contains("Z_SPMS_GETDN_MQ") || funStr.contains("Z_SPMS_GETBOXCODE")) {
					String params[] = funStr.split("[$]");
					Map<String, Object> result = new HashMap<String, Object>();
					for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
						result = traversalTable(result, iterator, params[0]);
					}

					for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
						result = traversalTable(result, iterator, params[0]);
					}
					MsgBuffer receiveBuffer = AbstractMsgReceiveSend.getMsgBufferInstance("ReceiveDataBuffer.Instance");
					String temres=JSON.toJSONString(result, SerializerFeature.WriteMapNullValue);
					Log.getInstance().stdDebug("transactionID=" + transactionID + ",functionName=" + params[0] + "result=" + temres);

//					interMsgData.setMsgContent(temres);
					interMsgData.setMsgContent(URLEncoder.encode(temres.replaceAll("%",ConstantUtil.PERCENT_SEP), "utf-8"));
					interMsgData.setMsgOrder(interMsgData.getMsgOrder()+1);
					sendMQInit(JSON.toJSONString(interMsgData, SerializerFeature.WriteMapNullValue), params[0], params[0], true);
					return;
				}
				
				for (int i = 0; i < md.getFieldCount(); i++) {
					String table = md.getName(i);
					Log.getInstance().stdDebug(LOG_PREFIX + " table = ======== " + table);

					JCoTable bt = outputParam.getTable(table);

					int fileCount = 0;
					String tablename = (funStr.contains("Z_SPMS_GETDN_MQ")?funStr.replaceAll("Z_SPMS_GETDN_MQ", "Z_SPMS_GETDN"):funStr) + "$" + table;
					String fileName = table + "_" + date + "_" + fileCount + ".csv";
					StringBuffer sb = new StringBuffer();

					int fieldCount = bt.getMetaData().getFieldCount();

					for (int j = 0; j < fieldCount; j++) {
						sb.append(bt.getMetaData().getName(j));

						if (j == fieldCount - 1) {
							continue;
						} else {
							sb.append(separator);
						}
					}

					sb.append(NEW_LINE);

					for (int j = 0; j < bt.getNumRows(); j++) {
						bt.setRow(j);

						if (0 != j && 0 == j % 1000) {
							// send mq
							Log.getInstance().stdDebug("transactionID=" + transactionID + ",tablename=" + tablename + ",sb1=" + sb.toString().replaceAll(NEW_LINE, ConstantUtil.SEPARATOR));

							interMsgData.setMsgContent(URLEncoder.encode(sb.toString().replaceAll("%",ConstantUtil.PERCENT_SEP), "utf-8"));
							interMsgData.setMsgOrder(interMsgData.getMsgOrder()+1);
							sendMQInit(JSON.toJSONString(interMsgData, SerializerFeature.WriteMapNullValue), fileName, tablename, isSend);
							isSend = false;
							sb.delete(0, sb.length());
							fileCount++;
							fileName = table + "_" + date + "_" + fileCount + ".csv";

							for (int k = 0; k < fieldCount; k++) {
								sb.append(bt.getMetaData().getName(k));

								if (k == fieldCount - 1) {
									isSend = true;
									continue;
								} else {
									sb.append(separator);
								}
							}

							sb.append(NEW_LINE);
						}

						for (int k = 0; k < fieldCount; k++) {
							sb.append(bt.getString(k).replaceAll("\n", ""));

							if (k == fieldCount - 1) {
								isSend = true;
								continue;
							} else {
								sb.append(separator);
							}
						}

						sb.append(NEW_LINE);
					}

					// send mq
					Log.getInstance().stdDebug("transactionID=" + transactionID + ",tablename=" + tablename + ",sb1=" + sb.toString().replaceAll(NEW_LINE, ConstantUtil.SEPARATOR));

					interMsgData.setMsgContent(URLEncoder.encode(sb.toString().replaceAll("%",ConstantUtil.PERCENT_SEP), "utf-8"));
					interMsgData.setMsgOrder(interMsgData.getMsgOrder()+1);
					sendMQInit(JSON.toJSONString(interMsgData, SerializerFeature.WriteMapNullValue), fileName, tablename, isSend);
					isSend = false;
				}
			} catch (Exception e) {

				Log.getInstance().stdError(LOG_PREFIX + " Exception error  funStr ==============" + funStr,e);
				Log.getInstance().stdError(e);
			}
		}

		Log.getInstance().stdDebug(LOG_PREFIX + " CommunicationTimer = end");

	}

	/**
	 * 处理配置的定时任务,向SAP捞取数据
	 */
	@Override
	public void run(JobExecutionContext context) {
		Log.getInstance().stdDebug(LOG_PREFIX + " CommunicationTimer = start. run()");

		String funStrs = context.getJobDetail().getJobDataMap().getString("param");
		Log.getInstance().stdDebug(LOG_PREFIX + " funStrs="+funStrs);
		String tmp[] = funStrs.split(":");
		funStrs = tmp[0];
		boolean isAll = tmp.length > 1 && tmp[1].equals("all");
//		boolean isCurrent = tmp.length > 1 && tmp[1].equals("current");
		
		String runDate = DateUtil.getCurrDate(-10);

		for (String funStr : funStrs.split(";")) {
			try {
				if (ConstantUtil.Z_ESB_UPDATE_CONFIG.equals(funStr)) {
					updateConfig();
					continue;
				}
				InterMsgData interMsgData=new InterMsgData();

				Log.getInstance().stdDebug(LOG_PREFIX + " funStr = ======== " + funStr);
				JCoFunction function = null;
				boolean isSend = false;
				try {
					function = RfcManager.getInstance().getFunction(funStr);

					if (funStr.equals("Z_POS_GET_ORDER")) {
						function.getImportParameterList().setValue("IM_LAND", "GH");
					}
//					if (funStr.equals("Z_RFC_STORE_10")) {
//						function.getImportParameterList().setValue("I_SYNC_DATE", "7");
//					}

					if (isAll && function.getImportParameterList() != null && this.myMap.get(funStr) != null)
						function.getImportParameterList().setValue("I_CURRDATE", "20130101");
//					if (isCurrent && function.getImportParameterList() != null && this.myMap.get(funStr) != null) {
//						function.getImportParameterList().setValue("I_CURRDATE", DateUtil.getCurrDate("yyyyMMdd"));
//					}

					//LOG
					String reqParameter = RfcManager.getInstance().getReqParameter(function);
					setReqMsgData(interMsgData, funStr, reqParameter);

					sendMQInit(JSON.toJSONString(interMsgData, SerializerFeature.WriteMapNullValue), "ReqLog", "ReqLog", true);
					long startTime = System.currentTimeMillis();
					
					RfcManager.getInstance().execute(function);
					
					long endTime = System.currentTimeMillis(); //获取结束时间
					int time = (int)(endTime - startTime);
					String result = RfcManager.getInstance().getResultMsg(function, false);
					if (result.length() > ConstantUtil.MSG_MAX_LENGTH) {
						Log.getInstance().stdDebug("消息内容大于指定长度" + ConstantUtil.MSG_MAX_LENGTH +",TransactionID="+interMsgData.getTransactionID()+ "，result=" + result);
						result = result.substring(0, ConstantUtil.MSG_MAX_LENGTH) + "...";
					}
					setResMsgData(interMsgData, time, result);
					sendMQInit(JSON.toJSONString(interMsgData, SerializerFeature.WriteMapNullValue), "ResLog", "ResLog", true);
				} catch (EsbException e) {

					Log.getInstance().stdError(LOG_PREFIX + " error", e);
				}

				JCoParameterList outputParam = function.getTableParameterList();
				JCoMetaData md = outputParam.getMetaData();

				SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd");
				String date = df.format(new Date());
				Log.getInstance().stdDebug(LOG_PREFIX + " md.getFieldCount() = ======== " + md.getFieldCount());

				for (int i = 0; i < md.getFieldCount(); i++) {
					String table = md.getName(i);
					Log.getInstance().stdDebug(LOG_PREFIX + " table = ======== " + table);

					JCoTable bt = outputParam.getTable(table);

					int fileCount = 0;
					String tablename = funStr + "_" + table;
					String fileName = table + "_" + date + "_" + fileCount + ".csv";
					StringBuffer sb = new StringBuffer();

					int fieldCount = bt.getMetaData().getFieldCount();

					for (int j = 0; j < fieldCount; j++) {
						sb.append(bt.getMetaData().getName(j));

						if (j == fieldCount - 1) {
							continue;
						} else {
							sb.append(separator);
						}
					}

					sb.append(NEW_LINE);

					for (int j = 0; j < bt.getNumRows(); j++) {
						bt.setRow(j);

						if (0 != j && 0 == j % 1000) {
							
							// send mq
//							Log.getInstance().stdDebug(LOG_PREFIX + "sb1=" + sb.toString());
							interMsgData.setMsgContent(URLEncoder.encode(sb.toString().replaceAll("%",ConstantUtil.PERCENT_SEP), "utf-8"));
							interMsgData.setMsgOrder(interMsgData.getMsgOrder()+1);
							sendMQInit(JSON.toJSONString(interMsgData, SerializerFeature.WriteMapNullValue), fileName, tablename, isSend, runDate);

							isSend = false;
							sb.delete(0, sb.length());
							fileCount++;
							fileName = table + "_" + date + "_" + fileCount + ".csv";

							for (int k = 0; k < fieldCount; k++) {
								sb.append(bt.getMetaData().getName(k));

								if (k == fieldCount - 1) {
									isSend = true;
									continue;
								} else {
									sb.append(separator);
								}
							}

							sb.append(NEW_LINE);
						}

						for (int k = 0; k < fieldCount; k++) {
							sb.append(bt.getString(k).replaceAll("\n", ""));

							if (k == fieldCount - 1) {
								isSend = true;
								continue;
							} else {
								sb.append(separator);
							}
						}

						sb.append(NEW_LINE);
					}
					
					// send mq
//					Log.getInstance().stdDebug(LOG_PREFIX + "sb2=" + sb.toString());
					interMsgData.setMsgContent(URLEncoder.encode(sb.toString().replaceAll("%",ConstantUtil.PERCENT_SEP), "utf-8"));
					interMsgData.setMsgOrder(interMsgData.getMsgOrder()+1);
					sendMQInit(JSON.toJSONString(interMsgData, SerializerFeature.WriteMapNullValue), fileName, tablename, isSend, runDate);
					
					isSend = false;
				}
			} catch (Exception e) {

				Log.getInstance().stdError(LOG_PREFIX + " error  funStr ==============" + funStr,e);
				Log.getInstance().stdError(e);
			}
		}

		Log.getInstance().stdDebug(LOG_PREFIX + " CommunicationTimer = end");

	}
	
	private InterMsgData setReqMsgData(InterMsgData interMsgData,String methodName, String msgContent) throws UnsupportedEncodingException{
		interMsgData.setIp(NetworkUtil.getIP());
		interMsgData.setClassName("CommunicationTimer");
		interMsgData.setMethodName(methodName);
		interMsgData.setOperation(URLEncoder.encode("ESB定时任务，向SAP发起请求获取数据。", "utf-8"));
		interMsgData.setTime(0);
		interMsgData.setReqAppId("ESB");
		interMsgData.setTransactionID(UUID.randomUUID().toString().replaceAll("-", ""));
		interMsgData.setMsgFrom("ESB");
		interMsgData.setMsgTo("SAP");
		interMsgData.setMsgContent(msgContent);
		interMsgData.setMethodNameEn(methodName);
		interMsgData.setCreateTime(new Date());
		interMsgData.setMsgOrder(0);
		return interMsgData;
	}

	private InterMsgData setResMsgData(InterMsgData interMsgData, int time, String msgContent) throws UnsupportedEncodingException {
		interMsgData.setMsgFrom("SAP");
		interMsgData.setMsgTo("ESB");
		interMsgData.setTime(time);
		interMsgData.setMsgContent(msgContent);
		interMsgData.setOperation("收到SAP返回结果。");
		interMsgData.setCreateTime(new Date());
		interMsgData.setMsgOrder(interMsgData.getMsgOrder() + 1);
		return interMsgData;
	}

//	public void runReq(String funStrs) {
//
//		Log.getInstance().stdDebug(LOG_PREFIX + "Warning: CommunicationTimer runReq() start. funStrs=" + funStrs);
//		if (!funStrs.startsWith("{")) {
//			Log.getInstance().stdDebug("CommunicationTimer runReq() Warning-Warning: funStrs is not json object,return. funStrs=" + funStrs);
//			return;
//		}
//		for (String funStr : funStrs.split(";")) {
//
//			JCoFunction function = null;
//			boolean isSend = false;
//			try {
//				function = RfcManager.getInstance().getFunction(funStr);
//				RfcManager.getInstance().execute(function);
//			} catch (EsbException e) {
//
//				Log.getInstance().stdDebug(LOG_PREFIX + " error", e);
//			}
//
//			JCoParameterList outputParam = function.getTableParameterList();
//			JCoMetaData md = outputParam.getMetaData();
//
//			SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd");
//			String date = df.format(new Date());
//
//			for (int i = 0; i < md.getFieldCount(); i++) {
//				String table = md.getName(i);
//				JCoTable bt = outputParam.getTable(table);
//
//				int fileCount = 0;
//				String tablename = funStr + "_" + table;
//				String fileName = table + "_" + date + "_" + fileCount + ".csv";
//				StringBuffer sb = new StringBuffer();
//
//				int fieldCount = bt.getMetaData().getFieldCount();
//
//				for (int j = 0; j < fieldCount; j++) {
//					sb.append(bt.getMetaData().getName(j));
//
//					if (j == fieldCount - 1) {
//						continue;
//					} else {
//						sb.append(",");
//					}
//				}
//
//				sb.append(NEW_LINE);
//
//				for (int j = 0; j < bt.getNumRows(); j++) {
//					bt.setRow(j);
//
//					if (0 != j && 0 == j % 1000) {
//						// send mq
//						sendMQInit(sb.toString(), fileName, tablename, isSend);
//						isSend = false;
//						sb.delete(0, sb.length());
//						fileCount++;
//						fileName = table + "_" + date + "_" + fileCount + ".csv";
//
//						for (int k = 0; k < fieldCount; k++) {
//							sb.append(bt.getMetaData().getName(k));
//
//							if (k == fieldCount - 1) {
//								isSend = true;
//								continue;
//							} else {
//								sb.append(",");
//							}
//						}
//
//						sb.append(NEW_LINE);
//					}
//
//					for (int k = 0; k < fieldCount; k++) {
//						sb.append(bt.getString(k));
//
//						if (k == fieldCount - 1) {
//							isSend = true;
//							continue;
//						} else {
//							sb.append(",");
//						}
//					}
//
//					sb.append(NEW_LINE);
//				}
//
//				// send mq
//				sendMQInit(sb.toString(), fileName, tablename, isSend);
//				isSend = false;
//			}
//		}
//
//	}
	
	private Map traversalTable(Map resultMap, Iterator<JCoField> iterator, String function) {
        JCoField jCoField = iterator.next();
        if (jCoField.isTable()) {
            JCoTable table = jCoField.getTable();
            List resultList = new ArrayList();
            for (int i = 0, len = table.getNumRows(); i < len; i++) {
                Map retMap = new HashMap();
                table.setRow(i);
                for (JCoRecordFieldIterator jCoRecordFieldIterator = table.getRecordFieldIterator(); jCoRecordFieldIterator.hasNextField(); ) {
                    JCoField field = jCoRecordFieldIterator.nextRecordField();
					if ("Z_SPMS_GETDN_MQ".equals(function)) {
//						if (Arrays.asList(RfcTableMappingUtil.rfcGetDnTableMapping.get(jCoField.getName())).contains(field.getName().trim())) {
//						}
						if ("LFDAT".equals(field.getName())) {
							String date = field.getValue() != null ? convertDate(field.getValue().toString()) : "";
							retMap.put(field.getName(), date);
						} else if ("ERDAT".equals(field.getName())) {
							String date = field.getValue() != null ? convertDate(field.getValue().toString()) : "";
							retMap.put(field.getName(), date);
						} else {
							retMap.put(field.getName(), field.getValue());
						}
					}
					if ("Z_SPMS_GETBOXCODE".equals(function)) {
						if (Arrays.asList(RfcTableMappingUtil.rfcGetBoxCodeTableMapping.get(jCoField.getName())).contains(field.getName().trim())) {
							if ("ZXDATE_F".equals(field.getName())) {
								String date = field.getValue() != null ? convertDate(field.getValue().toString()) : "";
								retMap.put(field.getName(), date);
							} else if ("ZXDATE_T".equals(field.getName())) {
								String date = field.getValue() != null ? convertDate(field.getValue().toString()) : "";
								retMap.put(field.getName(), date);
							} else {
								retMap.put(field.getName(), field.getValue());
							}
						}
					}
                    
                }
                resultList.add(retMap);
            }
            resultMap.put(jCoField.getName(), resultList);
        } else if (jCoField.isStructure()) {
//            JCoStructure jCoStructure = (JCoStructure) jCoField;
        	JCoStructure jCoStructure = jCoField.getStructure();
            Map resultStructureMap = new HashMap();
            for (JCoFieldIterator jCoFieldIterator = jCoStructure.getFieldIterator(); jCoFieldIterator.hasNextField(); ) {
                JCoField jcf = jCoFieldIterator.nextField();
                resultStructureMap.put(jcf.getName(), jcf.getValue());
            }
            resultMap.put(jCoField.getName(), resultStructureMap);
        } else {
            resultMap.put(jCoField.getName(), jCoField.getValue());
        }
        return resultMap;
    }
	
	public String convertDate(String str) {
        String format1 = null;
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd");
            Date date = new Date(str);
            format1 = format.format(date);
        } catch (Exception e) {
        	Log.getInstance().stdError(e);
        }
        return format1;
    }

	private void updateConfig() {
		Jedis jedis = new Jedis((String) cm.get("redis.host"));
		jedis.auth((String) cm.get("redis.password"));

		Set set = jedis.keys("*");
		Iterator it = set.iterator();
		String key;
		while (it.hasNext()) {
			key = (String) it.next();
			ConstantUtil.configMap.put(key, jedis.get(key));
		}
		jedis.close();
	}
}
