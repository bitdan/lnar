package com.ibm.pip.adapter.sunda.connect;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.ibm.pip.adapter.sunda.utils.*;
import com.sap.conn.jco.*;
import org.apache.commons.lang.StringUtils;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.google.common.base.Joiner;
import com.ibm.pip.adapter.sunda.bean.InterMsgData;
import com.ibm.pip.adapter.sunda.utils.sap.RfcManager;
import com.ibm.pip.adapter.sunda.utils.sendmq.MQUtil;
import com.ibm.pip.adapter.sunda.utils.sendmq.RfcTableMappingUtil;
import com.ibm.pip.framework.buffer.AbstractMsgReceiveSend;
import com.ibm.pip.framework.buffer.MsgBuffer;
import com.ibm.pip.framework.config.ConfigManager;
import com.ibm.pip.framework.connection.client.DataBean;
import com.ibm.pip.framework.connection.client.IClientCon;
import com.ibm.pip.framework.exception.EsbException;
import com.ibm.pip.framework.messageobject.ServiceRequest;
import com.ibm.pip.framework.messageobject.domparser.MsgObject;
import com.ibm.pip.util.log.Log;
import redis.clients.jedis.Jedis;

public class SAPClientCon implements IClientCon {

	public static final int STATUS_SUCCESS = 0;
	public static final int STATUS_FAIL_SENDMSG = 1;
	public static final int STATUS_FAIL_RECEIVEMSG = 2;
//	public static final int STATUS_TIMEOUT = 3;
	public static final int STATUS_FAIL = -1;
	
	
	public static String NEW_LINE = System.getProperty("line.separator");
	public static String separator = "$,$";
	public static ConfigManager cm;
	private String LOG_PREFIX = "CommunicationTimer";
	private String targetCon = null;
	private MsgBuffer receiveBuffer = null;
	private Object outinfo;
//	private static Jedis jedis = null;
	public static List noDbLogOptList = null;

	static {
		try {
			cm = ConfigManager.getInstance();
		} catch (EsbException e) {
			Log.getInstance().stdError(e);
		}
		try {
			String noDbLogOpt = (String) cm.get("NO_DB_LOG_OPT");
			if (StringUtils.isNotEmpty(noDbLogOpt)) {
				String[] noDbLogOptArr = noDbLogOpt.split(",");
				noDbLogOptList = Arrays.asList(noDbLogOptArr);
			}
			if (noDbLogOptList == null) {
				noDbLogOptList = new ArrayList<>();
			}
		} catch (Exception e) {
			Log.getInstance().stdError(e);
		}
	}

	public SAPClientCon(String prefix) {

		try {
			targetCon = (String) ConfigManager.getInstance().get("targetcon");
			receiveBuffer = AbstractMsgReceiveSend.getMsgBufferInstance("ReceiveDataBuffer.Instance");
		} catch (EsbException e) {
			Log.getInstance().stdError("SAPClientCon error");
			Log.getInstance().stdError(e);
		}

	}

	/**
	 * 处理Rest请求
	 */
	@SuppressWarnings("unchecked")
	@Override
	public void execute(MsgObject mo, DataBean databean) {
		Map<String, Object> result = null;
		int retValue = STATUS_FAIL;
		String message = null;
		String operation = null;
		String condition = null;
		Map messageMap = null;
		List<String> cacheOptList = new ArrayList<>();
		String transactionId = null;
		InterMsgData interMsgData = null;
		
		try {
			message = new String(databean.getSendMsg());
			messageMap = JSON.parseObject(message, Map.class);
			Map request = (Map) messageMap.get("REQUEST");
			if (request == null) {
				throw new Exception("REQUEST节点不存在。");
			}

			Map esbAttrs = (Map) request.get("ESB_ATTRS");
			if (esbAttrs == null) {
				throw new Exception("ESB_ATTRS节点不存在。");
			}

			String appId = (String) esbAttrs.get("App_ID");
			if (StringUtils.isEmpty(appId)) {
				// throw new Exception("App_ID不能为空。");
				Log.getInstance().stdDebug("Warning:App_ID为空。");
			}

			String applicationId = (String) esbAttrs.get("Application_ID");
			transactionId = (String) esbAttrs.get("Transaction_ID");

			Map requestData = (Map) request.get("REQUEST_DATA");
			if (requestData == null) {
				throw new Exception("REQUEST_DATA节点不存在。");
			}

			operation = (String) requestData.get("Operation");
			if (StringUtils.isEmpty(operation)) {
				throw new Exception("Operation不能为空。");
			}

			condition = (String) requestData.get("Type");
			if (condition == null) {
				condition = "default";
			}
//			if (ConstantUtil.Z_ESB_CON_CHECK_REST.equals(operation)) {
//				System.out.println("debug.");
//			}

			if (!ConstantUtil.Z_ESB_CON_CHECK_REST.equals(operation)) {

				try {
					interMsgData = getReqInfo(operation, appId, applicationId, transactionId, message, condition);
					String op = "收到" + appId + "的REST请求。";
					sendReqLog(interMsgData, interMsgData.getMsgFrom(), interMsgData.getMsgTo(), message, op);

					String available = getRedisValue(ConstantUtil.REDIS_PRE_DATA_FLOW + ":" + operation);
					Log.getInstance().stdDebug("校验接口是否有效.operation=" + operation + ",available=" + available);
					if ("1".equals(available)) {
						Map resMap = new HashMap<String, Object>();
						resMap.put("MESSAGE", "该接口尚未启用。");
						resMap.put("TYPE", "TIP");
						String resStr = JSON.toJSONString(resMap, SerializerFeature.WriteMapNullValue);
						sendResLog(interMsgData, "ESB", interMsgData.getReqAppId(), 0, resStr, "ESB接口校验");
						// 发送返回结果
						Log.getInstance().stdDebug("operation=" + operation + ",transactionId=" + transactionId + ",res=" + resStr);
						databean.setReceiveMsgInfo(retValue, resStr.getBytes());
						return;
					}
				} catch (Exception e) {
					Log.getInstance().stdError("Warning:校验接口是否有效Exception ", e);
				}
			}

			cacheOptList = Arrays.asList(ConstantUtil.CACHE_OPERATION);
	
			if (targetCon == null || receiveBuffer == null) {
				Log.getInstance().stdWarn("Warning: targetCon = " + this.targetCon + " || " + "receiveBuffer = " + this.receiveBuffer);
				Log.getInstance().stdWarn("Warning: request message = " + message);
			} else {
				if (StringUtils.isNotEmpty(operation) && (operation.startsWith("Z_POS_") || cacheOptList.contains(operation))) {
					MQUtil.sendMQInit(message, "SAPReq", "SAPReq", this.targetCon, this.receiveBuffer);
				}
			}
	
			Map mapType = (Map) requestData.get("Head");
			Map xHead = (Map) requestData.get("XHead");
	
			List items = (List) requestData.get("Items");
	
			List listXHead = (List) requestData.get("ListXHead");
			List lisMapType = (List) requestData.get("ListHead");
	
			Map mapMapType = (Map) requestData.get("MapHead");
	
			Map listItems = (Map) requestData.get("MapItems");

			Log.getInstance().stdDebug("operation=" + operation + ",condition=" + condition + ".Start...");

			switch (operation) {
			case "Z_ESB_CON_CHECK_REST":
				result = Z_ESB_CON_CHECK_REST();
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SPMS_SKU_CHECK":
				result = Z_SPMS_SKU_CHECK(mapType, items, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SPMS_READ_DATA":
				result = Z_SPMS_READ_DATA(mapType, items, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SPMS_GETHWDN":
				result = Z_SPMS_GETHWDN(mapType, items, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SPMS_GETDN_REST":
				result = Z_SPMS_GETDN_REST(mapType, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SPMS_GETSKUDATA":
				result = Z_SPMS_GETSKUDATA(mapType, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SPMS_CANCEL":
				result = Z_SPMS_CANCEL(mapType, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SPMS_SET_ZXD_1120":
				result = Z_SPMS_SET_ZXD_1120(mapType, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SPMS_SETBOXCODE":
				result = Z_SPMS_SETBOXCODE(mapType, listItems, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SPMS_ANOMALY_POST":
				result = Z_SPMS_ANOMALY_POST(mapType, items, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SPMS_MIGO":
				result = Z_SPMS_MIGO(mapType, items, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SPMS_1150":
				result = Z_SPMS_1150(mapType, items, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SPMS_CHECK_PL":
				result = Z_SPMS_CHECK_PL(mapType, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_ZXD_CONFIRM_CANCEL":
				result = Z_ZXD_CONFIRM_CANCEL(mapType, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_ZXD_DEL":
				result = Z_ZXD_DEL(mapType, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SPMS_CREATE_STO":
				result = Z_SPMS_CREATE_STO(mapType, listItems, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SPMS_CHANGE_STO":
				result = Z_SPMS_CHANGE_STO(mapType, listItems, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SPMS_DN_POST":
				result = Z_SPMS_DN_POST(mapType, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SPMS_HWCSH":
				result = Z_SPMS_HWCSH(mapType, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SPMS_SAVE_XHEAD":
				result = Z_SPMS_SAVE_XHEAD(items, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SRM_GET_FBL1N":
				result = Z_SRM_GET_FBL1N(mapType, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SRM_GET_GOODS_IN":
				result = Z_SRM_GET_GOODS_IN(mapType, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SRM_GET_PRINFO":
				result = Z_SRM_GET_PRINFO(mapType, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SRM_GET_PRINFO_02":
				result = Z_SRM_GET_PRINFO_02(items, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SRM_GET_INVOICELIST":
				result = Z_SRM_GET_INVOICELIST(mapType, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SRM_CREATE_INVOICE":
				result = Z_SRM_CREATE_INVOICE(mapType, items, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SRM_CREATE_EXPENSE_INVOICE":
				result = Z_SRM_CREATE_EXPENSE_INVOICE(mapType, items, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SRM_DELETE_INVOICE":
				result = Z_SRM_DELETE_INVOICE(mapType, items, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SRM_CREATE_CHANGE_DN":
				result = Z_SRM_CREATE_CHANGE_DN(mapType, items, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SRM_PACKAGE_INFORMATION":
				result = Z_SRM_PACKAGE_INFORMATION(mapType, listXHead, items, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SRM_YANSHOUHEGE_INPUT":
				result = Z_SRM_YANSHOUHEGE_INPUT(items, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SRM_FBE1_CREATE":
				result = Z_SRM_FBE1_CREATE(mapType, items, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SRM_FBE6_CANCEL":
				result = Z_SRM_FBE6_CANCEL(mapType, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_ZXD_IMPROT_AND_CONFIRM":
				result = Z_ZXD_IMPROT_AND_CONFIRM(mapType, listItems, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SRM_FW_ML81N":
				result = Z_SRM_FW_ML81N(mapType, items, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SRM_PAYMENT_F_47":
				result = Z_SRM_PAYMENT_F_47(mapType, items, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SRM_PAYMENT_F_47_RESVE":
				result = Z_SRM_PAYMENT_F_47_RESVE(mapType, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SRM_CREATE_PO":
				result = Z_SRM_CREATE_PO(mapType, listItems, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SRM_MODIFY_PO":
				result = Z_SRM_MODIFY_PO(mapType, listItems, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SRM_PRSTATUS_UPDATE":
				result = Z_SRM_PRSTATUS_UPDATE(lisMapType, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SRM_MODIFY_PO_BSTAE":
				result = Z_SRM_MODIFY_PO_BSTAE(lisMapType, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SRM_VMISTORE_INPUT":
				result = Z_SRM_VMISTORE_INPUT(lisMapType, items, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SRM_VENDER_MAINTAIN_CREATE":
				result = Z_SRM_VENDER_MAINTAIN_CREATE_SWITCH(appId, operation, mapType, listItems, message, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SRM_VENDER_MAINTAIN_CHANGE":
				result = Z_SRM_VENDER_MAINTAIN_CHANGE_SWITCH(appId, operation, mapType, listItems, message, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SRM_CLOSE_DEL_PO":
				result = Z_SRM_CLOSE_DEL_PO(items, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SRM_GET_PO_ZZ":
				result = Z_SRM_GET_PO_ZZ(mapType, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SRM_UPDATE_ZDCHTH_ZZ":
				result = Z_SRM_UPDATE_ZDCHTH_ZZ(items, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SRM_SRMRPSTATUS_UPDATE":
				result = Z_SRM_SRMRPSTATUS_UPDATE(items, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SRM_PAYMENT_QSRQ":
				result = Z_SRM_PAYMENT_QSRQ(mapType, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SRM_RETURN_PAYMENY":
				result = Z_SRM_RETURN_PAYMENY(mapType, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SRM_PAYMENT_STATUS":
				result = Z_SRM_PAYMENT_STATUS(mapType, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SRM_PROJECT_OANO":
				result = Z_SRM_PROJECT_OANO(mapType, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SRM_SOA_SYNC":
				result = Z_SRM_SOA_SYNC(mapType, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SRM_SOA_RETURN":
				result = Z_SRM_SOA_RETURN(mapType,interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SRM_GET_MAIN_INFO":
				result = Z_SRM_GET_MAIN_INFO(mapType,interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SRM_MATERIAL_PRICE":
				result = Z_SRM_MATERIAL_PRICE(mapType,items,interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_SRM_LABOR_SECTION":
				result = Z_SRM_LABOR_SECTION(mapType,interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_POS_DO_CREATE2":

				switch (condition) {
				case "FP":
					result = Z_POS_DO_CREATE2_FP(mapType, items, interMsgData);
					break;
				case "ZCK":
					result = Z_POS_DO_CREATE2_ZCK(mapType, items, interMsgData);
					break;
				default:
					result = Z_POS_DO_CREATE2(mapType, items, interMsgData);
				}
				retValue = STATUS_SUCCESS;
				break;

			case "Z_POS_DO_POST_CREATE2":

				switch (condition) {
				case "GZ":
					result = Z_POS_DO_POST_CREATE2_GZ(mapType, items, interMsgData);
					break;
				default:
					result = Z_POS_DO_POST_CREATE2(mapType, items, interMsgData);
				}
				retValue = STATUS_SUCCESS;
				break;

			case "Z_POS_MB1A_CREATE2":

				switch (condition) {
				case "DB":
					result = Z_POS_MB1A_CREATE2_DB(mapType, items, interMsgData);
					break;
				case "CB":
					result = Z_POS_MB1A_CREATE2_CB(mapType, items, interMsgData);
					break;
				case "CH":
					result = Z_POS_MB1A_CREATE2_CH(mapType, items, interMsgData);
					break;
				case "PD":
					result = Z_POS_MB1A_CREATE2_PD(mapType, items, interMsgData);
					break;
				default:
					result = Z_POS_MB1A_CREATE2(mapType, items, interMsgData);
				}
				retValue = STATUS_SUCCESS;
				break;

			case "Z_POS_MBST_CREATE":

				switch (condition) {
				case "CX":
					result = Z_POS_MBST_CREATE_CX(mapType, items, interMsgData);
					break;
				default:
					result = Z_POS_MBST_CREATE(mapType, items, interMsgData);
				}
				retValue = STATUS_SUCCESS;
				break;

			case "Z_POST_MIGO_CREATE":

				result = Z_POST_MIGO_CREATE(mapType, items, interMsgData);
				retValue = STATUS_SUCCESS;
				break;

			case "Z_POS_PO_CREATE":

				switch (condition) {
				case "ZZD_ITEM_OFF":
					result = Z_POS_PO_CREATE_ZZD_ITEM_OFF(mapType, items, interMsgData);
					break;
				case "ZZD":
					result = Z_POS_PO_CREATE_ZZD(mapType, items, interMsgData);
					break;
				default:
					result = Z_POS_PO_CREATE(mapType, items, interMsgData);
				}
				retValue = STATUS_SUCCESS;
				break;

			case "Z_POS_SO_CREATE":

				result = Z_POS_SO_CREATE(mapType, items, interMsgData);
				retValue = STATUS_SUCCESS;
				break;

			case "Z_POS_ORDER_MAT_CREATE":

				result = Z_POS_ORDER_MAT_CREATE(mapType, items, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_POS_CUSTOMER001":
				
				result = Z_POS_CUSTOMER001(mapType, items, interMsgData);
				retValue = STATUS_SUCCESS;
				break;

			case "spms_srm_purchase_status":

				result = spms_srm_purchase_status(mapType, items, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "spms_srm_purchase_info":

				result = spms_srm_purchase_info(message, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "spms_srm_purchase_request_head_type":

				result = spms_srm_purchase_request_head_type(message, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "spms_srm_material_request_time":
				
				result = spms_srm_material_request_time(message, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_MDM_MATER_PUSH":

				result = Z_MDM_MATER_PUSH(message, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_MDM_CUSTOMER_PUSH":

				result = Z_MDM_CUSTOMER_PUSH(message, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_MDM_SUPPLIER_PUSH":

				result = Z_MDM_SUPPLIER_PUSH(message, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_MDM_CREATE_SKU":

				result = Z_MDM_CREATE_SKU(listItems, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_MDM_CHANGE_SKU":

				result = Z_MDM_CHANGE_SKU(mapType, listItems, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_MDM_DELETE_SKU":

				result = Z_MDM_DELETE_SKU(mapType, listItems, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_MDM_VENDER_MAINTAIN_CREATE":
				result = Z_MDM_VENDER_MAINTAIN_CREATE(mapType, listItems, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_MDM_VENDER_MAINTAIN_CHANGE":
				result = Z_MDM_VENDER_MAINTAIN_CHANGE(mapType, listItems, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_MDM_HRP1001":
				result = Z_MDM_HRP1001(mapType, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_MDM_HRP1002":
				result = Z_MDM_HRP1002(mapType, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_MDM_CUSTOMER_MAINTAIN_CREATE":
				result = Z_MDM_CUSTOMER_MAINTAIN_CREATE(mapType, listItems, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_MDM_CUSTOMER_MAINTAIN_CHANGE":
				result = Z_MDM_CUSTOMER_MAINTAIN_CHANGE(mapType, listItems, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_MDM_READ_TABLE":
				result = Z_MDM_READ_TABLE(mapType, listItems, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_MDM_READ_SKB1":
				result = Z_MDM_READ_SKB1(mapType, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_MDM_GET_MATER_TYPE":
				result = Z_MDM_GET_MATER_TYPE(interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_TMS_READ_TCURR":
				result = Z_TMS_READ_TCURR(mapType, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_TMS_READ_COST_TYPE":
				result = Z_TMS_READ_COST_TYPE(interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_TMS_GET_TOKEN":
				result = Z_TMS_GET_TOKEN(mapType,interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_TMS_SAVE_ORDERINFO":
				result = Z_TMS_SAVE_ORDERINFO(requestData,interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_TMS_UPDATE_ORDERINFO":
				result = Z_TMS_UPDATE_ORDERINFO(requestData,interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_TMS_CANCEL_ORDERINFO":
				result = Z_TMS_CANCEL_ORDERINFO(requestData,interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_TMS_TRD_CUSTOMER_CREATE":
				result = Z_TMS_TRD_CUSTOMER_CREATE(message,interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_TMS_TRD_CUSTOMER_CHANGE":
				result = Z_TMS_TRD_CUSTOMER_CHANGE(message,interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_TMS_UPDATE_SPINFO":
				result = Z_TMS_UPDATE_SPINFO(requestData, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_TMS_SET_ESTIMATE":

				result = Z_TMS_SET_ESTIMATE(mapType, listItems, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_TMS_GET_PO_MIGO_STATUS":
				result = Z_TMS_GET_PO_MIGO_STATUS(mapType, items, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_TMS_INVOICE_CHECK":
				result = Z_TMS_INVOICE_CHECK(mapType, items, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_TMS_INVOICE_HXJDJ":
				result = Z_TMS_INVOICE_HXJDJ(mapType, items, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_TMS_SET_ESTIMATE_STATUS":
				result = Z_TMS_SET_ESTIMATE_STATUS(items, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_TMS_GET_PO_ESTIMATE_STATUS":
				result = Z_TMS_GET_PO_ESTIMATE_STATUS(items, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_MDM_HRP1003":
				// 20220407 xpw 离职人员信息接口
				result = Z_MDM_HRP1003(mapType, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_MDM_FI_READ":
				// 20220407 xpw 财务视图参数接口
				result = Z_MDM_FI_READ(mapType, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_MDM_CHECK_MATNR":
				// 20220510 xpw 查询物料是否允许修改单位转换关系
				result = Z_MDM_CHECK_MATNR(mapType, interMsgData);
				retValue = STATUS_SUCCESS;
				break;
			case "Z_TR_PAYMENT_REQUEST":
			    // 20220518 xpw SAP->TR付款申请单接口
                result = Z_TR_PAYMENT_REQUEST(mapType, interMsgData);
                retValue = STATUS_SUCCESS;
			    break;
            case "Z_TR_PAYMENT_REQUEST_RET":
                // 20220519 xpw TR->SAP付款申请单更新反馈接口
                result = Z_TR_PAYMENT_REQUEST_RET(mapType, items, interMsgData);
                retValue = STATUS_SUCCESS;
                break;
//            case "Z_TR_PAYMENT_RETURN":
//            	// 20220621 xpw SAP->TR付款申请反馈接口
//				result = Z_TR_PAYMENT_RETURN(requestData, interMsgData);
//				retValue = STATUS_SUCCESS;
//				break;
			default:
				JSONObject sapAdapterConfig = XmlUtil.xmlToJson(new File("./config/apiAdapter.xml"), "SAP");
			    Log.getInstance().stdDebug(sapAdapterConfig.toJSONString());
				JSONObject rfcConfig = sapAdapterConfig.getJSONObject(operation);
				// 20220615 xpw 通用RFC请求接口,目标系统SAP
				if (rfcConfig != null){
					result = restToSapCommonUtils(requestData, interMsgData, rfcConfig);
					retValue = STATUS_SUCCESS;
				}else{
					retValue = STATUS_FAIL_RECEIVEMSG;
					throw new EsbException("ReqPostService not found! Operation:" + operation);
				}
                break;
			}
		} catch (Exception e) {
			if (!(e instanceof EsbException)) {
				retValue = STATUS_FAIL_SENDMSG;
			}
			Log.getInstance().stdError("SAPClientCon error ", e);
			Log.getInstance().stdError(e);
			result = new HashMap<String, Object>();
			String exMsg=e.getMessage();//.replaceAll("\"", "").replaceAll(":", "").replaceAll(";", ".").replaceAll("/", "");
			if (exMsg.length() > 100) {
				exMsg = exMsg.substring(0, 100) + "...";
			}
			result.put("MESSAGE", exMsg);
			result.put("TYPE", "TIP");
		} finally {
			Log.getInstance().stdDebug("==========" + operation + " " + condition + "==========  end");
			Log.getInstance().bizInfo("==========" + operation + " " + condition + "========== end id = " +  mo.getMID(), "runStatus");
		}
//		Object res = JSON.toJSON(result);
		((Map) ((Map) messageMap.get("REQUEST")).get("ESB_ATTRS")).put("Type", condition);
		((Map) messageMap.get("REQUEST")).remove("REQUEST_DATA");
		((Map) messageMap.get("REQUEST")).put("RETURN_DATA", result);

		String tmpres = JSON.toJSONString(messageMap, SerializerFeature.WriteMapNullValue).replace("REQUEST", "RESPONSE");

		if (targetCon == null || receiveBuffer == null) {
			Log.getInstance()
					.stdWarn("targetCon = " + this.targetCon + " || " + "receiveBuffer = " + this.receiveBuffer);
			Log.getInstance().stdWarn("request message = " + message);
		} else {
			if(StringUtils.isNotEmpty(operation) && (operation.startsWith("Z_POS_") || cacheOptList.contains(operation))){
				MQUtil.sendMQInit((String) tmpres, "SAPRes", "SAPRes", this.targetCon, this.receiveBuffer);
			}
		}
		
		String resStr=JSON.toJSONString(result, SerializerFeature.WriteMapNullValue);
		// Z_SRM_GET_FBL1N方法返回值result里有reqData，与sendMQInit()里也有reqData，区分下,发送前reqData-->ESB_Log_reqData，接受后ESB_Log_reqData->reqData
		String resLog = resStr;
		if (resLog.contains("reqData")) {
			resLog = resLog.replaceAll("reqData", "ESB_Log_reqData");
		}

		if (!ConstantUtil.Z_ESB_CON_CHECK_REST.equals(operation)) {
			sendResLog(interMsgData, "ESB", interMsgData.getReqAppId(), 0, resLog, "返回REST接口调用结果");
		}

		//发送返回结果
		Log.getInstance().stdDebug("发送返回结果Operation=" + operation + ",transactionId=" + transactionId + ",res=" + resStr);
		databean.setReceiveMsgInfo(retValue, resStr.getBytes());
	}

    /**
     * SAP通用RFC请求
     * @param requestData
     * @param interMsgData
     * @return
     * @throws EsbException
     */
	private Map<String, Object> restToSapCommonUtils (Map requestData, InterMsgData interMsgData,JSONObject rfcConfig) throws EsbException{
        String rfcName = (String) requestData.get("Operation");
        Log.getInstance().stdDebug("==================" + rfcName + "================");
        JCoFunction function = RfcManager.getInstance().getFunction(rfcName);

		JSONObject importParameterConfig = rfcConfig.getJSONObject("ImportParameter");
		JSONObject mappingConfig = null;
		if (null != importParameterConfig){
			mappingConfig = importParameterConfig.getJSONObject("Mapping");
		}
		JSONObject exportParameterConfig = rfcConfig.getJSONObject("ExportParameter");

        // ---- 传参方式1 ---- //
        Integer headType = Integer.valueOf(String.valueOf(requestData.get("HeadType"))); // 输入类型
        String headName = (String) requestData.get("HeadName");
        Map<String, Object> head = (Map<String, Object>) requestData.get("Head");
        if (headType != null){
            // 0:字段Filed 1:结构Structure 2:表Table
            if (headType.equals(0)){
                Iterator it = head.entrySet().iterator();
                while (it.hasNext()){
                    Map.Entry entry = (Map.Entry) it.next();
                    if (null != mappingConfig && StringUtils.isNotEmpty(mappingConfig.getString((String) entry.getKey()))){
						function.getImportParameterList().setValue(mappingConfig.getString((String) entry.getKey()), entry.getValue());
					}else{
						function.getImportParameterList().setValue((String) entry.getKey(), entry.getValue());
					}
                }
            }else if (headType.equals(1)){
                JCoStructure st = (JCoStructure) function.getImportParameterList().getValue(headName);
                Iterator it = head.entrySet().iterator();
                while (it.hasNext()){
                    Map.Entry entry = (Map.Entry) it.next();
					if (null != mappingConfig && StringUtils.isNotEmpty(mappingConfig.getString((String) entry.getKey()))){
						st.setValue(mappingConfig.getString((String) entry.getKey()), entry.getValue());
					}else{
						st.setValue((String) entry.getKey(), entry.getValue());
					}
                }
            }else if (headType.equals(2)){
                JCoTable table = function.getTableParameterList().getTable(headName);
				table.appendRow();
				table.setRow(0);
                Iterator it = head.entrySet().iterator();
                while (it.hasNext()){
                    Map.Entry entry = (Map.Entry) it.next();
					if (null != mappingConfig && StringUtils.isNotEmpty(mappingConfig.getString((String) entry.getKey()))){
						table.setValue(mappingConfig.getString((String) entry.getKey()), entry.getValue());
					}else{
						table.setValue((String) entry.getKey(), entry.getValue());
					}
                }
            }
        }
        // ---- 传参方式2 ---- //
        String itemsName = (String) requestData.get("ItemsName");
        List<Map<String, Object>> items = (List<Map<String, Object>>) requestData.get("Items");
        if (StringUtils.isNotEmpty(itemsName)){
            JCoTable table = function.getTableParameterList().getTable(itemsName);
            for (int i = 0; i < items.size(); i++) {
                table.appendRow();
                table.setRow(i);
                Map<String, Object> item = items.get(i);
                Iterator it = item.entrySet().iterator();
                while (it.hasNext()){
                    Map.Entry entry = (Map.Entry) it.next();
					if (null != mappingConfig && StringUtils.isNotEmpty(mappingConfig.getString((String) entry.getKey()))){
						table.setValue(mappingConfig.getString((String) entry.getKey()), entry.getValue());
					}else{
						table.setValue((String) entry.getKey(), entry.getValue());
					}
                }
            }
        }
        // ---- 传参方式3 ---- //
        Map<String, Map> mapItems = (Map<String, Map>) requestData.get("MapItems");
        if (mapItems != null){
            Iterator it = mapItems.entrySet().iterator();
            while (it.hasNext()){
                Map.Entry entry = (Map.Entry) it.next();
                String key = (String) entry.getKey();
                Map vMap = (Map) entry.getValue();
                JCoTable table = function.getTableParameterList().getTable(key);
                Iterator vit = vMap.entrySet().iterator();
                while (vit.hasNext()){
                    Map.Entry vEntry = (Map.Entry) vit.next();
					if (null != mappingConfig && StringUtils.isNotEmpty(mappingConfig.getString((String) vEntry.getKey()))){
						table.setValue(mappingConfig.getString((String) vEntry.getKey()), vEntry.getValue());
					}else{
						table.setValue((String) vEntry.getKey(), vEntry.getValue());
					}
                }
            }
        }
        // ---- 传参方式4 ---- //
        Map<String, List<Map>> mapListItems = (Map<String, List<Map>>) requestData.get("MapListItems");
        if (mapListItems != null){
            Iterator it = mapListItems.entrySet().iterator();
            while (it.hasNext()){
                Map.Entry entry = (Map.Entry) it.next();
                String key = (String) entry.getKey();
                List<Map> listMap = (List<Map>) entry.getValue();
                JCoTable table = function.getTableParameterList().getTable(key);
                for(int i = 0; i < listMap.size(); i++){
                    Iterator vit = listMap.get(i).entrySet().iterator();
                    table.appendRow();
                    table.setRow(i);
                    while (vit.hasNext()){
                        Map.Entry vEntry = (Map.Entry) vit.next();
						if (null != mappingConfig && StringUtils.isNotEmpty(mappingConfig.getString((String) vEntry.getKey()))){
							table.setValue(mappingConfig.getString((String) vEntry.getKey()), vEntry.getValue());
						}else{
							table.setValue((String) vEntry.getKey(), vEntry.getValue());
						}
                    }
                }
            }
        }
        sendReqLog(interMsgData, "ESB", "SAP", function);
        long startTime = System.currentTimeMillis();

        RfcManager.getInstance().execute(function);

        long endTime = System.currentTimeMillis();
        int time = (int) (endTime - startTime);
        sendResLog(interMsgData, "SAP", "ESB", time, function);
		// ---- 返回数据 ---- //
        // 取输出表名及输出类型 0:字段Filed 1:结构Structure 2:表Table
//		Integer exportParamsType = (Integer) requestData.get("ExportParamsType"); // 输入类型
		List<String> exportTableName = (List<String>) requestData.get("ExportTableName");

		Map<String, Object> result = new HashMap<String, Object>();
//		exportParamsType = exportParamsType == null ? 2 : exportParamsType;
//		if (exportParamsType.equals(0) || exportParamsType.equals(1)){
//			// 导出数据类型 0,1: 字段或结构
//			for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
//				result = traversalTable(result, iterator, rfcName, exportTableName, exportParameterConfig);
//			}
//		}else if(exportParamsType.equals(2)) {
//			// 导出数据类型 2: 表
//			for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
//				result = traversalTable(result, iterator, rfcName, exportTableName, exportParameterConfig);
//			}
//		} else if (exportParamsType.equals(3)) {
//			// 导出数据类型 3: 字段 + 表
//			for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
//				result = traversalTable(result, iterator, rfcName, exportTableName, exportParameterConfig);
//			}
//			for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
//				result = traversalTable(result, iterator, rfcName, exportTableName, exportParameterConfig);
//			}
//		}
		if (null != function.getExportParameterList()){
			for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
				result = traversalTable(result, iterator, rfcName, exportTableName, exportParameterConfig);
			}
		}
		if (null != function.getTableParameterList()){
			for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
				result = traversalTable(result, iterator, rfcName, exportTableName, exportParameterConfig);
			}
		}
        return result;
    }


	private Map<String, String> Z_POS_MBST_CREATE_CX(Map<String, Object> head, List<Map<String, Object>> items, InterMsgData interMsgData)
			throws EsbException {
		Log.getInstance().stdDebug("==================Z_POS_MBST_CREATE_CX================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_POS_MBST_CREATE");
		SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");// 小写的mm表示的是分钟

		function.getImportParameterList().setValue("BUDAT", head.get("BUDAT"));
		function.getImportParameterList().setValue("MBLNR", head.get("MBLNR"));
		function.getImportParameterList().setValue("ZID", head.get("ZID"));

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, String> result = new HashMap<String, String>();
		String tmp = tranfersInfoMessage(function);
		result.put("MESSAGE", tmp != null ? tmp:(String) function.getTableParameterList().getTable("ET_RET").getValue("MESSAGE"));
		result.put("TYPE", (String) function.getTableParameterList().getTable("ET_RET").getValue("TYPE"));
		return result;
	}

	private Map<String, String> Z_POS_DO_CREATE2_ZCK(Map<String, Object> head, List<Map<String, Object>> items, InterMsgData interMsgData)
			throws EsbException {

		Log.getInstance().stdDebug("==================Z_POS_DO_CREATE2_ZCK================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_POS_DO_CREATE2");

        function.getImportParameterList().setValue("BUDAT", head.get("BUDAT"));
        function.getImportParameterList().setValue("VERUR", head.get("VERUR"));
        function.getImportParameterList().setValue("I_VBELN", head.get("I_VBELN"));
        function.getImportParameterList().setValue("ZTYPE", head.get("ZTYPE"));
        function.getImportParameterList().setValue("LIFEX", head.get("LIFEX"));
        function.getImportParameterList().setValue("ZTEXT", head.get("ZTEXT"));
        function.getImportParameterList().setValue("LGORT", head.get("LGORT"));

        JCoTable table = function.getTableParameterList().getTable("ITAB");
        for (int i = 0; i < items.size(); i++) {
            table.setRow(i);
            table.appendRow();
            table.setValue("REF_DOC", items.get(i).get("REF_DOC"));
            table.setValue("REF_ITEM", items.get(i).get("REF_ITEM"));
            table.setValue("DLV_QTY", items.get(i).get("DLV_QTY"));
            table.setValue("SALES_UNIT", items.get(i).get("SALES_UNIT"));
        }

        JCoTable table1 = function.getTableParameterList().getTable("ITAB2");
        for (int i = 0; i < items.size(); i++) {
            table1.setRow(i);
            table1.appendRow();
            table1.setValue("REF_DOC", items.get(i).get("REF_DOC"));
            table1.setValue("REF_ITEM", items.get(i).get("REF_ITEM"));
            table1.setValue("DLV_QTY", items.get(i).get("DLV_QTY"));
            table1.setValue("SALES_UNIT", items.get(i).get("SALES_UNIT"));
            table1.setValue("KANNR", items.get(i).get("KANNR"));
        }

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, String> result = new HashMap<String, String>();

		String saptype = (String) function.getTableParameterList().getTable("ET_RET").getValue("TYPE");
		String sapid = null;
		if ("S".equals(saptype)) {
			JCoTable tmp = function.getTableParameterList().getTable("DLVNO");
			if (tmp != null && !tmp.isEmpty()) {
				sapid = ((String) tmp.getValue("DELIV_NUMB")).replaceAll("^0*", "");
			}
		}

		String tmp = tranfersInfoMessage(function);
		result.put("MESSAGE", tmp != null ? tmp:(String) function.getTableParameterList().getTable("ET_RET").getValue("MESSAGE"));
		result.put("TYPE", (String) function.getTableParameterList().getTable("ET_RET").getValue("TYPE"));
		if (sapid != null)
			result.put("SAPID", sapid);
		Log.getInstance().stdDebug("==================Z_POS_DO_CREATE2_ZCK================ end");
		return result;
		
	}

	private Map<String, String> Z_POS_PO_CREATE_ZZD(Map<String, Object> head, List<Map<String, Object>> items, InterMsgData interMsgData)
			throws EsbException {
		Log.getInstance().stdDebug("==================Z_POS_PO_CREATE_ZZD================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_POS_PO_CREATE");
		JCoStructure st = (JCoStructure) function.getImportParameterList().getValue("IT_EKKO");

		st.setValue("SUPPL_PLNT", head.get("SUPPL_PLNT"));
		st.setValue("ERZET", head.get("ERZET"));
		st.setValue("CREAT_DATE", head.get("CREAT_DATE"));
		st.setValue("PUR_GROUP", head.get("PUR_GROUP"));
		st.setValue("VENDOR_NO", head.get("VENDOR_NO"));
		st.setValue("ERDAT", head.get("ERDAT"));
		st.setValue("PURCH_ORG", head.get("PURCH_ORG"));
		st.setValue("COMP_CODE", head.get("COMP_CODE"));
		st.setValue("COND_VALUE", head.get("COND_VALUE"));
		st.setValue("CURRENCY", head.get("CURRENCY"));
		st.setValue("DOC_TYPE", head.get("DOC_TYPE"));
		st.setValue("PO_NUMBER", head.get("PO_NUMBER"));
		st.setValue("VENDOR", head.get("VENDOR"));
		//新增转储发票号
		st.setValue("COLLECT_NO", head.get("COLLECT_NO"));
		// TMS计划单号
		st.setValue("ZJJDH", head.get("ZJJDH"));

		JCoTable table = function.getTableParameterList().getTable("IT_EKPO");

		for (int i = 0; i < items.size(); i++) {
			table.appendRow();
			table.setRow(i);
			Map<String, Object> orderitem = items.get(i);

			table.setValue("PLANT", orderitem.get("PLANT"));
			table.setValue("MATERIAL", orderitem.get("MATERIAL"));
			table.setValue("PO_ITEM", orderitem.get("PO_ITEM"));
			table.setValue("SHORT_TEXT", orderitem.get("SHORT_TEXT"));
			table.setValue("DELIVERY_DATE", orderitem.get("DELIVERY_DATE"));
			table.setValue("PO_UNIT", orderitem.get("PO_UNIT"));
			table.setValue("MATL_GROUP", orderitem.get("MATL_GROUP"));
			table.setValue("QUANTITY", orderitem.get("QUANTITY"));
			if (orderitem.get("ITEM_CAT") != null && !"".equals(orderitem.get("ITEM_CAT"))) {
				table.setValue("ITEM_CAT", orderitem.get("ITEM_CAT"));
			}
			if (orderitem.get("NET_PRICE") != null && !"".equals(orderitem.get("NET_PRICE"))) {
				table.setValue("NET_PRICE", orderitem.get("NET_PRICE"));
			}

			// SPMS海外增加
			table.setValue("WHS_DUMP_NO", orderitem.get("WHS_DUMP_NO"));
			table.setValue("PACKAGE_CODE", orderitem.get("PACKAGE_CODE"));
			table.setValue("SERIAL", orderitem.get("SERIAL"));
		}

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, String> result = new HashMap<String, String>();
		String tmp = tranfersInfoMessage(function);
		String type = "", msg = "";
		if (function.getTableParameterList().getTable("ET_RET").getNumRows() > 0) {
			type = (String) function.getTableParameterList().getTable("ET_RET").getValue("TYPE");
			msg = (String) function.getTableParameterList().getTable("ET_RET").getValue("MESSAGE");
		}
		result.put("MESSAGE", tmp != null ? tmp : msg);
		result.put("TYPE", type);
		Log.getInstance().stdDebug("==================Z_POS_PO_CREATE_ZZD================  end");
		return result;
	}

	private Map<String, String> Z_POS_DO_POST_CREATE2_GZ(Map<String, Object> head, List<Map<String, Object>> items, InterMsgData interMsgData)
			throws EsbException {
		Log.getInstance().stdDebug("==================Z_POS_DO_POST_CREATE2_GZ================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_POS_DO_POST_CREATE2");

		function.getImportParameterList().setValue("BUDAT", head.get("BUDAT"));
		function.getImportParameterList().setValue("D_VBELN", head.get("D_VBELN"));
		function.getImportParameterList().setValue("BOLNR", head.get("BOLNR"));
		function.getImportParameterList().setValue("TRAID", head.get("TRAID"));
		function.getImportParameterList().setValue("LGORT", head.get("LGORT"));

		JCoTable table = function.getTableParameterList().getTable("IT_LIPS");

		for (int i = 0; i < items.size(); i++) {
			table.appendRow();
			table.setRow(i);
			Map<String, Object> orderitem = items.get(i);

			table.setValue("LFIMG", orderitem.get("LFIMG"));
			table.setValue("POSNR", orderitem.get("POSNR"));
			table.setValue("MEINS", orderitem.get("MEINS"));
			table.setValue("VBELN", orderitem.get("VBELN"));
		}

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		String saptype = (String) function.getTableParameterList().getTable("ET_RET").getValue("TYPE");
		String sapid = null;
		if ("S".equals(saptype)) {
			JCoTable tmp = function.getTableParameterList().getTable("IT_LIPS");
			if (tmp != null && !tmp.isEmpty()) {
				sapid = ((String) tmp.getValue("LFBNR"));
			}
		}
		
		Map<String, String> result = new HashMap<String, String>();
		String tmp = tranfersInfoMessage(function);
		result.put("MESSAGE", tmp != null ? tmp:(String) function.getTableParameterList().getTable("ET_RET").getValue("MESSAGE"));
		result.put("TYPE", saptype);
		if (sapid != null)
			result.put("SAPID", sapid);
		Log.getInstance().stdDebug("==================Z_POS_DO_POST_CREATE2_GZ================ end");
		return result;
	}

	private Map<String, String> Z_POS_PO_CREATE_ZZD_ITEM_OFF(Map<String, Object> head, List<Map<String, Object>> items, InterMsgData interMsgData)
			throws EsbException {

		Log.getInstance().stdDebug("==================Z_POS_PO_CREATE_ZZD_ITEM_OFF================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_POS_PO_CREATE");

		function.getImportParameterList().setValue("ZDEL", head.get("ZDEL"));
		JCoStructure st = (JCoStructure) function.getImportParameterList().getValue("IT_EKKO");
		st.setValue("PO_NUMBER", head.get("PO_NUMBER"));

		JCoTable table = function.getTableParameterList().getTable("IT_EKPO");

		for (int i = 0; i < items.size(); i++) {
			table.appendRow();
			table.setRow(i);
			Map<String, Object> orderitem = items.get(i);

			table.setValue("PO_ITEM", orderitem.get("PO_ITEM"));
		}
		
		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, String> result = new HashMap<String, String>();
		String tmp = tranfersInfoMessage(function);
		result.put("MESSAGE", tmp != null ? tmp:(String) function.getTableParameterList().getTable("ET_RET").getValue("MESSAGE"));
		result.put("TYPE", (String) function.getTableParameterList().getTable("ET_RET").getValue("TYPE"));
		Log.getInstance().stdDebug("==================Z_POS_PO_CREATE_ZZD_ITEM_OFF================");
		return result;
	}

	private Map<String, String> Z_POS_MB1A_CREATE2_PD(Map<String, Object> head, List<Map<String, Object>> items, InterMsgData interMsgData)
			throws EsbException {

		JCoFunction function = RfcManager.getInstance().getFunction("Z_POS_MB1A_CREATE2");

		function.getImportParameterList().setValue("BUDAT", head.get("BUDAT"));
		function.getImportParameterList().setValue("BWART", head.get("BWART"));
		function.getImportParameterList().setValue("BKTXT", head.get("BKTXT"));

		JCoTable table = function.getTableParameterList().getTable("IT_MSEG");

		for (int i = 0; i < items.size(); i++) {
			table.appendRow();
			table.setRow(i);
			Map<String, Object> orderitem = items.get(i);

			table.setValue("GRUND", orderitem.get("GRUND"));
			table.setValue("ERFMG", orderitem.get("ERFMG"));
			table.setValue("LGORT", orderitem.get("LGORT"));
			table.setValue("ERFME", orderitem.get("ERFME"));
			table.setValue("WERKS", orderitem.get("WERKS"));
			table.setValue("MATNR", orderitem.get("MATNR"));
		}

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, String> result = new HashMap<String, String>();
		String tmp = tranfersInfoMessage(function);
		result.put("MESSAGE", tmp != null ? tmp:(String) function.getTableParameterList().getTable("ET_RET").getValue("MESSAGE"));
		result.put("TYPE", (String) function.getTableParameterList().getTable("ET_RET").getValue("TYPE"));
		return result;
	}

	private Map<String, String> Z_POS_MB1A_CREATE2_CH(Map<String, Object> head, List<Map<String, Object>> items, InterMsgData interMsgData)
			throws EsbException {
		JCoFunction function = RfcManager.getInstance().getFunction("Z_POS_MB1A_CREATE2");

		function.getImportParameterList().setValue("BUDAT", head.get("BUDAT"));
		function.getImportParameterList().setValue("BWART", head.get("BWART"));
		function.getImportParameterList().setValue("BKTXT", head.get("BKTXT"));

		JCoTable table = function.getTableParameterList().getTable("IT_MSEG");

		for (int i = 0; i < items.size(); i++) {
			table.appendRow();
			table.setRow(i);
			Map<String, Object> orderitem = items.get(i);

			table.setValue("UMMAT", orderitem.get("UMMAT"));
			table.setValue("UMLGO", orderitem.get("UMLGO"));
			table.setValue("ZEILE", orderitem.get("ZEILE"));
			table.setValue("UMWRK", orderitem.get("UMWRK"));
			table.setValue("ERFMG", orderitem.get("ERFMG"));
			table.setValue("LGORT", orderitem.get("LGORT"));
			table.setValue("ERFME", orderitem.get("ERFME"));
			table.setValue("WERKS", orderitem.get("WERKS"));
			table.setValue("MATNR", orderitem.get("MATNR"));
		}

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, String> result = new HashMap<String, String>();
		String tmp = tranfersInfoMessage(function);
		result.put("MESSAGE", tmp != null ? tmp:(String) function.getTableParameterList().getTable("ET_RET").getValue("MESSAGE"));
		result.put("TYPE", (String) function.getTableParameterList().getTable("ET_RET").getValue("TYPE"));
		return result;
	}

	private Map<String, String> Z_POS_MB1A_CREATE2_CB(Map<String, Object> head, List<Map<String, Object>> items, InterMsgData interMsgData)throws EsbException {
		JCoFunction function = RfcManager.getInstance().getFunction("Z_POS_MB1A_CREATE2");

		function.getImportParameterList().setValue("BUDAT", head.get("BUDAT"));
		function.getImportParameterList().setValue("BWART", head.get("BWART"));
		function.getImportParameterList().setValue("KOSTL", head.get("KOSTL"));
		function.getImportParameterList().setValue("ZID", head.get("ZID"));
		function.getImportParameterList().setValue("BKTXT", head.get("BKTXT"));
		//增加oanumber参照
		function.getImportParameterList().setValue("XBLNR", head.get("XBLNR"));

		JCoTable table = function.getTableParameterList().getTable("IT_MSEG");

		for (int i = 0; i < items.size(); i++) {
			table.appendRow();
			table.setRow(i);
			Map<String, Object> orderitem = items.get(i);

			if (orderitem.get("LFBNR") != null && !"".equals(orderitem.get("LFBNR"))) {
				table.setValue("LFBNR", orderitem.get("LFBNR"));
			}
			
			if (orderitem.get("SGTXT") != null && !"".equals(orderitem.get("SGTXT"))) {
				table.setValue("SGTXT", orderitem.get("SGTXT"));
			}
			
			table.setValue("ZEILE", orderitem.get("ZEILE"));
			table.setValue("AUFNR", orderitem.get("AUFNR"));
			
			if (orderitem.get("GRUND") != null && !"".equals(orderitem.get("GRUND"))) {
				table.setValue("GRUND", orderitem.get("GRUND"));
			}
			
			table.setValue("KOSTL", orderitem.get("KOSTL"));
			table.setValue("ERFMG", orderitem.get("ERFMG"));
			table.setValue("LGORT", orderitem.get("LGORT"));
			table.setValue("ERFME", orderitem.get("ERFME"));
			
			if (orderitem.get("WEMPF") != null && !"".equals(orderitem.get("WEMPF"))) {
				table.setValue("WEMPF", orderitem.get("WEMPF"));
			}
			table.setValue("WERKS", orderitem.get("WERKS"));
			table.setValue("MATNR", orderitem.get("MATNR"));
		}

        sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();
		
		RfcManager.getInstance().execute(function);
		
        long endTime = System.currentTimeMillis();
        int time = (int) (endTime - startTime);
        sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, String> result = new HashMap<String, String>();
		JCoTable tmp = function.getTableParameterList().getTable("ET_RET");
		String tmp1 = tranfersInfoMessage(function);
		result.put("MESSAGE", tmp1 != null ? tmp1:(String) function.getTableParameterList().getTable("ET_RET").getValue("MESSAGE"));
		result.put("TYPE", (String) tmp.getValue("TYPE"));
		return result;
	}

	private Map<String, String> Z_POS_MB1A_CREATE2_DB(Map<String, Object> head, List<Map<String, Object>> items, InterMsgData interMsgData)
			throws EsbException {

		Log.getInstance().stdDebug("==================Z_POS_MB1A_CREATE2_DB================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_POS_MB1A_CREATE2");

		function.getImportParameterList().setValue("BUDAT", head.get("BUDAT"));
		function.getImportParameterList().setValue("BWART", head.get("BWART"));
		function.getImportParameterList().setValue("BKTXT", head.get("BKTXT"));

		JCoTable table = function.getTableParameterList().getTable("IT_MSEG");

		for (int i = 0; i < items.size(); i++) {
			table.appendRow();
			table.setRow(i);
			Map<String, Object> orderitem = items.get(i);

			table.setValue("UMLGO", orderitem.get("UMLGO"));
			table.setValue("ZEILE", orderitem.get("ZEILE"));
			table.setValue("UMWRK", orderitem.get("UMWRK"));
			table.setValue("ERFMG", orderitem.get("ERFMG"));
			table.setValue("LGORT", orderitem.get("LGORT"));
			table.setValue("ERFME", orderitem.get("ERFME"));
			table.setValue("WERKS", orderitem.get("WERKS"));
			table.setValue("MATNR", orderitem.get("MATNR"));
		}

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, String> result = new HashMap<String, String>();
		String tmp = tranfersInfoMessage(function);
		result.put("MESSAGE", tmp != null ? tmp:(String) function.getTableParameterList().getTable("ET_RET").getValue("MESSAGE"));
		result.put("TYPE", (String) function.getTableParameterList().getTable("ET_RET").getValue("TYPE"));
		return result;
	}

	private Map<String, String> Z_POS_DO_CREATE2_FP(Map<String, Object> head, List<Map<String, Object>> items, InterMsgData interMsgData)
			throws EsbException {

		Log.getInstance().stdDebug("==================Z_POS_DO_CREATE2_FP================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_POS_DO_CREATE2");
		function.getImportParameterList().setValue("I_VBELN", head.get("I_VBELN"));

		Date d = new Date((Long) head.get("DOCDT"));
		SimpleDateFormat sf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
		function.getImportParameterList().setValue("DOCDT", sf.format(d));

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, String> result = new HashMap<String, String>();
		String tmp = tranfersInfoMessage(function);
		result.put("MESSAGE", tmp != null ? tmp:(String) function.getTableParameterList().getTable("ET_RET").getValue("MESSAGE"));
		result.put("TYPE", (String) function.getTableParameterList().getTable("ET_RET").getValue("TYPE"));
		return result;
	}

	private Map<String, String> Z_POS_ORDER_MAT_CREATE(Map<String, Object> head, List<Map<String, Object>> items, InterMsgData interMsgData)
			throws EsbException {

		Log.getInstance().stdDebug("==================Z_POS_ORDER_MAT_CREATE================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_POS_ORDER_MAT_CREATE");

		function.getImportParameterList().setValue("BUDAT", head.get("BUDAT"));
		function.getImportParameterList().setValue("AUFNR", head.get("AUFNR"));
		function.getImportParameterList().setValue("BWART", head.get("BWART"));
		function.getImportParameterList().setValue("ZID", head.get("ZID"));
		function.getImportParameterList().setValue("BLDAT", head.get("BLDAT"));
		function.getImportParameterList().setValue("BKTXT", head.get("BKTXT"));

		JCoTable table = function.getTableParameterList().getTable("IT_MSEG");

		for (int i = 0; i < items.size(); i++) {
			table.appendRow();
			table.setRow(i);
			Map<String, Object> orderitem = items.get(i);
			table.setValue("ZEILE", orderitem.get("ZEILE"));
			table.setValue("ERFMG", orderitem.get("ERFMG"));
			table.setValue("LGORT", orderitem.get("LGORT"));
			table.setValue("ERFME", orderitem.get("ERFME"));
			table.setValue("WERKS", orderitem.get("WERKS"));
			table.setValue("MATNR", orderitem.get("MATNR"));
		}

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, String> result = new HashMap<String, String>();
		String tmp = tranfersInfoMessage(function);
		result.put("MESSAGE", tmp != null ? tmp:(String) function.getTableParameterList().getTable("ET_RET").getValue("MESSAGE"));
		result.put("TYPE", (String) function.getTableParameterList().getTable("ET_RET").getValue("TYPE"));
		return result;
	}
	
	private Map<String, Object> Z_POS_CUSTOMER001(Map<String, Object> head, List<Map<String, Object>> items, InterMsgData interMsgData)
			throws EsbException {
		
		Log.getInstance().stdDebug("==================Z_POS_CUSTOMER001================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_POS_CUSTOMER001");
		function.getImportParameterList().setValue("I_BUKRS", head.get("bukrs"));
		function.getImportParameterList().setValue("I_KUNNR", head.get("kunnr"));
		function.getImportParameterList().setValue("I_GSBER", head.get("werks"));
		function.getImportParameterList().setValue("I_DATES", head.get("starttime"));
		function.getImportParameterList().setValue("I_DATEE", head.get("stoptime"));
		
		
		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();
		
		RfcManager.getInstance().execute(function);
		
		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);
		Map<String, Object> result = new HashMap<String, Object>();
		for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator);
		}
		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
			result = traversalField(result, iterator);
		}
		return result;
	}
	
	private Map<String, Object> spms_srm_purchase_status(Map<String, Object> head, List<Map<String, Object>> items, InterMsgData interMsgData)
			throws EsbException {

		Log.getInstance().stdDebug("==================spms_srm_purchase_status================");
		Map<String, Object> result = new HashMap<String, Object>();
		
		HashMap<String, Object> reqHm=new HashMap<>();
		reqHm.put("businessType", "spms_srm_purchase_status");
		reqHm.put("tokenCode", "91d051ea184f4ed4b3be218858000888");
		
		List<Object> list=new ArrayList<>();
		for (int i = 0; i < items.size(); i++) {
			Map<String, Object> orderitem = items.get(i);
			HashMap<String, String> data = new HashMap<>();
			data.put("purchaseRequestNumber", orderitem.get("purchaseRequestNumber") + "");
			data.put("requestItemNumber", orderitem.get("requestItemNumber") + "");
			list.add(data);
		}
		reqHm.put("data", list);
		
		String reqJson=JSON.toJSONString(reqHm, SerializerFeature.WriteMapNullValue);
		String opration = "调用SRM接口";
		sendReqLog(interMsgData, "ESB", "SRM", reqJson, opration);
		long startTime = System.currentTimeMillis();
		
		RestTemplate restTemplate = new RestTemplate();
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		HttpEntity<String> requestEntity = new HttpEntity<String>(reqJson, headers);

		List<HttpMessageConverter<?>> httpMessageConverters = restTemplate.getMessageConverters();
		httpMessageConverters.stream().forEach(httpMessageConverter -> {
			if (httpMessageConverter instanceof StringHttpMessageConverter) {
				StringHttpMessageConverter messageConverter = (StringHttpMessageConverter) httpMessageConverter;
				messageConverter.DEFAULT_CHARSET.forName("UTF-8");
			}
		});

//		String srmRestUrl = (String) cm.get("SPMS_SRM_PURCHASE_STATUS_URL");
		String redisPre = ConstantUtil.REDIS_PRE_DICT + ":" + ConstantUtil.REDIS_PRE_DICT_TYPE_URL_SRM + ":";
		String srmRestUrl = getRedisValue(redisPre + "SPMS_SRM_PURCHASE_STATUS_URL");
		Log.getInstance().stdDebug("SPMS_SRM_PURCHASE_STATUS_URL=" + srmRestUrl);
		String srmRes = restTemplate.postForObject(srmRestUrl, requestEntity, String.class);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SRM", "ESB", time, srmRes,"收到SRM接口返回信息");

		Map resMap = JSON.parseObject(srmRes, Map.class);
		List<Object> dataList= JSON.parseArray(resMap.get("data")+"", Object.class);

		result.put("DATA", dataList);
		result.put("MESSAGE", resMap.get("message"));
		result.put("TYPE", resMap.get("status"));
		return result;
	}
	
	private Map<String, Object> spms_srm_purchase_info(String message, InterMsgData interMsgData) throws EsbException {

		Log.getInstance().stdDebug("==================spms_srm_purchase_info================");
		String opration = "调用SRM接口";
		sendReqLog(interMsgData, "ESB", "SRM", message, opration);
		long startTime = System.currentTimeMillis();

		RestTemplate restTemplate = new RestTemplate();
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		HttpEntity<String> requestEntity = new HttpEntity<String>(message, headers);

		List<HttpMessageConverter<?>> httpMessageConverters = restTemplate.getMessageConverters();
		httpMessageConverters.stream().forEach(httpMessageConverter -> {
			if (httpMessageConverter instanceof StringHttpMessageConverter) {
				StringHttpMessageConverter messageConverter = (StringHttpMessageConverter) httpMessageConverter;
				messageConverter.DEFAULT_CHARSET.forName("UTF-8");
			}
		});

//		String srmRestUrl = (String) cm.get("SPMS_SRM_PURCHASE_INFO_URL");
		String redisPre = ConstantUtil.REDIS_PRE_DICT + ":" + ConstantUtil.REDIS_PRE_DICT_TYPE_URL_SRM + ":";
		String srmRestUrl = getRedisValue(redisPre + "SPMS_SRM_PURCHASE_INFO_URL");
		Log.getInstance().stdDebug("SPMS_SRM_PURCHASE_INFO_URL=" + srmRestUrl);
		String resJson = restTemplate.postForObject(srmRestUrl, requestEntity, String.class);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SRM", "ESB", time, resJson, "收到SRM接口返回信息");

		Map result = JSON.parseObject(resJson, Map.class);
		return result;
	}
	
	@SuppressWarnings("unchecked")
	private Map<String, Object> spms_srm_material_request_time(String message, InterMsgData interMsgData) throws EsbException {
		
		Log.getInstance().stdDebug("==================spms_srm_material_request_time================");
		String opration = "调用SRM接口";
		sendReqLog(interMsgData, "ESB", "SRM", message, opration);
		long startTime = System.currentTimeMillis();
		
		RestTemplate restTemplate = new RestTemplate();
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		HttpEntity<String> requestEntity = new HttpEntity<String>(message, headers);
		
		List<HttpMessageConverter<?>> httpMessageConverters = restTemplate.getMessageConverters();
		httpMessageConverters.stream().forEach(httpMessageConverter -> {
			if (httpMessageConverter instanceof StringHttpMessageConverter) {
				StringHttpMessageConverter messageConverter = (StringHttpMessageConverter) httpMessageConverter;
				messageConverter.DEFAULT_CHARSET.forName("UTF-8");
			}
		});
		
		String redisPre = ConstantUtil.REDIS_PRE_DICT + ":" + ConstantUtil.REDIS_PRE_DICT_TYPE_URL_SRM + ":";
		String srmRestUrl = getRedisValue(redisPre + "SPMS_SRM_MATERIAL_REQUEST_TIME_URL");
		Log.getInstance().stdDebug("SPMS_SRM_MATERIAL_REQUEST_TIME_URL=" + srmRestUrl);
		String resJson = restTemplate.postForObject(srmRestUrl, requestEntity, String.class);
		
		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SRM", "ESB", time, resJson, "收到SRM接口返回信息");
		
		Map result = JSON.parseObject(resJson, Map.class);
		return result;
	}

	private Map<String, Object> spms_srm_purchase_request_head_type(String message, InterMsgData interMsgData) throws EsbException {

		Log.getInstance().stdDebug("==================spms_srm_purchase_request_head_type================");
		Map<String, Object> result = new HashMap<String, Object>();
		String opration = "调用SRM接口";
		sendReqLog(interMsgData, "ESB", "SRM", message, opration);
		long startTime = System.currentTimeMillis();

		RestTemplate restTemplate = new RestTemplate();
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		HttpEntity<String> requestEntity = new HttpEntity<String>(message, headers);

		List<HttpMessageConverter<?>> httpMessageConverters = restTemplate.getMessageConverters();
		httpMessageConverters.stream().forEach(httpMessageConverter -> {
			if (httpMessageConverter instanceof StringHttpMessageConverter) {
				StringHttpMessageConverter messageConverter = (StringHttpMessageConverter) httpMessageConverter;
				messageConverter.DEFAULT_CHARSET.forName("UTF-8");
			}
		});

		String redisPre = ConstantUtil.REDIS_PRE_DICT + ":" + ConstantUtil.REDIS_PRE_DICT_TYPE_URL_SRM + ":";
		String srmRestUrl = getRedisValue(redisPre + "SPMS_SRM_PURCHASE_TYPE_URL");
		Log.getInstance().stdDebug("SPMS_SRM_PURCHASE_TYPE_URL=" + srmRestUrl);
		String resJson = restTemplate.postForObject(srmRestUrl, requestEntity, String.class);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SRM", "ESB", time, resJson, "收到SRM接口返回信息");

		Map resMap = JSON.parseObject(resJson, Map.class);
		List<Object> dataList= JSON.parseArray(resMap.get("data")+"", Object.class);

		result.put("DATA", dataList);
		result.put("MESSAGE", resMap.get("message"));
		result.put("TYPE", resMap.get("status"));
		return result;
	}
	
	private Map<String, String> Z_POS_SO_CREATE(Map<String, Object> head, List<Map<String, Object>> items, InterMsgData interMsgData)
			throws EsbException {
		Log.getInstance().stdDebug("==================Z_POS_SO_CREATE================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_POS_SO_CREATE");
		JCoStructure st = (JCoStructure) function.getImportParameterList().getValue("I_ZTSTORE2_12");
		st.setValue("POSID", head.get("POSID"));
//			st.setValue("ERDAT", head.get("20200623"));
//			st.setValue("ERZET", "08431");
		JCoTable jCoParameterList = function.getTableParameterList().getTable("IT_VBAK");
		jCoParameterList.appendRow();
		jCoParameterList.setRow(0);
		jCoParameterList.setValue("AUART", head.get("AUART"));// 订单类型
		jCoParameterList.setValue("VKORG", head.get("VKORG"));// 销售组织
		jCoParameterList.setValue("VTWEG", head.get("VTWEG"));// 分销渠道
		jCoParameterList.setValue("SPART", head.get("SPART"));// 产品组
		jCoParameterList.setValue("KUNNR", head.get("KUNNR"));// 客户号
		jCoParameterList.setValue("KUNNR2", head.get("KUNNR2"));// 负责人编号
		jCoParameterList.setValue("BSTKD_C", head.get("BSTKD_C"));// 负责人编号
		jCoParameterList.setValue("BSTKD_E", head.get("BSTKD_E"));// 总价折扣
		jCoParameterList.setValue("DZTERM", head.get("DZTERM"));// 付款条件
		jCoParameterList.setValue("IHREZ", head.get("IHREZ"));// 付款方式
		jCoParameterList.setValue("BNAME", head.get("BNAME"));// 操作员姓名
		jCoParameterList.setValue("NAME2", head.get("NAME2"));// 一次性客户真实姓名
		jCoParameterList.setValue("KONDA", head.get("KONDA"));// 一次性客户真实等级
		// rfctablevbak.setValue("NAME", head.get("vipname"));// 会员姓名
		jCoParameterList.setValue("WAERK", head.get("WAERK"));// orderresponsible
		jCoParameterList.setValue("MTLAUR", head.get("MTLAUR"));// 内外单标识

//			if (null != head.get("littlestock") && !head.get("littlestock").isEmpty()) {
//				jCoParameterList.setValue("ABRVW", head.get("littlestock"));// littlestock
//			}

		jCoParameterList.setValue("AUGRU", head.get("AUGRU") != null ? head.get("AUGRU") : "");// 订单原因
		jCoParameterList.setValue("AUDAT", head.get("AUDAT") != null ? head.get("AUDAT") : "");// 订单日期
		if (head.get("ZAMT") != null) {
			jCoParameterList.setValue("ZAMT", head.get("ZAMT"));// 需返利金额
			jCoParameterList.setValue("SHORT_TEXT", head.get("SHORT_TEXT"));// 需返利金额
		}

//			if ("S002".equals(head.get("auart"))) {
//				jCoParameterList.setValue("PRSDT", head.get("referencesaledate"));
//			}

		jCoParameterList = function.getTableParameterList().getTable("IT_VBAP");

		if (items != null) {
			for (int i = 0; i < items.size(); i++) {
				jCoParameterList.appendRow();
				jCoParameterList.setRow(i);
				Map<String, Object> orderitem = items.get(i);
				
				BigDecimal totalmoney = (BigDecimal) orderitem.get("NETWR");
				
				jCoParameterList.setValue("NETWR", totalmoney.setScale(2, RoundingMode.HALF_EVEN).doubleValue());// 实销价格
				jCoParameterList.setValue("POSNR", orderitem.get("POSNR"));// 项目编号
				jCoParameterList.setValue("MATNR", orderitem.get("MATNR"));// 物料编号
				jCoParameterList.setValue("VRKME", orderitem.get("VRKME"));// 销售单位
				jCoParameterList.setValue("KDMAT", orderitem.get("KDMAT"));// 客户所用的物料编号
				jCoParameterList.setValue("WERKS", orderitem.get("WERKS"));// 工厂
				jCoParameterList.setValue("LGORT", orderitem.get("LGORT"));// 仓库
				jCoParameterList.setValue("VKAUS", orderitem.get("VKAUS"));// 库存清理原因
				jCoParameterList.setValue("SHORT_TEXT", orderitem.get("SHORT_TEXT"));// 项目文本
				jCoParameterList.setValue("PSTYV", orderitem.get("PSTYV"));// 项目类型
				
				jCoParameterList.setValue("KSCHA1", orderitem.get("KSCHA1"));
				jCoParameterList.setValue("KSCHA2", orderitem.get("KSCHA2"));
				
				jCoParameterList.setValue("COND_VALUE", orderitem.get("COND_VALUE"));
//			jCoParameterList.setValue("COND_VALUE2",
//					((BigDecimal) orderitem.get("COND_VALUE2")).multiply(new BigDecimal(10)));// 税率
				jCoParameterList.setValue("COND_VALUE2", ((BigDecimal) orderitem.get("COND_VALUE2")));// 税率
				
				if(orderitem.get("ZPR1") != null)
					jCoParameterList.setValue("ZPR1", ((BigDecimal)orderitem.get("ZPR1")));// 价钱
				jCoParameterList.setValue("KWMENG", orderitem.get("KWMENG"));// 数量
				
				jCoParameterList.setValue("CURRENCY", orderitem.get("CURRENCY"));// 货币
				jCoParameterList.setValue("COND_P_UNT", orderitem.get("COND_P_UNT"));// 货币
				jCoParameterList.setValue("COND_UNIT", orderitem.get("COND_UNIT"));// 货币
			}
		}

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, String> result = new HashMap<String, String>();
		JCoStructure jco = (JCoStructure) function.getExportParameterList().getValue("U_RETURN");
		String tmp = tranfersInfoMessage(function);
		result.put("MESSAGE", tmp != null ? tmp:(String) jco.getValue("MESSAGE"));
		result.put("TYPE", (String) jco.getValue("TYPE"));
		return result;
	}

	private Map<String, String> Z_POS_PO_CREATE(Map<String, Object> head, List<Map<String, Object>> items, InterMsgData interMsgData)
			throws EsbException {
		Log.getInstance().stdDebug("==================Z_POS_PO_CREATE================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_POS_PO_CREATE");
		JCoStructure st = (JCoStructure) function.getImportParameterList().getValue("IT_EKKO");
		st.setValue("PO_NUMBER", head.get("EBELN"));
		st.setValue("ERDAT", new SimpleDateFormat("yyyyMMdd").format(new Date()));
		st.setValue("ERZET", new SimpleDateFormat("HHmmss").format(new Date()));
		st.setValue("COMP_CODE", head.get("BUKRS"));
		st.setValue("DOC_TYPE", head.get("BSART"));
		st.setValue("VENDOR_NO", head.get("VENDOR"));

		if ("ZU01".equals(head.get("BSART"))) {
			st.setValue("SUPPL_PLNT", head.get("LLIEF"));
			st.setValue("VENDOR", "");
		} else {
			st.setValue("SUPPL_PLNT", head.get("WERKS"));
			st.setValue("VENDOR", head.get("LLIEF"));
		}

		st.setValue("CURRENCY", head.get("TRANPRICECURRENCY"));
		st.setValue("CREAT_DATE",
				null != items.get(0).get("BEDAT") ? ((String) items.get(0).get("BEDAT")).replace("-", "") : null);
		st.setValue("PURCH_ORG", head.get("EKORG"));
		st.setValue("PUR_GROUP", head.get("EKGRP"));
		st.setValue("COND_VALUE", head.get("TRANPRICE"));

		JCoTable table = function.getTableParameterList().getTable("IT_EKPO");

		for (int i = 0; i < items.size(); i++) {
			table.appendRow();
			table.setRow(i);
			table.setValue("PO_ITEM", items.get(i).get("LPONR"));
			table.setValue("MATERIAL", items.get(i).get("EMANT"));
			if ("ZU01".equals(head.get("BSART"))) {
				table.setValue("ITEM_CAT", "U");
			}
			table.setValue("SHORT_TEXT", items.get(i).get("TXZ01"));
			table.setValue("PLANT", items.get(i).get("WERKS"));
			table.setValue("QUANTITY", items.get(i).get("MENGE"));
			table.setValue("MATL_GROUP", items.get(i).get("MATKL"));
			table.setValue("PO_UNIT", items.get(i).get("MEINS"));
			table.setValue("NET_PRICE", items.get(i).get("NETPR"));
			table.setValue("DELIVERY_DATE",
					null != items.get(0).get("AEDAT") ? ((String) items.get(0).get("AEDAT")).replace("-", "") : null);
		}

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, String> result = new HashMap<String, String>();
		String tmp = tranfersInfoMessage(function);
		result.put("MESSAGE", tmp != null ? tmp:(String) function.getTableParameterList().getTable("ET_RET").getValue("MESSAGE"));
		result.put("TYPE", (String) function.getTableParameterList().getTable("ET_RET").getValue("TYPE"));
		return result;
	}

	private Map<String, String> Z_POST_MIGO_CREATE(Map<String, Object> head, List<Map<String, Object>> items, InterMsgData interMsgData)
			throws EsbException {

		Log.getInstance().stdDebug("==================Z_POST_MIGO_CREATE================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_POST_MIGO_CREATE");
		function.getImportParameterList().setValue("DELIV_NUM", head.get("DELIV_NUM"));
		function.getImportParameterList().setValue("FRBNR", head.get("FRBNR"));
		function.getImportParameterList().setValue("BKTXT", head.get("BKTXT"));
		function.getImportParameterList().setValue("ZID", head.get("ZID"));
		function.getImportParameterList().setValue("BUDAT",
				null != head.get("BUDAT") ? ((String) head.get("BUDAT")).replace("-", "") : null);

		JCoTable table = function.getTableParameterList().getTable("IT_LIPS");

		for (int i = 0; i < items.size(); i++) {
			table.appendRow();
			table.setRow(i);
			table.setValue("POSNR", items.get(i).get("POSNR"));
			table.setValue("VBELN", items.get(i).get("VBELN"));
			table.setValue("LFIMG", items.get(i).get("LFIMG"));
			table.setValue("SPE_LIFEXPOS2", items.get(i).get("SPE_LIFEXPOS2"));
			table.setValue("MEINS", items.get(i).get("MEINS"));
			table.setValue("LGORT", items.get(i).get("LGORT"));
		}

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, String> result = new HashMap<String, String>();
		String tmp = tranfersInfoMessage(function);
		result.put("MESSAGE", tmp != null ? tmp:(String) function.getTableParameterList().getTable("ET_RET").getValue("MESSAGE"));
		result.put("TYPE", (String) function.getTableParameterList().getTable("ET_RET").getValue("TYPE"));
		return result;
	}

	private Map<String, String> Z_POS_MBST_CREATE(Map<String, Object> head, List<Map<String, Object>> items, InterMsgData interMsgData)
			throws EsbException {
		Log.getInstance().stdDebug("==================Z_POS_MBST_CREATE================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_POS_MBST_CREATE");

		JCoParameterList jcoParameterList = function.getImportParameterList();
		jcoParameterList.setValue("ZID", head.get("ZID"));
		jcoParameterList.setValue("MBLNR", head.get("OFFSAPID"));
		jcoParameterList.setValue("BUDAT",
				null != head.get("RECORDDATE") ? ((String) head.get("RECORDDATE")).replace("-", "") : null);

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, String> result = new HashMap<String, String>();
		String tmp = tranfersInfoMessage(function);
		result.put("MESSAGE", tmp != null ? tmp:(String) function.getTableParameterList().getTable("ET_RET").getValue("MESSAGE"));
		result.put("TYPE", (String) function.getTableParameterList().getTable("ET_RET").getValue("TYPE"));
		return result;
	}

	private Map<String, String> Z_POS_MB1A_CREATE2(Map<String, Object> head, List<Map<String, Object>> items, InterMsgData interMsgData)
			throws EsbException {
		Log.getInstance().stdDebug("==================Z_POS_MB1A_CREATE2================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_POS_MB1A_CREATE2");

		JCoParameterList jcoParameterList = function.getImportParameterList();
		jcoParameterList.setValue("BWART", head.get("OUTSTOCKTYPE"));
		jcoParameterList.setValue("KOSTL", head.get("CENTERID"));
		jcoParameterList.setValue("BKTXT", head.get("COSTID"));
		jcoParameterList.setValue("ZID", head.get("ZID"));
		jcoParameterList.setValue("XBLNR", "OANUMBER");
		jcoParameterList.setValue("BUDAT",
				null != head.get("recorddate") ? ((String) head.get("RECORDDATE")).replace("-", "") : null);

		JCoParameterList tables = function.getTableParameterList();

		for (int i = 0; i < items.size(); i++) {
			tables.getTable("IT_MSEG").appendRow();
			tables.getTable("IT_MSEG").setRow(i);
			tables.getTable("IT_MSEG").setValue("ZEILE", items.get(i).get("ROWITEM"));
			tables.getTable("IT_MSEG").setValue("MATNR", items.get(i).get("MATNR"));
			tables.getTable("IT_MSEG").setValue("ERFMG", items.get(i).get("LABST"));
			tables.getTable("IT_MSEG").setValue("ERFME", items.get(i).get("UNIT"));
			tables.getTable("IT_MSEG").setValue("LGORT", items.get(i).get("LGORT"));
			tables.getTable("IT_MSEG").setValue("WERKS", items.get(i).get("WERKS"));

			if (null == head.get("REMARK") || ((String) head.get("REMARK")).isEmpty()) {

			} else {
				tables.getTable("IT_MSEG").setValue("SGTXT", head.get("REMARK"));
			}

			if (null != items.get(i).get("center") && !((String) items.get(i).get("CENTER")).isEmpty()) {
				tables.getTable("IT_MSEG").setValue("KOSTL", items.get(i).get("CENTER"));
			} else {
				tables.getTable("IT_MSEG").setValue("KOSTL", head.get("CENTERID"));
			}
		}

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, String> result = new HashMap<String, String>();
		String tmp = tranfersInfoMessage(function);
		result.put("MESSAGE", tmp != null ? tmp:(String) function.getTableParameterList().getTable("ET_RET").getValue("MESSAGE"));
		result.put("TYPE", (String) function.getTableParameterList().getTable("ET_RET").getValue("TYPE"));
		return result;
	}

	private Map<String, String> Z_POS_DO_POST_CREATE2(Map<String, Object> head, List<Map<String, Object>> items, InterMsgData interMsgData)
			throws EsbException {

		Log.getInstance().stdDebug("==================Z_POS_DO_POST_CREATE2================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_POS_DO_POST_CREATE2");

		JCoParameterList jcoParameterList = function.getImportParameterList();
		jcoParameterList.setValue("D_VBELN", items.get(0).get("SAPID"));
		jcoParameterList.setValue("LGORT", items.get(0).get("LGORT"));
		jcoParameterList.setValue("BOLNR", items.get(0).get("ENCASEMENTID"));
		jcoParameterList.setValue("TRAID", items.get(0).get("CONTAINERID"));
		jcoParameterList.setValue("BUDAT",
				null != items.get(0).get("RECORDDATE") ? ((String) items.get(0).get("RECORDDATE")).replace("-", "")
						: null);

		JCoParameterList tables = function.getTableParameterList();

		for (int i = 0; i < items.size(); i++) {
			tables.getTable("IT_LIPS").appendRow();
			tables.getTable("IT_LIPS").setRow(i);
			tables.getTable("IT_LIPS").setValue("VBELN", items.get(i).get("SAPID"));
			tables.getTable("IT_LIPS").setValue("POSNR", items.get(i).get("ROWITEM"));
			tables.getTable("IT_LIPS").setValue("LFIMG", items.get(i).get("MENGE"));
			tables.getTable("IT_LIPS").setValue("MEINS", items.get(i).get("UNIT"));
		}

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, String> result = new HashMap<String, String>();
		String tmp = tranfersInfoMessage(function);
		result.put("MESSAGE", tmp != null ? tmp:(String) function.getTableParameterList().getTable("ET_RET").getValue("MESSAGE"));
		result.put("TYPE", (String) function.getTableParameterList().getTable("ET_RET").getValue("TYPE"));
		return result;
	}

	private Map<String, String> Z_POS_DO_CREATE2(Map<String, Object> head, List<Map<String, Object>> items, InterMsgData interMsgData)
			throws EsbException {

		Log.getInstance().stdDebug("==================Z_POS_DO_CREATE2================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_POS_DO_CREATE2");

		JCoParameterList jcoParameterList = function.getImportParameterList();
		jcoParameterList.setValue("I_VBELN", head.get("EBELN"));
		jcoParameterList.setValue("ZTYPE", "M");
		jcoParameterList.setValue("LGORT", items.get(0).get("LGORT"));
		jcoParameterList.setValue("ZTEXT", items.get(0).get("HREMARK"));
		jcoParameterList.setValue("VERUR", items.get(0).get("CARID"));
		jcoParameterList.setValue("LIFEX", items.get(0).get("CUSTOMID"));
		jcoParameterList.setValue("BUDAT",
				null != items.get(0).get("RECORDDATE") ? ((String) items.get(0).get("RECORDDATE")).replace("-", "")
						: null);

		JCoParameterList tables = function.getTableParameterList();

		for (int i = 0; i < items.size(); i++) {
			tables.getTable("ITAB").appendRow();
			tables.getTable("ITAB").setRow(i);
			tables.getTable("ITAB").setValue("REF_DOC", items.get(i).get("EBELN"));
			tables.getTable("ITAB").setValue("REF_ITEM", items.get(i).get("ROWITEM"));
			tables.getTable("ITAB").setValue("DLV_QTY", items.get(i).get("MENGE"));
			tables.getTable("ITAB").setValue("SALES_UNIT", items.get(i).get("UNIT"));

			tables.getTable("ITAB2").appendRow();
			tables.getTable("ITAB2").setRow(i);
			tables.getTable("ITAB2").setValue("REF_DOC", items.get(i).get("EBELN"));
			tables.getTable("ITAB2").setValue("REF_ITEM", items.get(i).get("ROWITEM"));
			tables.getTable("ITAB2").setValue("DLV_QTY", items.get(i).get("MENGE"));
			tables.getTable("ITAB2").setValue("SALES_UNIT", items.get(i).get("UNIT"));
			tables.getTable("ITAB2").setValue("KANNR", items.get(i).get("COLOURNO"));
		}

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, String> result = new HashMap<String, String>();
		String tmp = tranfersInfoMessage(function);
		result.put("MESSAGE", tmp != null ? tmp:(String) function.getTableParameterList().getTable("ET_RET").getValue("MESSAGE"));
		result.put("TYPE", (String) function.getTableParameterList().getTable("ET_RET").getValue("TYPE"));
		return result;
	}
	
	public Map<String, Object> Z_SRM_VENDER_MAINTAIN_CREATE_SWITCH(String appId, String functionName, Map<String, String> head, Map<String, List<Map<String, Object>>> itemMap, String message, InterMsgData interMsgData) throws EsbException {
		Map<String, Object> result = new HashMap<String, Object>();

		String srmSwitchMdm = (String) cm.get("SRM_SWITCH_MDM");
		Log.getInstance().stdDebug("==================Z_SRM_VENDER_MAINTAIN_CREATE_SWITCH===============srmSwitchMdm=" + srmSwitchMdm);

		// 1已切换
		if ("1".equals(srmSwitchMdm) && "SRM".equals(appId)) {
			result = Z_SRM_VENDER_MAINTAIN_CREATE_TO_MDM(message, interMsgData);

		} else if ("0".equals(srmSwitchMdm) && "SRM".equals(appId)) {
			result = Z_SRM_VENDER_MAINTAIN_CREATE_TO_SAP(head, itemMap, interMsgData);

		} else if ("TMS".equals(appId)) {
			result = Z_SRM_VENDER_MAINTAIN_CREATE_TO_SAP(head, itemMap, interMsgData);
		}

		return result;
	}
	
	public  Map<String, Object> Z_SRM_VENDER_MAINTAIN_CREATE_TO_SAP(Map<String, String> head, Map<String, List<Map<String, Object>>> itemMap, InterMsgData interMsgData) throws EsbException {
		Log.getInstance().stdDebug("==================Z_SRM_VENDER_MAINTAIN_CREATE_MDM==To SAP==============");
		
        JCoFunction function = RfcManager.getInstance().getFunction("Z_SRM_VENDER_MAINTAIN_CREATE");
        
        JCoStructure st = (JCoStructure) function.getImportParameterList().getValue("IM_VENDER");
        st.setValue("LIFNR", head.get("lifnr"));
        st.setValue("KTOKK", head.get("ktokk"));
        st.setValue("BRSCH", head.get("brsch"));
        st.setValue("STCEG", head.get("stceg"));
        st.setValue("NAME1", head.get("name1"));
        st.setValue("NAME2", head.get("name2"));
        st.setValue("SORTL", head.get("sortl"));
        st.setValue("STRAS", head.get("stras"));
        st.setValue("ORT01", head.get("ort01"));
        st.setValue("PSTLZ", head.get("pstlz"));
        st.setValue("LAND1", head.get("land1"));
        st.setValue("REGIO", head.get("regio"));
        st.setValue("SPRAS", head.get("spras"));
        st.setValue("SPRAS_T002", head.get("spras_t002"));
        st.setValue("TELF1", head.get("telf1"));
        st.setValue("TELF2", head.get("telf2"));
        st.setValue("TELFX", head.get("telfx"));
        st.setValue("SPERR", head.get("sperr"));
        st.setValue("SPERM", head.get("sperm"));
        st.setValue("LOEVM", head.get("loevm"));

        JCoTable table1 = function.getTableParameterList().getTable("PT_BANKS");
        List<Map<String, Object>> items = itemMap.get("PT_BANKS");
        for (int i = 0; i < items.size(); i++) {
            table1.appendRow();
            table1.setRow(i);
            Map<String, Object> item = items.get(i);

            table1.setValue("BANKS", item.get("banks"));
            table1.setValue("BANKL", item.get("bankl"));
            table1.setValue("BANKN", item.get("bankn"));
            table1.setValue("KOINH", item.get("koinh"));
            table1.setValue("BKREF", item.get("bkref"));
            table1.setValue("BANKA", item.get("banka"));
            table1.setValue("PROVZ", item.get("provz"));
            table1.setValue("STRAS", item.get("stras"));
            table1.setValue("ORT01", item.get("ort01"));
            table1.setValue("BRNCH", item.get("brnch"));
            table1.setValue("SWIFT", item.get("swift"));
        }

        JCoTable table2 = function.getTableParameterList().getTable("PT_BUKRS");
        List<Map<String, Object>> items1 = itemMap.get("PT_BUKRS");
        for (int i = 0; i < items1.size(); i++) {
            table2.appendRow();
            table2.setRow(i);
            Map<String, Object> item1 = items1.get(i);

            table2.setValue("BUKRS", item1.get("bukrs"));
            table2.setValue("ZTERM", item1.get("zterm"));
            table2.setValue("AKONT", item1.get("akont"));
            table2.setValue("FDGRV", item1.get("fdgrv"));
            table2.setValue("SPERR", item1.get("sperr"));
            table2.setValue("LOEVM", item1.get("loevm"));
        }

        JCoTable table3 = function.getTableParameterList().getTable("PT_EKORG");
        List<Map<String, Object>> items2 = itemMap.get("PT_EKORG");
        for (int i = 0; i < items2.size(); i++) {
            table3.appendRow();
            table3.setRow(i);
            Map<String, Object> item2 = items2.get(i);

            table3.setValue("EKORG", item2.get("ekorg"));
            table3.setValue("WAERS", item2.get("waers"));
            table3.setValue("ZTERM", item2.get("zterm"));
            table3.setValue("VERKF", item2.get("verkf"));
            table3.setValue("EKGRP", item2.get("ekgrp"));
            table3.setValue("KALSK", item2.get("kalsk"));
            table3.setValue("LEBRE", item2.get("lebre"));
            table3.setValue("SPERM", item2.get("sperm"));
        }

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

        Map<String, Object> result = new HashMap<String, Object>();
        JCoTable jCoTable = function.getTableParameterList().getTable("RE_RFC_RETURN");
        StringBuilder stringBuilder = new StringBuilder();
        for(int i = 0; i < jCoTable.getNumRows(); i++){
            jCoTable.setRow(i);
            for(JCoField fld : jCoTable){
                stringBuilder.append(String.format("%s\t", fld.getValue()));
            }
        }
        result.put("LOG", stringBuilder.toString().replaceAll("\r|\n|\t", "-"));
        for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext(); ) {
            result = traversalField(result, iterator);
        }
        return result;
    }
	
	public  Map<String, Object> Z_SRM_VENDER_MAINTAIN_CREATE_TO_MDM(String message, InterMsgData interMsgData) throws EsbException {
		Log.getInstance().stdDebug("==================Z_SRM_VENDER_MAINTAIN_CREATE==TO MDM==============");
		Map<String, Object> result = new HashMap<String, Object>();

		String opration = "调用MDM接口";
		sendReqLog(interMsgData, "ESB", "MDM", message, opration);
		long startTime = System.currentTimeMillis();
		
		RestTemplate restTemplate = new RestTemplate();
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		HttpEntity<String> requestEntity = new HttpEntity<String>(message, headers);

		List<HttpMessageConverter<?>> httpMessageConverters = restTemplate.getMessageConverters();
		httpMessageConverters.stream().forEach(httpMessageConverter -> {
			if (httpMessageConverter instanceof StringHttpMessageConverter) {
				StringHttpMessageConverter messageConverter = (StringHttpMessageConverter) httpMessageConverter;
				messageConverter.DEFAULT_CHARSET.forName("UTF-8");
			}
		});
		
//		String mdmRestUrl = (String) cm.get("MDM_VENDER_MAINTAIN_CREATET_URL");
		String redisPre = ConstantUtil.REDIS_PRE_DICT + ":" + ConstantUtil.REDIS_PRE_DICT_TYPE_URL_MDM + ":";
		String mdmRestUrl = getRedisValue(redisPre + "MDM_VENDER_MAINTAIN_CREATET_URL");
		
		Log.getInstance().stdDebug("MDM_VENDER_MAINTAIN_CREATET_URL=" + mdmRestUrl);
		String resJson = restTemplate.postForObject(mdmRestUrl, requestEntity, String.class);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "MDM", "ESB", time, resJson, "收到MDM接口返回信息");

		Map resMap = JSON.parseObject(resJson, Map.class);
		//Map returnDataMap=(Map)((Map)resMap.get("RESPONSE")).get("RETURN_DATA");

		result.put("EX_MESSAGE", resMap.get("EX_MESSAGE"));
		result.put("EX_TYPE", resMap.get("EX_TYPE"));
		result.put("EX_VENDERNO", resMap.get("EX_VENDERNO"));
		result.put("LOG", resMap.get("LOG"));
		return result;
	}
	
	public Map<String, Object> Z_SRM_VENDER_MAINTAIN_CHANGE_SWITCH(String appId, String functionName, Map<String, String> head, Map<String, List<Map<String, Object>>> itemMap, String message, InterMsgData interMsgData) throws EsbException {
		Map<String, Object> result = new HashMap<String, Object>();

		String srmSwitchMdm = (String) cm.get("SRM_SWITCH_MDM");
		Log.getInstance().stdDebug("==================Z_SRM_VENDER_MAINTAIN_CHANGE_SWITCH===============srmSwitchMdm=" + srmSwitchMdm);

		// 1已切换
		if ("1".equals(srmSwitchMdm) && "SRM".equals(appId)) {
			result = Z_SRM_VENDER_MAINTAIN_CHANGE_TO_MDM(message, interMsgData);
		} else if ("0".equals(srmSwitchMdm) && "SRM".equals(appId)) {
			result = Z_SRM_VENDER_MAINTAIN_CHANGE_TO_SAP(head, itemMap, interMsgData);
		} else if ("TMS".equals(appId)) {
			result = Z_SRM_VENDER_MAINTAIN_CHANGE_TO_SAP(head, itemMap, interMsgData);
		}

		return result;
	}
	
	public  Map<String, Object> Z_SRM_VENDER_MAINTAIN_CHANGE_TO_SAP(Map<String, String> head, Map<String, List<Map<String, Object>>> itemMap, InterMsgData interMsgData) throws EsbException {
		Log.getInstance().stdDebug("==================Z_SRM_VENDER_MAINTAIN_CHANGE_MDM==TO SAP==============");
		
        JCoFunction function = RfcManager.getInstance().getFunction("Z_SRM_VENDER_MAINTAIN_CHANGE");
        
        JCoStructure st = (JCoStructure) function.getImportParameterList().getValue("IM_VENDER");
        st.setValue("LIFNR", head.get("lifnr"));
        st.setValue("KTOKK", head.get("ktokk"));
        st.setValue("BRSCH", head.get("brsch"));
        st.setValue("STCEG", head.get("stceg"));
        st.setValue("NAME1", head.get("name1"));
        st.setValue("NAME2", head.get("name2"));
        st.setValue("SORTL", head.get("sortl"));
        st.setValue("STRAS", head.get("stras"));
        st.setValue("ORT01", head.get("ort01"));
        st.setValue("PSTLZ", head.get("pstlz"));
        st.setValue("LAND1", head.get("land1"));
        st.setValue("REGIO", head.get("regio"));
        st.setValue("SPRAS", head.get("spras"));
        st.setValue("SPRAS_T002", head.get("spras_t002"));
        st.setValue("TELF1", head.get("telf1"));
        st.setValue("TELF2", head.get("telf2"));
        st.setValue("TELFX", head.get("telfx"));
        st.setValue("SPERR", head.get("sperr"));
        st.setValue("SPERM", head.get("sperm"));
        st.setValue("LOEVM", head.get("loevm"));

        if (null != itemMap) {
        	if (null != itemMap.get("PT_BANKS")) {
                JCoTable table1 = function.getTableParameterList().getTable("PT_BANKS");
                List<Map<String, Object>> items = itemMap.get("PT_BANKS");
                for (int i = 0; i < items.size(); i++) {
                    table1.appendRow();
                    table1.setRow(i);
                    Map<String, Object> item = items.get(i);

                    table1.setValue("BANKS", item.get("banks"));
                    table1.setValue("BANKL", item.get("bankl"));
                    table1.setValue("BANKN", item.get("bankn"));
                    table1.setValue("KOINH", item.get("koinh"));
                    table1.setValue("BKREF", item.get("bkref"));
                    table1.setValue("BANKA", item.get("banka"));
                    table1.setValue("PROVZ", item.get("provz"));
                    table1.setValue("STRAS", item.get("stras"));
                    table1.setValue("ORT01", item.get("ort01"));
                    table1.setValue("BRNCH", item.get("brnch"));
                    table1.setValue("SWIFT", item.get("swift"));
                }
            }

            if (null != itemMap.get("PT_BUKRS")) {
                JCoTable table2 = function.getTableParameterList().getTable("PT_BUKRS");
                List<Map<String, Object>> items1 = itemMap.get("PT_BUKRS");
                for (int i = 0; i < items1.size(); i++) {
                    table2.appendRow();
                    table2.setRow(i);
                    Map<String, Object> item1 = items1.get(i);

                    table2.setValue("BUKRS", item1.get("bukrs"));
                    table2.setValue("ZTERM", item1.get("zterm"));
                    table2.setValue("AKONT", item1.get("akont"));
                    table2.setValue("FDGRV", item1.get("fdgrv"));
                    table2.setValue("SPERR", item1.get("sperr"));
                    table2.setValue("LOEVM", item1.get("loevm"));
                }
            }

            if (null != itemMap.get("PT_EKORG")) {
                JCoTable table3 = function.getTableParameterList().getTable("PT_EKORG");
                List<Map<String, Object>> items2 = itemMap.get("PT_EKORG");
                for (int i = 0; i < items2.size(); i++) {
                    table3.appendRow();
                    table3.setRow(i);
                    Map<String, Object> item2 = items2.get(i);

                    table3.setValue("EKORG", item2.get("ekorg"));
                    table3.setValue("WAERS", item2.get("waers"));
                    table3.setValue("ZTERM", item2.get("zterm"));
                    table3.setValue("VERKF", item2.get("verkf"));
                    table3.setValue("EKGRP", item2.get("ekgrp"));
                    table3.setValue("KALSK", item2.get("kalsk"));
                    table3.setValue("LEBRE", item2.get("lebre"));
                    table3.setValue("SPERM", item2.get("sperm"));
                }
            }
        }

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

        Map<String, Object> result = new HashMap<String, Object>();
        JCoTable jCoTable = function.getTableParameterList().getTable("RE_RFC_RETURN");
        StringBuilder stringBuilder = new StringBuilder();
        for(int i = 0; i < jCoTable.getNumRows(); i++){
            jCoTable.setRow(i);
            for(JCoField fld : jCoTable){
                stringBuilder.append(String.format("%s\t", fld.getValue()));
            }
        }
        result.put("LOG", stringBuilder.toString().replaceAll("\r|\n|\t", "-"));
        for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext(); ) {
            result = traversalField(result, iterator);
        }
        return result;
    }
	
	public Map<String, Object> Z_SRM_VENDER_MAINTAIN_CHANGE_TO_MDM(String message, InterMsgData interMsgData) throws EsbException {
		Log.getInstance().stdDebug("==================Z_SRM_VENDER_MAINTAIN_CHANGE==TO MDM==============");
		Map<String, Object> result = new HashMap<String, Object>();

		String opration = "调用MDM接口";
		sendReqLog(interMsgData, "ESB", "MDM", message, opration);
		long startTime = System.currentTimeMillis();

		RestTemplate restTemplate = new RestTemplate();
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		HttpEntity<String> requestEntity = new HttpEntity<String>(message, headers);

		List<HttpMessageConverter<?>> httpMessageConverters = restTemplate.getMessageConverters();
		httpMessageConverters.stream().forEach(httpMessageConverter -> {
			if (httpMessageConverter instanceof StringHttpMessageConverter) {
				StringHttpMessageConverter messageConverter = (StringHttpMessageConverter) httpMessageConverter;
				messageConverter.DEFAULT_CHARSET.forName("UTF-8");
			}
		});
		
//		String mdmRestUrl = (String) cm.get("MDM_VENDER_MAINTAIN_UPDATE_URL");
		String redisPre = ConstantUtil.REDIS_PRE_DICT + ":" + ConstantUtil.REDIS_PRE_DICT_TYPE_URL_MDM + ":";
		String mdmRestUrl = getRedisValue(redisPre + "MDM_VENDER_MAINTAIN_UPDATE_URL");
		Log.getInstance().stdDebug("MDM_VENDER_MAINTAIN_UPDATE_URL=" + mdmRestUrl);
		String resJson = restTemplate.postForObject(mdmRestUrl, requestEntity, String.class);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "MDM", "ESB", time, resJson, "收到MDM接口返回信息");

		Map resMap = JSON.parseObject(resJson, Map.class);
		//Map returnDataMap = (Map) ((Map) resMap.get("RESPONSE")).get("RETURN_DATA");

		result.put("EX_MESSAGE", resMap.get("EX_MESSAGE"));
		result.put("EX_TYPE", resMap.get("EX_TYPE"));
		result.put("EX_VENDERNO", resMap.get("EX_VENDERNO"));
		result.put("LOG", resMap.get("LOG"));
		return result;
	}

	public Map<String, Object> Z_SRM_VMISTORE_INPUT(List<Map<String, Object>> heads, List<Map<String, Object>> items, InterMsgData interMsgData) throws EsbException {
		
		Log.getInstance().stdDebug("==================Z_SRM_VMISTORE_INPUT================");
		JCoFunction function =  RfcManager.getInstance().getFunction("Z_SRM_VMISTORE_INPUT");

        JCoTable table = function.getTableParameterList().getTable("PT_VMI");
		for (int i = 0; i < items.size(); i++) {
			table.appendRow();
			table.setRow(i);
			Map<String, Object> orderitem = items.get(i);

			table.setValue("ID", orderitem.get("id"));
			table.setValue("LIFNR", orderitem.get("lifnr"));
			table.setValue("VERSIONNUMBER", orderitem.get("versionnumber"));
			table.setValue("MATNR", orderitem.get("matnr"));
			table.setValue("FBK14", orderitem.get("fbk14"));
			table.setValue("FBK15", orderitem.get("fbk15"));
			table.setValue("EBLEN", orderitem.get("eblen"));
			table.setValue("EBELP", orderitem.get("ebelp"));
			table.setValue("BASICUNIT", orderitem.get("basicunit"));
			table.setValue("QUANTITY", orderitem.get("quantity"));
			table.setValue("UNDELIVEREDQUANTITY", orderitem.get("undeliveredquantity"));
			table.setValue("CUMULATIVEQUANTITY", orderitem.get("cumulativequantity"));
			table.setValue("WEEKREADYGOODSQUANTITY", orderitem.get("weekreadygoodsquantity"));
			table.setValue("SUPREMARK", orderitem.get("supremark"));
			table.setValue("FBK3", orderitem.get("fbk3"));
			table.setValue("FBK4", orderitem.get("fbk4"));
			table.setValue("BANFN", orderitem.get("banfn"));
			table.setValue("FBK13", orderitem.get("fbk13"));
			table.setValue("FBK16", orderitem.get("fbk16"));
			table.setValue("BSTAE", orderitem.get("bstae"));
			table.setValue("FBK18", orderitem.get("fbk18"));
			table.setValue("FBK26", orderitem.get("fbk26"));
			table.setValue("FBK27", orderitem.get("fbk27"));
			table.setValue("FBK28", orderitem.get("fbk28"));
			table.setValue("FBK29", orderitem.get("fbk29"));
			table.setValue("FBK30", orderitem.get("fbk30"));
			table.setValue("FBK31", orderitem.get("fbk31"));
			table.setValue("FBK32", orderitem.get("fbk32"));
			table.setValue("FBK33", orderitem.get("fbk33"));
			table.setValue("FBK35", orderitem.get("fbk35"));
			table.setValue("THISWEEKFIRSTDAY", orderitem.get("thisWeekFirstDay"));
			table.setValue("THISWEEKSECONDDAY", orderitem.get("thisWeekSecondDay"));
			table.setValue("THISWEEKTHIRDDAY", orderitem.get("thisWeekThirdDay"));
			table.setValue("THISWEEKFORTHDAY", orderitem.get("thisWeekForthDay"));
			table.setValue("THISWEEKFIFTHDAY", orderitem.get("thisWeekFifthDay"));
			table.setValue("THISWEEKSIXDAY", orderitem.get("thisWeekSixDay"));
			table.setValue("THISWEEKSEVENDAY", orderitem.get("thisWeekSevenDay"));
			table.setValue("NEXTWEEKFIRSTDAY", orderitem.get("nextWeekFirstDay"));
			table.setValue("NEXTWEEKSECONDDAY", orderitem.get("nextWeekSecondDay"));
			table.setValue("NEXTWEEKTHIRDDAY", orderitem.get("nextWeekThirdDay"));
			table.setValue("NEXTWEEKFORTHDAY", orderitem.get("nextWeekForthDay"));
			table.setValue("NEXTWEEKFIFTHDAY", orderitem.get("nextWeekFifthDay"));
			table.setValue("NEXTWEEKSIXDAY", orderitem.get("nextWeekSixDay"));
			table.setValue("NEXTWEEKSEVENDAY", orderitem.get("nextWeekSevenDay"));

		}

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

        Map<String, Object> result = new HashMap<String, Object>();
        for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext(); ) {
            result = traversalField(result, iterator);
        }
        return result;
    }
	
	public Map<String, Object> Z_SRM_PRSTATUS_UPDATE(List<Map<String, String>> heads, InterMsgData interMsgData) throws EsbException {
		
		Log.getInstance().stdDebug("==================Z_SRM_PRSTATUS_UPDATE================");
		JCoFunction function =  RfcManager.getInstance().getFunction("Z_SRM_PRSTATUS_UPDATE");

        JCoTable table1 = function.getTableParameterList().getTable("PT_PR");
        for (int i = 0; i < heads.size(); i++) {
            table1.appendRow();
            table1.setRow(i);
            Map<String, String> head = heads.get(i);

            table1.setValue("BANFN", head.get("banfn"));
            table1.setValue("BNFPO", head.get("bnfpo"));
            table1.setValue("ZTATUS", head.get("statusPr"));
        }

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

        Map<String, Object> result = new HashMap<String, Object>();
        for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext(); ) {
            result = traversalField(result, iterator);
        }
        return result;
    }
	
	public Map<String, Object> Z_SRM_MODIFY_PO_BSTAE(List<Map<String, String>> heads, InterMsgData interMsgData) throws EsbException {
		
		Log.getInstance().stdDebug("==================Z_SRM_MODIFY_PO_BSTAE================");
		JCoFunction function =  RfcManager.getInstance().getFunction("Z_SRM_MODIFY_PO_BSTAE");

		JCoTable table = function.getTableParameterList().getTable("PT_BSTAE");
		for (int i = 0; i < heads.size(); i++) {
            table.appendRow();
            table.setRow(i);
            Map<String, String> head = heads.get(i);

            table.setValue("EBELN", head.get("ebeln"));
            table.setValue("EBELP", head.get("ebelp"));
            table.setValue("BSTAE", head.get("bstae"));
        }

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

        Map<String, Object> result = new HashMap<String, Object>();
        for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext(); ) {
            result = traversalField(result, iterator);
        }
        return result;
    }
	
	public Map<String, Object> Z_SRM_CREATE_PO(Map<String, String> head, Map<String, List<Map<String, String>>> itemMap, InterMsgData interMsgData) throws EsbException {
		
		Log.getInstance().stdDebug("==================Z_SRM_CREATE_PO================");
		JCoFunction function =  RfcManager.getInstance().getFunction("Z_SRM_CREATE_PO");

		JCoTable ptEkko = function.getTableParameterList().getTable("PT_EKKO");
        ptEkko.appendRow();
        ptEkko.setValue("BSART", head.get("bsart"));
        ptEkko.setValue("ZDCHTH", head.get("zdchth"));
        ptEkko.setValue("EBELN", head.get("ebeln"));
        ptEkko.setValue("BUKRS", head.get("bukrs"));
        ptEkko.setValue("ZTERM", head.get("zterm"));
        ptEkko.setValue("EKORG", head.get("ekorg"));
        ptEkko.setValue("EKGRP", head.get("ekgrp"));
        ptEkko.setValue("WAERS", head.get("waers"));
        ptEkko.setValue("INCO1", head.get("inco1"));
        ptEkko.setValue("INCO2", head.get("inco2"));
        ptEkko.setValue("ZPAYER", head.get("zpayer"));
        ptEkko.setValue("LIFNR", head.get("lifnr"));
		if (StringUtils.isNotEmpty(head.get("zcgfzr"))) {
			ptEkko.setValue("ZCGFZR", head.get("zcgfzr"));
		}

        JCoTable ptEkpo = function.getTableParameterList().getTable("PT_EKPO");
        List<Map<String, String>> ptEkpoItems = itemMap.get("PT_EKPO");
        for (int i = 0; i < ptEkpoItems.size(); i++) {
            Map<String, String> item = ptEkpoItems.get(i);
            ptEkpo.appendRow();
            ptEkpo.setRow(i);
            ptEkpo.setValue("EBELN", item.get("ebeln"));
            ptEkpo.setValue("EBELP", item.get("ebelp"));
            ptEkpo.setValue("PSTYP", item.get("pstyp"));
            ptEkpo.setValue("KNTTP", item.get("knttp"));
            ptEkpo.setValue("LOEKZ", item.get("loekz"));
            ptEkpo.setValue("TXZ01", item.get("txz01"));
            ptEkpo.setValue("MATNR", item.get("matnr"));
            ptEkpo.setValue("WERKS", item.get("werks"));
            ptEkpo.setValue("BANFN", item.get("banfn"));
            ptEkpo.setValue("BNFPO", item.get("bnfpo"));
            ptEkpo.setValue("MATKL", item.get("matkl"));
            ptEkpo.setValue("MENGE", item.get("menge"));
            ptEkpo.setValue("MEINS", item.get("meins"));
            ptEkpo.setValue("BPRME", item.get("bprme"));
            ptEkpo.setValue("NETPR", item.get("netpr"));
            ptEkpo.setValue("PEINH", item.get("peinh"));
            ptEkpo.setValue("NETWR", item.get("netwr"));
            ptEkpo.setValue("ZZEXTWG", item.get("zzextwg"));
            ptEkpo.setValue("EINDT", item.get("eindt"));
            ptEkpo.setValue("BSTAE", item.get("bstae"));
            ptEkpo.setValue("MWSKZ", item.get("mwskz"));
            ptEkpo.setValue("BEDNR", item.get("bednr"));
            ptEkpo.setValue("AFNAM", item.get("afnam"));
            ptEkpo.setValue("ELIKZ", item.get("elikz"));
            ptEkpo.setValue("INCO1", item.get("inco1"));
            ptEkpo.setValue("INCO2", item.get("inco2"));
            ptEkpo.setValue("ZJHZL", item.get("zjhzl"));
            ptEkpo.setValue("ZJHZLDW", item.get("zjhzldw"));
            ptEkpo.setValue("ZHSL", item.get("zhsl"));

			ptEkpo.setValue("AFNAM", item.get("afnam"));
			ptEkpo.setValue("KOSTL", item.get("kostl"));
			ptEkpo.setValue("ZNBDD", item.get("znbdd"));

			ptEkpo.setValue("EMLIF", item.get("emlif"));
			ptEkpo.setValue("LBLKZ", item.get("lblkz"));
			ptEkpo.setValue("DEMAND_NO", item.get("demandNo"));
			ptEkpo.setValue("DEMAND_POSNR", item.get("demandPosnr"));
			ptEkpo.setValue("DEMANDER", item.get("demander"));
        }

        JCoTable ptKonv = function.getTableParameterList().getTable("PT_KONV");
        List<Map<String, String>> ptKonvItems = itemMap.get("PT_KONV");
        for (int i = 0; i < ptKonvItems.size(); i++) {
            Map<String, String> item = ptKonvItems.get(i);
            ptKonv.appendRow();
            ptKonv.setRow(i);
            ptKonv.setValue("EBELN", item.get("ebeln"));
            ptKonv.setValue("EBELP", item.get("ebelp"));

			ptKonv.setValue("KMEIN", item.get("kmein"));
			ptKonv.setValue("KPEIN", item.get("kpein"));

            ptKonv.setValue("LIFNR", item.get("lifnr"));
            ptKonv.setValue("KSCHL", item.get("kschl"));
            ptKonv.setValue("KBETR", item.get("kbetr"));
            ptKonv.setValue("KOEIN", item.get("koein"));
        }
        
        JCoTable ptEket = function.getTableParameterList().getTable("PT_EKET");
        List<Map<String, String>> ptEketItems = itemMap.get("PT_EKET");
        for (int i = 0; i < ptEketItems.size(); i++) {
            Map<String, String> item = ptEketItems.get(i);
            ptEket.appendRow();
            ptEket.setRow(i);
            ptEket.setValue("EBELN", item.get("ebeln"));
            ptEket.setValue("EBELP", item.get("ebelp"));
            ptEket.setValue("MENGE", item.get("menge"));
            ptEket.setValue("BANFN", item.get("banfn"));
            ptEket.setValue("BNFPO", item.get("bnfpo"));
        }

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

        Map<String, Object> result = new HashMap<String, Object>();

		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator, "Z_SRM_CREATE_PO");
		}

		String[] outTblArr = { "RE_RETURN" };
		List<String> outTblList = Arrays.asList(outTblArr);
		for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator, outTblList);
		}

        return result;
    }
	
	public Map<String, Object> Z_SRM_FW_ML81N(Map<String, String> head, List<Map<String, String>> items, InterMsgData interMsgData) throws EsbException {
		
		Log.getInstance().stdDebug("==================Z_SRM_FW_ML81N================");
		JCoFunction function =  RfcManager.getInstance().getFunction("Z_SRM_FW_ML81N");

        JCoStructure st = (JCoStructure) function.getImportParameterList().getValue("IM_ESSR");
        st.setValue("EBELN", head.get("ebeln"));
        st.setValue("EBELP", head.get("ebelp"));
        st.setValue("BKTXT", head.get("bktxt"));
        st.setValue("BLDAT", head.get("bldat"));
        st.setValue("BUDAT", head.get("budat"));
        st.setValue("XBLNR", head.get("xblnr"));
        st.setValue("LBLNE", head.get("lblne"));
        st.setValue("SBNAMAG", head.get("sbnamag"));
        st.setValue("SBNAMAN", head.get("sbnaman"));
        st.setValue("LZVON", head.get("lzvon"));
        st.setValue("LZBIS", head.get("lzbis"));

        JCoTable table = function.getTableParameterList().getTable("PT_ESLL");
        for (int i = 0; i < items.size(); i++) {
            table.appendRow();
            table.setRow(i);
//            table.setValue("LBLNI", items.get(i).get("lblni"));
            table.setValue("EXTROW", items.get(i).get("extrow"));
            table.setValue("KTEXT1", items.get(i).get("ktext1"));
            table.setValue("MENGE", items.get(i).get("menge"));
            table.setValue("MEINS", items.get(i).get("meins"));
            table.setValue("TBTWR", items.get(i).get("tbtwr"));
            table.setValue("MWSKZ", items.get(i).get("mwskz"));
            table.setValue("SAKTO", items.get(i).get("sakto"));
            table.setValue("KOSTL", items.get(i).get("kostl"));
            table.setValue("PRCTR", items.get(i).get("prctr"));
        }

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

        Map<String, Object> result = new HashMap<String, Object>();
        JCoTable jCoTable = function.getTableParameterList().getTable("PT_RETURN");
        StringBuilder stringBuilder = new StringBuilder();
        for(int i = 0; i < jCoTable.getNumRows(); i++){
            jCoTable.setRow(i);
            for(JCoField fld : jCoTable){
                stringBuilder.append(String.format("%s\t", fld.getValue()));
            }
        }
        result.put("LOG", stringBuilder.toString().replaceAll("\r|\n|\t", "-"));
        for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext(); ) {
            result = traversalField(result, iterator);
            result.put("EX_XBLNR", result.get("EX_XBLNR"));
            result.put("EX_LBLNI", result.get("EX_LBLNI"));
        }
        return result;
    }
	
	
	public Map<String, Object> Z_SRM_PAYMENT_F_47(Map<String, String> head, List<Map<String, String>> items, InterMsgData interMsgData) throws EsbException {

		Log.getInstance().stdDebug("==================Z_SRM_PAYMENT_F_47================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_SRM_PAYMENT_F_47");

		JCoStructure st = (JCoStructure) function.getImportParameterList().getValue("IM_HEAD");
		st.setValue("BLDAT", head.get("bldat"));
		st.setValue("BUDAT", head.get("budat"));
		st.setValue("BLART", head.get("blart"));
		st.setValue("BUKRS", head.get("bukrs"));
		st.setValue("WAERS", head.get("waers"));
		st.setValue("XBLNR", head.get("xblnr"));
		st.setValue("BKTXT", head.get("bktxt"));
		st.setValue("KTNRA", head.get("ktnra"));
		st.setValue("ZUMSK", head.get("zumsk"));
		st.setValue("FKFS", head.get("fkfs"));
		st.setValue("PAYCLTNO", head.get("paycltno"));
		st.setValue("IS_ORG_CURR", head.get("is_org_curr"));
		st.setValue("PAY_PUB", head.get("pay_pub"));
		st.setValue("RZAWE", head.get("rzawe"));
		st.setValue("ORG_PAY_DATE", head.get("org_pay_date"));
		st.setValue("EXCH_TYPE", head.get("exch_type"));
		st.setValue("RECE_ACC_CUR", head.get("rece_acc_cur"));
		st.setValue("BANKL", head.get("bankl"));
		st.setValue("BANKS", head.get("banks"));
		st.setValue("BANKN_ALL", head.get("bankn_all"));
		st.setValue("KOINH", head.get("koinh"));
		st.setValue("RECE_OPBANK_NAME", head.get("rece_opbank_name"));
		st.setValue("IS_PRO_PAYYER", head.get("is_pro_payyer"));
		st.setValue("GJ", head.get("gj"));
		st.setValue("XMMC", head.get("xmmc"));
		st.setValue("XMOA", head.get("xmoa"));
		st.setValue("HTH", head.get("hth"));
		st.setValue("SOURCE", head.get("source"));
		st.setValue("BUSINESSTYPE", head.get("businesstype"));
		st.setValue("DATE_P", head.get("date_p"));
		st.setValue("CREATOR", head.get("creator"));
		st.setValue("ERNAM", head.get("ernam"));
		st.setValue("MARK", head.get("mark"));

		JCoTable table = function.getTableParameterList().getTable("PT_ITEM");
		for (int i = 0; i < items.size(); i++) {
			table.appendRow();
			table.setRow(i);
			table.setValue("EBELN", items.get(i).get("ebeln"));
			table.setValue("EBELP", items.get(i).get("ebelp"));
			table.setValue("WRBTR", items.get(i).get("wrbtr"));
			table.setValue("ZFBDT", items.get(i).get("zfbdt"));
			table.setValue("ZUONR", items.get(i).get("zuonr"));
			table.setValue("SGTXT", items.get(i).get("sgtxt"));
			table.setValue("ORGDOCIT", items.get(i).get("orgdocit"));
		}

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, Object> result = new HashMap<String, Object>();

		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator, "Z_SRM_PAYMENT_F_47");
		}

		JCoTable jCoTable = function.getTableParameterList().getTable("PT_RETURN");
		List resultList = new ArrayList();
		Map retMap = new HashMap();
		for (int i = 0; i < jCoTable.getNumRows(); i++) {
			jCoTable.setRow(i);
			for (JCoField fld : jCoTable) {
				retMap.put(fld.getName(), fld.getValue());
			}
			resultList.add(retMap);
		}
		result.put("PT_RETURN", resultList);

		return result;
	}

	
	public Map<String, Object> Z_SRM_PAYMENT_F_47_RESVE(Map<String, String> head, InterMsgData interMsgData) throws EsbException {

		Log.getInstance().stdDebug("==================Z_SRM_PAYMENT_F_47_RESVE================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_SRM_PAYMENT_F_47_RESVE");

		JCoStructure st = (JCoStructure) function.getImportParameterList().getValue("IM_RESVE");
		st.setValue("BELNR", head.get("belnr"));
		st.setValue("BUKRS", head.get("bukrs"));
		st.setValue("GJAHR", head.get("gjahr"));
		st.setValue("STGRD", head.get("stgrd"));
		st.setValue("BUDAT", head.get("budat"));

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, Object> result = new HashMap<String, Object>();

		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator, "Z_SRM_PAYMENT_F_47");
		}

		return result;
	}
	
	public Map<String, Object> Z_SRM_PACKAGE_INFORMATION(Map<String, String> head, List<Map<String, String>> listXHead, List<Map<String, String>> items, InterMsgData interMsgData) throws EsbException {
		
		Log.getInstance().stdDebug("==================Z_SRM_PACKAGE_INFORMATION================");
		JCoFunction function =  RfcManager.getInstance().getFunction("Z_SRM_PACKAGE_INFORMATION");

        JCoTable ptHead = function.getTableParameterList().getTable("PT_HEAD");
        ptHead.appendRow();
        ptHead.setValue("MANDT", head.get("mandt"));
        ptHead.setValue("PACKAGE_NO", head.get("packageNo"));
        ptHead.setValue("SRM_DN", head.get("srmDn"));
        ptHead.setValue("SAP_DN", head.get("sapDn"));
        ptHead.setValue("LIFNR", head.get("lifnr"));
        ptHead.setValue("VOLUM_T", head.get("volumT"));
        ptHead.setValue("BRGEW_T", head.get("brgewT"));
        ptHead.setValue("ZXDATE_F", head.get("zxdateF"));
        ptHead.setValue("ZXDATE_T", head.get("zxdateT"));
        ptHead.setValue("YSR", head.get("ysr"));
        ptHead.setValue("CYRLXFS", head.get("cyrlxfs"));
        ptHead.setValue("SHR", head.get("shr"));
        ptHead.setValue("SHRLXFS", head.get("shrlxfs"));
        ptHead.setValue("SHDZ", head.get("shdz"));
        ptHead.setValue("SPMS_CONTRACT_NO", head.get("spmsContractNo"));
        ptHead.setValue("SPMS_FLG", head.get("spmsFlg"));
		if (head.get("purchaser") != null) {
			ptHead.setValue("PURCHASER", head.get("purchaser"));
		}
		if (head.get("boxcodeDel") != null) {

			ptHead.setValue("BOXCODE_DEL", head.get("boxcodeDel"));
		}

		if (null != listXHead) {
			JCoTable ptXHead = function.getTableParameterList().getTable("PT_XHEAD");
			for (int i = 0; i < listXHead.size(); i++) {
				ptXHead.appendRow();
				ptXHead.setRow(i);
				ptXHead.setValue("MANDT", listXHead.get(i).get("mandt"));
				ptXHead.setValue("PACKAGE_NO", listXHead.get(i).get("packageNo"));
				ptXHead.setValue("PACKAGE_CODE", listXHead.get(i).get("packageCode"));
				ptXHead.setValue("OUTER_BOX_TEXTURE", listXHead.get(i).get("outerBoxTextuer"));
				ptXHead.setValue("TOTAL_QTY", listXHead.get(i).get("totalQty"));
				ptXHead.setValue("BOX_BRGEW", listXHead.get(i).get("boxBrgew"));
				ptXHead.setValue("BOX_VOLUM", listXHead.get(i).get("boxVolum"));
				ptXHead.setValue("LENGTH", listXHead.get(i).get("length"));
				ptXHead.setValue("WIDTH", listXHead.get(i).get("width"));
				ptXHead.setValue("HEIGHT", listXHead.get(i).get("height"));
				ptXHead.setValue("COLOR", listXHead.get(i).get("color"));
			}
		}

        JCoTable ptItem = function.getTableParameterList().getTable("PT_ITEM");
        for (int i = 0; i < items.size(); i++) {
            ptItem.appendRow();
            ptItem.setRow(i);
            ptItem.setValue("MANDT", items.get(i).get("mandt"));
            ptItem.setValue("PACKAGE_NO", items.get(i).get("packageNo"));
            ptItem.setValue("SERIAL", items.get(i).get("serial"));
            ptItem.setValue("PACKAGE_CODE", items.get(i).get("packageCode"));
            ptItem.setValue("PACKAGE_POSNR", items.get(i).get("packagePosnr"));
            ptItem.setValue("MATNR", items.get(i).get("matnr"));
            ptItem.setValue("SPECS", items.get(i).get("specs"));
            ptItem.setValue("MENGE", items.get(i).get("menge"));
            ptItem.setValue("MEINS", items.get(i).get("meins"));
            ptItem.setValue("SRM_CONTRACT_NO", items.get(i).get("srmContractNo"));
            ptItem.setValue("EBELN", items.get(i).get("ebeln"));
            ptItem.setValue("EBELP", items.get(i).get("ebelp"));
            ptItem.setValue("VBELN", items.get(i).get("vbeln"));
            ptItem.setValue("POSNR", items.get(i).get("posnr"));
            ptItem.setValue("DEMAND_NO", items.get(i).get("demandNo"));
            ptItem.setValue("DEMAND_POSNR", items.get(i).get("demandPosnr"));
            ptItem.setValue("DEMANDER", items.get(i).get("demander"));
            ptItem.setValue("MARK", items.get(i).get("mark"));
            ptItem.setValue("BRGEW", items.get(i).get("brgew"));
            ptItem.setValue("NTGEW", items.get(i).get("ntgew"));
            ptItem.setValue("GNSHSL", items.get(i).get("gnshsl"));
            ptItem.setValue("GNSJSL", items.get(i).get("gnsjsl"));
            ptItem.setValue("HWSHSL", items.get(i).get("hwshsl"));
            if (items.get(i).get("boxcodeDel") != null) {
            	ptItem.setValue("BOXCODE_DEL", items.get(i).get("boxcodeDel"));
    		}
        }

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

        Map<String, Object> result = new HashMap<String, Object>();
        for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext(); ) {
            result = traversalField(result, iterator);
        }
        return result;
    }
	
	public Map<String, Object> Z_SRM_YANSHOUHEGE_INPUT(List<Map<String, String>> items, InterMsgData interMsgData) throws EsbException {

		Log.getInstance().stdDebug("==================Z_SRM_YANSHOUHEGE_INPUT================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_SRM_YANSHOUHEGE_INPUT");

		JCoTable table = function.getTableParameterList().getTable("PT_YANSHOUHEGE");
		for (int i = 0; i < items.size(); i++) {
			table.appendRow();
			table.setRow(i);
			table.setValue("LLJYDH", items.get(i).get("lljydh"));
			table.setValue("LLJYDMC", items.get(i).get("lljydmc"));
			table.setValue("LLJYDZT", items.get(i).get("lljydzt"));
			table.setValue("ZDATE", items.get(i).get("zdate"));
			table.setValue("ZUSER", items.get(i).get("zuser"));
			table.setValue("LIFNR", items.get(i).get("lifnr"));
			table.setValue("MATNR", items.get(i).get("matnr"));
			table.setValue("FBK17", items.get(i).get("fbk17"));
			table.setValue("FBK6", items.get(i).get("fbk6"));
			table.setValue("FBK23", items.get(i).get("fbk23"));
			table.setValue("FBK28", items.get(i).get("fbk28"));
		}

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, Object> result = new HashMap<String, Object>();

		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator, "Z_SRM_YANSHOUHEGE_INPUT");
		}

		return result;
	}
	
	public Map<String, Object> Z_SRM_FBE1_CREATE(Map<String, String> head, List<Map<String, String>> items, InterMsgData interMsgData) throws EsbException {
		
		Log.getInstance().stdDebug("==================Z_SRM_FBE1_CREATE================");
		JCoFunction function =  RfcManager.getInstance().getFunction("Z_SRM_FBE1_CREATE");

        JCoTable ptHead = function.getTableParameterList().getTable("PT_FBE_HEAD");
        ptHead.appendRow();
        ptHead.setValue("BUKRS", head.get("bukrs"));
        ptHead.setValue("KOART", head.get("koart"));
        ptHead.setValue("KONTO", head.get("konto"));
        ptHead.setValue("RWBTR", head.get("rwbtr"));
        ptHead.setValue("WAERS", head.get("waers"));
        ptHead.setValue("ZALDT", head.get("zaldt"));
        ptHead.setValue("AVSRT", head.get("avsrt"));
        ptHead.setValue("AVTXT", head.get("avtxt"));
        ptHead.setValue("FKFS", head.get("fkfs"));
        ptHead.setValue("KTNRA", head.get("ktnra"));
        ptHead.setValue("RECE_ACC_CUR", head.get("rece_acc_cur"));
        ptHead.setValue("BANKL", head.get("bankl"));
        ptHead.setValue("BANKS", head.get("banks"));
        ptHead.setValue("KOINH", head.get("koinh"));
        ptHead.setValue("BANKA", head.get("banka"));
        ptHead.setValue("BANKN_ALL", head.get("banka_all"));
        ptHead.setValue("DATE_P", head.get("date_p"));
        ptHead.setValue("PRINTER", head.get("printer"));
        ptHead.setValue("FKFS_D", head.get("fkfs_d"));
        ptHead.setValue("IS_PRO_PAYYER", head.get("is_pro_payyer"));
        ptHead.setValue("SOURCE", head.get("source"));
        ptHead.setValue("ZEXPLAIN", head.get("zexplanin"));
        ptHead.setValue("PAYCLTNO", head.get("paycltno"));
        ptHead.setValue("IS_ORG_CURR", head.get("is_org_curr"));
        ptHead.setValue("PAY_PUB", head.get("pay_pub"));
        ptHead.setValue("ORG_PAY_DATE", head.get("org_pay_date"));
        ptHead.setValue("EXCH_TYPE", head.get("exch_type"));
        ptHead.setValue("ERNAM", head.get("ernam"));
        ptHead.setValue("MARK", head.get("mark"));
        ptHead.setValue("ORGDOC", head.get("orgdoc"));

        JCoTable ptItem = function.getTableParameterList().getTable("PT_FBE_DETAIL");
        for (int i = 0; i < items.size(); i++) {
            ptItem.appendRow();
            ptItem.setRow(i);
            ptItem.setValue("BELNR", items.get(i).get("belnr"));
            ptItem.setValue("GJAHR", items.get(i).get("gjahr"));
            ptItem.setValue("BUZEI", items.get(i).get("buzei"));
            ptItem.setValue("BLART", items.get(i).get("blart"));
            ptItem.setValue("NEBTR", items.get(i).get("nebtr"));
            ptItem.setValue("KONTO", items.get(i).get("konto"));
            ptItem.setValue("BUKRS", items.get(i).get("bukrs"));
            ptItem.setValue("KOART", items.get(i).get("koart"));
            ptItem.setValue("SGTXT", items.get(i).get("sgtxt"));
            ptItem.setValue("KIDNO", items.get(i).get("kidno"));
            ptItem.setValue("GJ", items.get(i).get("gj"));
            ptItem.setValue("XMMC", items.get(i).get("xmmc"));
            ptItem.setValue("XMOA", items.get(i).get("xmoa"));
            ptItem.setValue("HTH", items.get(i).get("hth"));
            ptItem.setValue("ORGDOCIT", items.get(i).get("orgdocit"));
        }

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

        Map<String, Object> result = new HashMap<String, Object>();
        for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext(); ) {
            result = traversalField(result, iterator);
        }
        return result;
    }
	
	public Map<String, Object> Z_SRM_FBE6_CANCEL(Map<String, String> head, InterMsgData interMsgData) throws EsbException {

		Log.getInstance().stdDebug("==================Z_SRM_FBE6_CANCEL================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_SRM_FBE6_CANCEL");
		JCoParameterList jcoParameterList = function.getImportParameterList();

		jcoParameterList.setValue("IM_KOART", head.get("imKoart"));
		jcoParameterList.setValue("IM_BUKRS", head.get("imBukrs"));
		jcoParameterList.setValue("IM_KONTO", head.get("imKonto"));
		jcoParameterList.setValue("IM_AVSID", head.get("imAvsid"));

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, Object> result = new HashMap<String, Object>();
		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
			result = traversalField(result, iterator);
		}
		return result;
	}
	
	public Map<String, Object> Z_ZXD_IMPROT_AND_CONFIRM(Map<String, String> head, Map<String, List<Map<String, String>>> itemMap, InterMsgData interMsgData) throws EsbException {
		
		Log.getInstance().stdDebug("==================Z_ZXD_IMPROT_AND_CONFIRM================");
		JCoFunction function =  RfcManager.getInstance().getFunction("Z_ZXD_IMPROT_AND_CONFIRM");

        function.getImportParameterList().setValue("IM_IMPORT_FLAG", head.get("imImportFlag"));

        JCoTable ptInputList = function.getTableParameterList().getTable("PT_INPUT_LIST");
        List<Map<String, String>> items = itemMap.get("PT_INPUT_LIST");
        for (int i = 0; i < items.size(); i++) {
            ptInputList.appendRow();
            ptInputList.setRow(i);
            Map<String, String> item = items.get(i);

            ptInputList.setValue("ZXDID_EX", item.get("zxdidEx"));
            ptInputList.setValue("ZSPMS_TASKID", item.get("zspmsTaskid"));
            ptInputList.setValue("ZXDID", item.get("zxdid"));
            ptInputList.setValue("XDNUM", item.get("xdnum"));
            ptInputList.setValue("CABNO", item.get("cabno"));
            ptInputList.setValue("AZDAT", item.get("azdat"));
            ptInputList.setValue("ALIFNR", item.get("alifnr"));
            ptInputList.setValue("ASORTL", item.get("asortl"));
            ptInputList.setValue("ASORTL_SAP", item.get("asortlSap"));
            ptInputList.setValue("ALGORT", item.get("algort"));
            ptInputList.setValue("ALGOBE", item.get("algobe"));
            ptInputList.setValue("ALGOBE_SAP", item.get("algobeSap"));
            ptInputList.setValue("SEALN", item.get("sealn"));
            ptInputList.setValue("MATNR", item.get("matnr"));
            ptInputList.setValue("MAKTX", item.get("maktx"));
            ptInputList.setValue("MAKTX_SAP", item.get("maktxSap"));
            ptInputList.setValue("VRKME", item.get("vrkme"));
            ptInputList.setValue("AEMNG", item.get("aemng"));
            ptInputList.setValue("MEINS", item.get("meins"));
            ptInputList.setValue("AMENG", item.get("ameng"));
            ptInputList.setValue("CD_QTY", item.get("cdQty"));
            ptInputList.setValue("ZBAGUNIT", item.get("zbagunit"));
            ptInputList.setValue("BZGG", item.get("bzgg"));
            ptInputList.setValue("JYJG", item.get("jyjg"));
            ptInputList.setValue("KG_BRGEW", item.get("kgBrgew"));
            ptInputList.setValue("WX_BRAND", item.get("wxBrand"));
            ptInputList.setValue("COLOR_CODE", item.get("colorCode"));
            ptInputList.setValue("ZH", item.get("zh"));
            ptInputList.setValue("CPSJPJ_BRGEW", item.get("cpsjpjBrgew"));
            ptInputList.setValue("XD_AVG_BRGEW", item.get("xdAvgBrgew"));
            ptInputList.setValue("CHA_BRGEW", item.get("chaBrgew"));
            ptInputList.setValue("JZRY", item.get("jzry"));
            ptInputList.setValue("ZGFS", item.get("zgfs"));
            ptInputList.setValue("MARK", item.get("mark"));
            ptInputList.setValue("BOX_CODE", item.get("boxCode"));
            ptInputList.setValue("SERIAL", item.get("serial"));
            ptInputList.setValue("PKMAK_YN", item.get("pkmakYn"));
            ptInputList.setValue("DEL", item.get("del"));
            ptInputList.setValue("IMPORT_FLAG", item.get("importFlag"));
            ptInputList.setValue("BRGEW", item.get("brgew"));
            ptInputList.setValue("NTGEW", item.get("ntgew"));
            ptInputList.setValue("GEWEI", item.get("gewei"));
            ptInputList.setValue("VOLUM", item.get("volum"));
            ptInputList.setValue("VOLEH", item.get("voleh"));
            ptInputList.setValue("PBRGEW", item.get("pbrgew"));
            ptInputList.setValue("PNTGEW", item.get("pntgew"));
            ptInputList.setValue("PVOLUM", item.get("pvolum"));
        }

        List<Map<String, String>> items1 = itemMap.get("PT_STATE");
        if (null != items1) {
            JCoTable ptState = function.getTableParameterList().getTable("PT_STATE");
            for (int i = 0; i < items1.size(); i++) {
                ptState.appendRow();
                ptState.setRow(i);
                Map<String, String> item1 = items1.get(i);

                ptState.setValue("XDNUM", item1.get("xdnum"));
                ptState.setValue("XZHJ", item1.get("xzhj"));
                ptState.setValue("CURTU", item1.get("curtu"));
            }
        }

		List<Map<String, String>> itemsConfirm = itemMap.get("PT_CONFIRM");
		if (null != itemsConfirm) {
			JCoTable ptConfirm = function.getTableParameterList().getTable("PT_CONFIRM");
			for (int i = 0; i < itemsConfirm.size(); i++) {
				ptConfirm.appendRow();
				ptConfirm.setRow(i);
				Map<String, String> item1 = itemsConfirm.get(i);

				ptConfirm.setValue("PACKAGE_CODE", item1.get("packageCode"));
				ptConfirm.setValue("XDNUM", item1.get("xdnum"));
			}
		}

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

        Map<String, Object> result = new HashMap<String, Object>();
        JCoTable jCoTable = function.getTableParameterList().getTable("PT_OUTPUT_LIST");
        StringBuilder stringBuilder = new StringBuilder();
        for(int i = 0; i < jCoTable.getNumRows(); i++){
            jCoTable.setRow(i);
            for(JCoField fld : jCoTable){
                stringBuilder.append(String.format("%s\t", fld.getValue()));
            }
        }
        result.put("LOG", stringBuilder.toString().replaceAll("\r|\n|\t", "-"));
        for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext(); ) {
            result = traversalField(result, iterator);
        }
        return result;
    }
	
	public Map<String, Object> Z_SRM_MODIFY_PO(Map<String, String> head, Map<String, List<Map<String, String>>> itemMap, InterMsgData interMsgData) throws EsbException {
		
		Log.getInstance().stdDebug("==================Z_SRM_MODIFY_PO================");
		JCoFunction function =  RfcManager.getInstance().getFunction("Z_SRM_MODIFY_PO");

		JCoTable ptEkko = function.getTableParameterList().getTable("PT_EKKO");
        ptEkko.appendRow();
        ptEkko.setValue("BSART", head.get("bsart"));
        ptEkko.setValue("ZDCHTH", head.get("zdchth"));
        ptEkko.setValue("EBELN", head.get("ebeln"));
        ptEkko.setValue("BUKRS", head.get("bukrs"));
        ptEkko.setValue("ZTERM", head.get("zterm"));
        ptEkko.setValue("EKORG", head.get("ekorg"));
        ptEkko.setValue("EKGRP", head.get("ekgrp"));
        ptEkko.setValue("WAERS", head.get("waers"));
        ptEkko.setValue("INCO1", head.get("inco1"));
        ptEkko.setValue("INCO2", head.get("inco2"));
        ptEkko.setValue("ZPAYER", head.get("zpayer"));
        ptEkko.setValue("LIFNR", head.get("lifnr"));
		if (StringUtils.isNotEmpty(head.get("zcgfzr"))) {
			ptEkko.setValue("ZCGFZR", head.get("zcgfzr"));
		}

        JCoTable ptEkpo = function.getTableParameterList().getTable("PT_EKPO");
        List<Map<String, String>> ptEkpoItems = itemMap.get("PT_EKPO");
        for (int i = 0; i < ptEkpoItems.size(); i++) {
            Map<String, String> item = ptEkpoItems.get(i);
            ptEkpo.appendRow();
            ptEkpo.setRow(i);
            ptEkpo.setValue("EBELN", item.get("ebeln"));
            ptEkpo.setValue("EBELP", item.get("ebelp"));
            ptEkpo.setValue("PSTYP", item.get("pstyp"));
            ptEkpo.setValue("KNTTP", item.get("knttp"));
            ptEkpo.setValue("LOEKZ", item.get("loekz"));
            ptEkpo.setValue("TXZ01", item.get("txz01"));
            ptEkpo.setValue("MATNR", item.get("matnr"));
            ptEkpo.setValue("WERKS", item.get("werks"));
            ptEkpo.setValue("BANFN", item.get("banfn"));
            ptEkpo.setValue("BNFPO", item.get("bnfpo"));
            ptEkpo.setValue("MATKL", item.get("matkl"));
            ptEkpo.setValue("MENGE", item.get("menge"));
            ptEkpo.setValue("MEINS", item.get("meins"));
            ptEkpo.setValue("BPRME", item.get("bprme"));
            ptEkpo.setValue("NETPR", item.get("netpr"));
            ptEkpo.setValue("PEINH", item.get("peinh"));
            ptEkpo.setValue("NETWR", item.get("netwr"));
            ptEkpo.setValue("ZZEXTWG", item.get("zzextwg"));
            ptEkpo.setValue("EINDT", item.get("eindt"));
            ptEkpo.setValue("BSTAE", item.get("bstae"));
            ptEkpo.setValue("MWSKZ", item.get("mwskz"));
            ptEkpo.setValue("BEDNR", item.get("bednr"));
            ptEkpo.setValue("AFNAM", item.get("afnam"));
            ptEkpo.setValue("ELIKZ", item.get("elikz"));
            ptEkpo.setValue("INCO1", item.get("inco1"));
            ptEkpo.setValue("INCO2", item.get("inco2"));
            ptEkpo.setValue("ZJHZL", item.get("zjhzl"));
            ptEkpo.setValue("ZJHZLDW", item.get("zjhzldw"));
            ptEkpo.setValue("ZHSL", item.get("zhsl"));

//			ptEkpo.setValue("AFNAM", item.get("afnam"));
			ptEkpo.setValue("KOSTL", item.get("kostl"));
			ptEkpo.setValue("ZNBDD", item.get("znbdd"));

			ptEkpo.setValue("EMLIF", item.get("emlif"));
			ptEkpo.setValue("LBLKZ", item.get("lblkz"));
			ptEkpo.setValue("DEMAND_NO", item.get("demandNo"));
			ptEkpo.setValue("DEMAND_POSNR", item.get("demandPosnr"));
			ptEkpo.setValue("DEMANDER", item.get("demander"));
        }

        JCoTable ptKonv = function.getTableParameterList().getTable("PT_KONV");
        List<Map<String, String>> ptKonvItems = itemMap.get("PT_KONV");
        for (int i = 0; i < ptKonvItems.size(); i++) {
            Map<String, String> item = ptKonvItems.get(i);
            ptKonv.appendRow();
            ptKonv.setRow(i);
            ptKonv.setValue("EBELN", item.get("ebeln"));
            ptKonv.setValue("EBELP", item.get("ebelp"));

			ptKonv.setValue("KMEIN", item.get("kmein"));
			ptKonv.setValue("KPEIN", item.get("kpein"));

            ptKonv.setValue("LIFNR", item.get("lifnr"));
            ptKonv.setValue("KSCHL", item.get("kschl"));
            ptKonv.setValue("KBETR", item.get("kbetr"));
            ptKonv.setValue("KOEIN", item.get("koein"));

			ptKonv.setValue("FLG", item.get("flg"));
        }
        JCoTable ptEket = function.getTableParameterList().getTable("PT_EKET");
        List<Map<String, String>> ptEketItems = itemMap.get("PT_EKET");
        for (int i = 0; i < ptEketItems.size(); i++) {
            Map<String, String> item = ptEketItems.get(i);
            ptEket.appendRow();
            ptEket.setRow(i);
            ptEket.setValue("EBELN", item.get("ebeln"));
            ptEket.setValue("EBELP", item.get("ebelp"));
            ptEket.setValue("MENGE", item.get("menge"));
            ptEket.setValue("BANFN", item.get("banfn"));
            ptEket.setValue("BNFPO", item.get("bnfpo"));
        }

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

        Map<String, Object> result = new HashMap<String, Object>();

		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator, "Z_SRM_MODIFY_PO");
		}

		String[] outTblArr = { "RE_RETURN" };
		List<String> outTblList = Arrays.asList(outTblArr);
		for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator, outTblList);
		}

        return result;
    }
	
	public Map<String, Object> Z_SRM_CREATE_CHANGE_DN(Map<String, String> head, List<Map<String, String>> items, InterMsgData interMsgData) throws EsbException {
		
		Log.getInstance().stdDebug("==================Z_SRM_CREATE_CHANGE_DN================");
		JCoFunction function =  RfcManager.getInstance().getFunction("Z_SRM_CREATE_CHANGE_DN");

		JCoStructure st = (JCoStructure) function.getImportParameterList().getValue("IM_LIKP");
        st.setValue("VERUR_LA", head.get("verurLa"));
        st.setValue("VBELN", head.get("vbeln"));
        st.setValue("LIFNR", head.get("lifnr"));
        st.setValue("LFDAT", head.get("lfdat"));
        st.setValue("DELETE", head.get("deleteFlag"));

        JCoTable ptItem = function.getTableParameterList().getTable("PT_LIPS");
		for (int i = 0; i < items.size(); i++) {
			ptItem.appendRow();
			ptItem.setRow(i);
			ptItem.setValue("SHDH", items.get(i).get("shdh"));
			ptItem.setValue("SHDHH", items.get(i).get("shdhh"));
			ptItem.setValue("VGBEL", items.get(i).get("vgbel"));
			ptItem.setValue("VGPOS", items.get(i).get("vgpos"));
			ptItem.setValue("VBELN", items.get(i).get("vbeln"));
			ptItem.setValue("POSNR", items.get(i).get("posnr"));
			ptItem.setValue("MATNR", items.get(i).get("matnr"));
			ptItem.setValue("WLGG", items.get(i).get("wlgg"));
			ptItem.setValue("LFIMG", items.get(i).get("lfimg"));
			ptItem.setValue("MEINS", items.get(i).get("meins"));
			ptItem.setValue("WERKS", items.get(i).get("werks"));
			ptItem.setValue("LGORT", items.get(i).get("lgort"));
			ptItem.setValue("DELETE", items.get(i).get("deleteFlag"));

			ptItem.setValue("WERKS_XQ", items.get(i).get("werksXq"));
			ptItem.setValue("QTY_SALES", items.get(i).get("qtySales"));
			ptItem.setValue("UNIT_SALES", items.get(i).get("unitSales"));
		}

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

        Map<String, Object> result = new HashMap<String, Object>();
        JCoTable jCoTable = function.getTableParameterList().getTable("PT_RETURN");
        StringBuilder stringBuilder = new StringBuilder();
        for (int i = 0; i < jCoTable.getNumRows(); i++) {
            jCoTable.setRow(i);
            for (JCoField fld : jCoTable) {
                stringBuilder.append(String.format("%s\t", fld.getValue()));
            }
        }
        result.put("LOG", stringBuilder.toString().replaceAll("\r|\n|\t", "-"));
        JCoTable dnTable = function.getTableParameterList().getTable("PT_DN");
        List resultList = new ArrayList();
        for(int i = 0; i < dnTable.getNumRows(); i++){
            Map retMap = new HashMap();
            dnTable.setRow(i);
            for (JCoRecordFieldIterator jCoRecordFieldIterator = dnTable.getRecordFieldIterator(); jCoRecordFieldIterator.hasNextField(); ) {
                JCoField field = jCoRecordFieldIterator.nextRecordField();
                retMap.put(field.getName(), field.getValue());
            }
            resultList.add(retMap);
        }
        result.put("PT_DN", resultList.toString());
        for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext(); ) {
            result = traversalField(result, iterator);
        }
        return result;
    }
	
	public Map<String, Object> Z_SRM_CREATE_INVOICE(Map<String, String> head, List<Map<String, String>> items, InterMsgData interMsgData) throws EsbException {
		
		Log.getInstance().stdDebug("==================Z_SRM_CREATE_INVOICE================");
		JCoFunction function =  RfcManager.getInstance().getFunction("Z_SRM_CREATE_INVOICE");

		JCoTable ptHead = function.getTableParameterList().getTable("PT_RBKP");
        ptHead.appendRow();
        ptHead.setValue("BELNR", head.get("belnr"));
        ptHead.setValue("GJAHR", head.get("gjahr"));
        ptHead.setValue("BUKRS", head.get("bukrs"));
        ptHead.setValue("BLDAT", head.get("bldat"));
        ptHead.setValue("BUDAT", head.get("budat"));
        ptHead.setValue("ZFBDT", head.get("zfbdt"));
        ptHead.setValue("WAERS", head.get("waers"));
        ptHead.setValue("LIFRE", head.get("lifre"));        
        ptHead.setValue("WRBTR", head.get("wrbtr"));
        ptHead.setValue("MWSKZ", head.get("mwskz"));        
        ptHead.setValue("SGTXT", head.get("sgtxt"));
        ptHead.setValue("XBLNR", head.get("xblnr"));
        ptHead.setValue("ZTERM", head.get("zterm"));
        ptHead.setValue("ZLSCH", head.get("zlsch"));
        ptHead.setValue("BLART", head.get("blart"));
        ptHead.setValue("ZUONR", head.get("zuonr"));
        ptHead.setValue("BEZNK", head.get("beznk"));
        ptHead.setValue("XRECH", head.get("xrech"));

        JCoTable ptItem = function.getTableParameterList().getTable("PT_RSEG");
        for (int i = 0; i < items.size(); i++) {
            ptItem.appendRow();
            ptItem.setRow(i);
            ptItem.setValue("VBELN", items.get(i).get("vbeln"));
            ptItem.setValue("POSNR", items.get(i).get("posnr"));
            ptItem.setValue("EBELN", items.get(i).get("ebeln"));
            ptItem.setValue("EBELP", items.get(i).get("ebelp"));
            ptItem.setValue("WRBTR", items.get(i).get("wrbtr"));
            ptItem.setValue("MENGE", items.get(i).get("menge"));
            ptItem.setValue("BPMNG_POP", items.get(i).get("bpmngPop"));
            ptItem.setValue("MWSKZ", items.get(i).get("mwskz"));
			// 经确认SGTXT字段头传了，SAP方虽有这个字段，但没用上，ESB维持原逻辑，不用传
            ptItem.setValue("LFBNR", items.get(i).get("lfbnr"));
            ptItem.setValue("LFPOS", items.get(i).get("lfpos"));
            ptItem.setValue("LFGJA", items.get(i).get("lfgja"));
            
        }

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

        Map<String, Object> result = new HashMap<String, Object>();
        JCoTable jCoTable = function.getTableParameterList().getTable("EX_RETURN");
        StringBuilder stringBuilder = new StringBuilder();
        for (int i = 0; i < jCoTable.getNumRows(); i++) {
            jCoTable.setRow(i);
            for (JCoField fld : jCoTable) {
                stringBuilder.append(String.format("%s\t", fld.getValue()));
            }
        }
        result.put("LOG", stringBuilder.toString().replaceAll("\r|\n|\t", "-"));
        for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext(); ) {
            result = traversalField(result, iterator);
        }
        return result;
    }
	
	public Map<String, Object> Z_SRM_CREATE_EXPENSE_INVOICE(Map<String, String> head, List<Map<String, String>> items, InterMsgData interMsgData) throws EsbException {
		
		Log.getInstance().stdDebug("==================Z_SRM_CREATE_EXPENSE_INVOICE================");
		JCoFunction function =  RfcManager.getInstance().getFunction("Z_SRM_CREATE_EXPENSE_INVOICE");
		String functionId = "Z_SRM_CREATE_EXPENSE_INVOICE";
		
		JCoStructure st = (JCoStructure) function.getImportParameterList().getValue("IM_HEAD");

		st.setValue("BUKRS", head.get("bukrs"));
		st.setValue("BLDAT", head.get("bldat"));
		st.setValue("BUDAT", head.get("budat"));
		st.setValue("ZFBDT", head.get("zfbdt"));
		st.setValue("WRBTR", head.get("wrbtr"));
		st.setValue("WAERS", head.get("waers"));
		st.setValue("MWSKZ", head.get("mwskz"));
		st.setValue("SGTXT", head.get("sgtxt"));        
		st.setValue("XBLNR", head.get("xblnr"));
		st.setValue("ZTERM", head.get("zterm"));        
		st.setValue("ZLSCH", head.get("zlsch"));
		st.setValue("BLART", head.get("blart"));
		st.setValue("LIFRE", head.get("lifre"));
		st.setValue("ZUONR", head.get("zuonr"));
		st.setValue("BKTXT", head.get("bktxt"));
		st.setValue("BEZNK", head.get("beznk"));
		//ptHead.setValue("", head.get(""));
		st.setValue("VORGANG", head.get("vorgang"));
		st.setValue("XWARE_BNK", head.get("xware_bnk"));
		st.setValue("REFERENZBELEGTYP", head.get("referenzbelegtyp"));
		
		JCoTable ptItem = function.getTableParameterList().getTable("IT_TAB");
		for (int i = 0; i < items.size(); i++) {
			ptItem.appendRow();
			ptItem.setRow(i);
//			ptItem.setValue("VBELN", items.get(i).get("vbeln"));
			ptItem.setValue("LFBNR", items.get(i).get("lfbnr"));
			ptItem.setValue("LFPOS", items.get(i).get("lfpos"));
			ptItem.setValue("LFBJA", items.get(i).get("lfbja"));
			ptItem.setValue("EBELN", items.get(i).get("ebeln"));
			ptItem.setValue("EBELP", items.get(i).get("ebelp"));
			ptItem.setValue("WRBTR", items.get(i).get("wrbtr"));
			ptItem.setValue("MENGE", items.get(i).get("menge"));
			ptItem.setValue("MEINS", items.get(i).get("meins"));
			ptItem.setValue("MWSKZ", items.get(i).get("mwskz"));
			ptItem.setValue("KSCHL", items.get(i).get("kschl"));
			ptItem.setValue("SHKZG", items.get(i).get("shkzg"));
			ptItem.setValue("TBTKZ", items.get(i).get("tbtkz"));
		}
		
		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();
		
		RfcManager.getInstance().execute(function);
		
		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);
		
		Map<String, Object> result = new HashMap<String, Object>();
		JCoParameterList outputParam = function.getTableParameterList();
        getSapData(result, outputParam, functionId, interMsgData);
		
		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext(); ) {
            result = traversalField(result, iterator);
        }
        return result;
	}
	
	public Map<String, Object> Z_SRM_DELETE_INVOICE(Map<String, String> head, List<Map<String, String>> items, InterMsgData interMsgData) throws EsbException {
		
		Log.getInstance().stdDebug("==================Z_SRM_DELETE_INVOICE================");
		JCoFunction function =  RfcManager.getInstance().getFunction("Z_SRM_DELETE_INVOICE");

		JCoTable ptHead = function.getTableParameterList().getTable("PT_RBKP");
        ptHead.appendRow();
        
        ptHead.setValue("BELNR", head.get("belnr"));
        ptHead.setValue("GJAHR", head.get("gjahr"));
        ptHead.setValue("BUKRS", head.get("bukrs"));
        ptHead.setValue("STGRD", head.get("stgrd"));
        ptHead.setValue("BUDAT", head.get("budat"));

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

        Map<String, Object> result = new HashMap<String, Object>();
        JCoTable jCoTable = function.getTableParameterList().getTable("EX_RETURN");
        StringBuilder stringBuilder = new StringBuilder();
        for (int i = 0; i < jCoTable.getNumRows(); i++) {
            jCoTable.setRow(i);
            for (JCoField fld : jCoTable) {
                stringBuilder.append(String.format("%s\t", fld.getValue()));
            }
        }
        result.put("LOG", stringBuilder.toString().replaceAll("\r|\n|\t", "-"));
        for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext(); ) {
            result = traversalField(result, iterator);
        }
        return result;
    }
	
	public Map<String, Object> Z_SRM_GET_FBL1N(Map<String, String> head, InterMsgData interMsgData) throws EsbException {
		
		Log.getInstance().stdDebug("==================Z_SRM_GET_FBL1N================");
		JCoFunction function =  RfcManager.getInstance().getFunction("Z_SRM_GET_FBL1N");
		String functionId = "Z_SRM_GET_FBL1N";

		function.getImportParameterList().setValue("IM_BUKRS", head.get("imBukrs"));
		function.getImportParameterList().setValue("IM_LIFNR", head.get("imLifnr"));
		function.getImportParameterList().setValue("IM_BUDATF", head.get("imBudatf"));
		function.getImportParameterList().setValue("IM_BUDATE", head.get("imBudate"));

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

        Map<String, Object> result = new HashMap<String, Object>();
        
        JCoParameterList outputParam = function.getTableParameterList();
        getSapData(result, outputParam, functionId, interMsgData);
		
		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext(); ) {
            result = traversalField(result, iterator);
        }
        return result;
    }
	
	public Map<String, Object> Z_SRM_GET_GOODS_IN(Map<String, String> head, InterMsgData interMsgData) throws EsbException {
		
		Log.getInstance().stdDebug("==================Z_SRM_GET_GOODS_IN================");
		JCoFunction function =  RfcManager.getInstance().getFunction("Z_SRM_GET_GOODS_IN");
	    function.getImportParameterList().setValue("CPUDT_F", head.get("cpudt_f"));
		function.getImportParameterList().setValue("CPUDT_E", head.get("cpudt_e"));

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

        Map<String, Object> result = new HashMap<String, Object>();
        
		for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator, "Z_SRM_GET_GOODS_IN");
		}
//		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
//			result = traversalField(result, iterator);
//		}
		return result;
    }
	
	public Map<String, Object> Z_SRM_PAYMENT_QSRQ(Map<String, String> head, InterMsgData interMsgData) throws EsbException {
		
		Log.getInstance().stdDebug("==================Z_SRM_PAYMENT_QSRQ================");
		JCoFunction function =  RfcManager.getInstance().getFunction("Z_SRM_PAYMENT_QSRQ");
		function.getImportParameterList().setValue("IM_FIQS_FROM", head.get("receiptDateFrom"));
		function.getImportParameterList().setValue("IM_FIQS_TO", head.get("receiptDateTo"));
		function.getImportParameterList().setValue("IM_UPDATE_FROM", head.get("receiptDateUpdateFrom"));
		function.getImportParameterList().setValue("IM_UPDATE_TO", head.get("receiptDateUpdateTo"));
		
		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();
		
		RfcManager.getInstance().execute(function);
		
		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);
		
		Map<String, Object> result = new HashMap<String, Object>();
		
		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator, "Z_SRM_PAYMENT_QSRQ");
		}

		String[] outTblArr = { "ET_LIST" };
		List<String> outTblList = Arrays.asList(outTblArr);
		for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator, outTblList);
		}
		return result;
	}
	
	public Map<String, Object> Z_SRM_RETURN_PAYMENY(Map<String, String> head, InterMsgData interMsgData) throws EsbException {
		
		Log.getInstance().stdDebug("==================Z_SRM_RETURN_PAYMENY================");
		JCoFunction function =  RfcManager.getInstance().getFunction("Z_SRM_RETURN_PAYMENY");
		function.getImportParameterList().setValue("IM_ERDAT_FROM", head.get("createDateFrom"));
		function.getImportParameterList().setValue("IM_ERDAT_TO", head.get("createDateTo"));
		
		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();
		
		RfcManager.getInstance().execute(function);
		
		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);
		Map<String, Object> result = new HashMap<String, Object>();
		for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator, "Z_SRM_RETURN_PAYMENY");
		}
		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
			result = traversalField(result, iterator);
		}
		return result;
	}
	
	public Map<String, Object> Z_SRM_PAYMENT_STATUS(Map<String, String> head, InterMsgData interMsgData) throws EsbException {
		
		Log.getInstance().stdDebug("==================Z_SRM_PAYMENT_STATUS================");
		JCoFunction function =  RfcManager.getInstance().getFunction("Z_SRM_PAYMENT_STATUS");
//		JCoStructure st = (JCoStructure) function.getImportParameterList().getValue("IT_LIST");
//		st.setValue("PAYNO", head.get("fbk26"));
		JCoTable table = function.getTableParameterList().getTable("IT_LIST");
		table.appendRow();
//		table.setRow(0);
		table.setValue("PAYNO", head.get("fbk26"));

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, Object> result = new HashMap<String, Object>();
		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator);
		}

		JCoTable jCoTable = function.getTableParameterList().getTable("ET_LIST");
		List resultList = new ArrayList();
		Map retMap = new HashMap();
		for (int i = 0; i < jCoTable.getNumRows(); i++) {
			jCoTable.setRow(i);
			for (JCoField fld : jCoTable) {
				retMap.put(fld.getName(), fld.getValue());
			}
			resultList.add(retMap);
		}
		result.put("ET_LIST", resultList);

		return result;
	}
	
	public Map<String, Object> Z_SRM_PROJECT_OANO(Map<String, String> head, InterMsgData interMsgData) throws EsbException {
		
		Log.getInstance().stdDebug("==================Z_SRM_PROJECT_OANO================");
		JCoFunction function =  RfcManager.getInstance().getFunction("Z_SRM_PROJECT_OANO");
		
		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();
		
		RfcManager.getInstance().execute(function);
		
		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);
		
		Map<String, Object> result = new HashMap<String, Object>();
		
		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator);
		}

		String[] outTblArr = { "ET_LIST" };
		List<String> outTblList = Arrays.asList(outTblArr);
		for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator, outTblList);
		}
		return result;
	}

	public Map<String, Object> Z_SRM_SOA_SYNC(Map<String, String> head, InterMsgData interMsgData) throws EsbException {

		Log.getInstance().stdDebug("==================Z_SRM_SOA_SYNC================");
		JCoFunction function =  RfcManager.getInstance().getFunction("Z_SRM_SOA_SYNC");
		function.getImportParameterList().setValue("IM_BLDAT", head.get("IM_BLDAT"));//凭证创建日期
		if(head.get("BUKRS")!=null && head.get("BUKRS").length()>0){
			JCoTable ptItem = function.getTableParameterList().getTable("IT_TAB");
			ptItem.appendRow();
			ptItem.setRow(0);
			ptItem.setValue("BUKRS", head.get("BUKRS"));
			ptItem.setValue("GJAHR", head.get("GJAHR"));
			ptItem.setValue("BELNR", head.get("BELNR"));

		}

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, Object> result = new HashMap<String, Object>();

		for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator, "Z_SRM_SOA_SYNC");
		}

		return result;
	}


	public Map<String, Object> Z_SRM_SOA_RETURN(Map<String, String> head, InterMsgData interMsgData) throws EsbException {

		Log.getInstance().stdDebug("==================Z_SRM_SOA_RETURN================");
		JCoFunction function =  RfcManager.getInstance().getFunction("Z_SRM_SOA_RETURN");
		JCoTable itHead = function.getTableParameterList().getTable("IT_SOA");
		itHead.appendRow();
		itHead.setValue("BUKRS", head.get("BUKRS"));
		itHead.setValue("GJAHR", head.get("GJAHR"));
		itHead.setValue("BELNR", head.get("BELNR"));
		itHead.setValue("BUZEI", head.get("BUZEI"));
		itHead.setValue("ZVER", head.get("ZVER"));

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, Object> result = new HashMap<String, Object>();

        for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext(); ) {
            result = traversalField(result, iterator);
        }

		return result;
	}
	
	public Map<String, Object> Z_SRM_GET_MAIN_INFO(Map<String, String> head, InterMsgData interMsgData) throws EsbException {
		
		Log.getInstance().stdDebug("==================Z_SRM_GET_MAIN_INFO================");
		JCoFunction function =  RfcManager.getInstance().getFunction("Z_SRM_GET_MAIN_INFO");
		
		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();
		
		RfcManager.getInstance().execute(function);
		
		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);
		
		Map<String, Object> result = new HashMap<String, Object>();
		// 公司代码
		String[] outTblArr = { "ET_T001" };
		List<String> outTblList = Arrays.asList(outTblArr);
		for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator, outTblList);
		}
		// 工厂
		String[] outTblArr2 = { "ET_T001W" };
		List<String> outTblList2 = Arrays.asList(outTblArr2);
		for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator, outTblList2);
		}
		// 库存地点
		String[] outTblArr3 = { "ET_T001L" };
		List<String> outTblList3 = Arrays.asList(outTblArr3);
		for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator, outTblList3);
		}
		// 采购组
		String[] outTblArr4 = { "ET_T024" };
		List<String> outTblList4 = Arrays.asList(outTblArr4);
		for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator, outTblList4);
		}
//		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
//			result = traversalTable(result, iterator);
//		}

		return result;
	}



	public Map<String, Object> Z_SRM_MATERIAL_PRICE(Map<String, String> head,List<Map<String, String>> items, InterMsgData interMsgData) throws EsbException {

		Log.getInstance().stdDebug("==================Z_SRM_MATERIAL_PRICE================");
		Map<String, Object> result = new HashMap<String, Object>();


		HashMap<String, Object> reqHm=new HashMap<>();
		reqHm.put("userId", head.get("userId"));
		reqHm.put("accesskey", head.get("accesskey"));
		String reqJson=JSON.toJSONString(reqHm, SerializerFeature.WriteMapNullValue);
		String opration = "调用SPMS登录接口";
		sendReqLog(interMsgData, "ESB", "SPMS", reqJson, opration);
		long startTime = System.currentTimeMillis();
		RestTemplate restTemplate = new RestTemplate();
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		HttpEntity<String> requestEntity = new HttpEntity<String>(reqJson, headers);
		List<HttpMessageConverter<?>> httpMessageConverters = restTemplate.getMessageConverters();
		httpMessageConverters.stream().forEach(httpMessageConverter -> {
			if (httpMessageConverter instanceof StringHttpMessageConverter) {
				StringHttpMessageConverter messageConverter = (StringHttpMessageConverter) httpMessageConverter;
				messageConverter.DEFAULT_CHARSET.forName("UTF-8");
			}
		});
		String redisPre = ConstantUtil.REDIS_PRE_DICT + ":" + ConstantUtil.REDIS_PRE_DICT_TYPE_URL_SPMS + ":";
		String srmRestUrl = getRedisValue(redisPre + "SPMS_REST_URL")+"oa/esblogin";
		Log.getInstance().stdDebug("SPMS_REST_URL=" + srmRestUrl);
		String spmsRes = restTemplate.postForObject(srmRestUrl, requestEntity, String.class);
		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SPMS", "ESB", time, spmsRes,"收到SPMS接口返回信息");
		Map resMap = JSON.parseObject(spmsRes, Map.class);
		if(!"200".equals(resMap.get("code").toString())){
			Map resData = JSON.parseObject(resMap.get("data").toString(), Map.class);

			HashMap<String, Object> reqHm1=new HashMap<>();
			reqHm1.put("doc", items);
			String reqJson1=JSON.toJSONString(reqHm1, SerializerFeature.WriteMapNullValue);
			String opration1 = "调用SPMS接口";
			sendReqLog(interMsgData, "ESB", "SPMS", reqJson1, opration1);
			long startTime1 = System.currentTimeMillis();
			RestTemplate restTemplate1 = new RestTemplate();
			HttpHeaders headers1 = new HttpHeaders();
			headers1.setContentType(MediaType.APPLICATION_JSON);
			headers1.set("token", resData.get("token").toString());
			HttpEntity<String> requestEntity1 = new HttpEntity<String>(reqJson1, headers1);
			List<HttpMessageConverter<?>> httpMessageConverters1 = restTemplate1.getMessageConverters();
			httpMessageConverters1.stream().forEach(httpMessageConverter -> {
				if (httpMessageConverter instanceof StringHttpMessageConverter) {
					StringHttpMessageConverter messageConverter = (StringHttpMessageConverter) httpMessageConverter;
					messageConverter.DEFAULT_CHARSET.forName("UTF-8");
				}
			});
			String srmRestUrl1 = getRedisValue(redisPre + "SPMS_REST_URL")+"materialPrice/syncMaterialPrice";
			Log.getInstance().stdDebug("SPMS_REST_URL=" + srmRestUrl1);
			String spmsRes1 = restTemplate1.postForObject(srmRestUrl1, requestEntity1, String.class);
			long endTime1 = System.currentTimeMillis();
			int time1 = (int) (endTime1 - startTime1);
			sendResLog(interMsgData, "SPMS", "ESB", time1, spmsRes1,"收到SPMS接口返回信息");
			Map resMap1 = JSON.parseObject(spmsRes1, Map.class);

			result.put("msg", resMap1.get("msg"));
			result.put("code", resMap1.get("code"));
		}else{
			result.put("msg", "SPMS接口请求异常");
			result.put("code", "100");
		}

		return result;

	}


	public Map<String, Object> Z_SRM_LABOR_SECTION(Map<String, String> head, InterMsgData interMsgData) throws EsbException {

		Log.getInstance().stdDebug("==================Z_SRM_LABOR_SECTION================");
		Map<String, Object> result = new HashMap<String, Object>();

		HashMap<String, Object> reqHm=new HashMap<>();
		reqHm.put("userId", head.get("userId"));
		reqHm.put("accesskey", head.get("accesskey"));
		String reqJson=JSON.toJSONString(reqHm, SerializerFeature.WriteMapNullValue);
		String opration = "调用SPMS登录接口";
		sendReqLog(interMsgData, "ESB", "SPMS", reqJson, opration);
		long startTime = System.currentTimeMillis();
		RestTemplate restTemplate = new RestTemplate();
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		HttpEntity<String> requestEntity = new HttpEntity<String>(reqJson, headers);
		List<HttpMessageConverter<?>> httpMessageConverters = restTemplate.getMessageConverters();
		httpMessageConverters.stream().forEach(httpMessageConverter -> {
			if (httpMessageConverter instanceof StringHttpMessageConverter) {
				StringHttpMessageConverter messageConverter = (StringHttpMessageConverter) httpMessageConverter;
				messageConverter.DEFAULT_CHARSET.forName("UTF-8");
			}
		});
		String redisPre = ConstantUtil.REDIS_PRE_DICT + ":" + ConstantUtil.REDIS_PRE_DICT_TYPE_URL_SPMS + ":";
		String srmRestUrl = getRedisValue(redisPre + "SPMS_REST_URL")+"oa/esblogin";
		Log.getInstance().stdDebug("SPMS_REST_URL=" + srmRestUrl);
		String spmsRes = restTemplate.postForObject(srmRestUrl, requestEntity, String.class);
		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SPMS", "ESB", time, spmsRes,"收到SPMS接口返回信息");
		Map resMap = JSON.parseObject(spmsRes, Map.class);
		if(!"200".equals(resMap.get("code").toString())){
			Map resData = JSON.parseObject(resMap.get("data").toString(), Map.class);
			String reqJson1=JSON.toJSONString(head, SerializerFeature.WriteMapNullValue);
			String opration1 = "调用SPMS接口";
			sendReqLog(interMsgData, "ESB", "SPMS", reqJson1, opration1);
			long startTime1 = System.currentTimeMillis();
			RestTemplate restTemplate1 = new RestTemplate();
			HttpHeaders headers1 = new HttpHeaders();
			headers1.setContentType(MediaType.APPLICATION_JSON);
			headers1.set("token", resData.get("token").toString());
			HttpEntity<String> requestEntity1 = new HttpEntity<String>(reqJson1, headers1);
			List<HttpMessageConverter<?>> httpMessageConverters1 = restTemplate1.getMessageConverters();
			httpMessageConverters1.stream().forEach(httpMessageConverter -> {
				if (httpMessageConverter instanceof StringHttpMessageConverter) {
					StringHttpMessageConverter messageConverter = (StringHttpMessageConverter) httpMessageConverter;
					messageConverter.DEFAULT_CHARSET.forName("UTF-8");
				}
			});
			String srmRestUrl1 = getRedisValue(redisPre + "SPMS_REST_URL")+"materialCategoryLabor/syncMaterialCategoryLabor";
			Log.getInstance().stdDebug("SPMS_REST_URL=" + srmRestUrl1);
			String spmsRes1 = restTemplate1.postForObject(srmRestUrl1, requestEntity1, String.class);
			long endTime1 = System.currentTimeMillis();
			int time1 = (int) (endTime1 - startTime1);
			sendResLog(interMsgData, "SPMS", "ESB", time1, spmsRes1,"收到SPMS接口返回信息");
			Map resMap1 = JSON.parseObject(spmsRes1, Map.class);

			result.put("msg", resMap1.get("msg"));
			result.put("code", resMap1.get("code"));
		}else{
			result.put("msg", "SPMS接口请求异常");
			result.put("code", "100");
		}

		return result;
	}

	
	public Map<String, Object> Z_SRM_GET_PRINFO(Map<String, String> head, InterMsgData interMsgData) throws EsbException {
		
		Log.getInstance().stdDebug("==================Z_SRM_GET_PRINFO================");
		JCoFunction function =  RfcManager.getInstance().getFunction("Z_SRM_GET_PRINFO");
		String functionId = "Z_SRM_GET_PRINFO";

		function.getImportParameterList().setValue("IM_WERKS", head.get("imWerks"));
		function.getImportParameterList().setValue("IM_ERDAT_F", head.get("imErdatF"));
		function.getImportParameterList().setValue("IM_ERDAT_E", head.get("imErdatE"));
		function.getImportParameterList().setValue("IM_BANFN", head.get("imBanfn"));

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

        Map<String, Object> result = new HashMap<String, Object>();
        
		for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator, "Z_SRM_GET_PRINFO");
		}
		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
			result = traversalField(result, iterator);
		}
		return result;
    }


	public Map<String, Object> Z_SRM_GET_PRINFO_02(List<Map<String, String>> items, InterMsgData interMsgData) throws EsbException {

		Log.getInstance().stdDebug("==================Z_SRM_GET_PRINFO_02================");
		JCoFunction function =  RfcManager.getInstance().getFunction("Z_SRM_GET_PRINFO_02");
		String functionId = "Z_SRM_GET_PRINFO_02";
		JCoTable prItem = function.getTableParameterList().getTable("IT_PR");
		for (int i = 0; i < items.size(); i++) {
			prItem.appendRow();
			prItem.setRow(i);
			prItem.setValue("BANFN", items.get(i).get("BANFN"));
			prItem.setValue("BNFPO", items.get(i).get("BNFPO"));
		}

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, Object> result = new HashMap<String, Object>();

		for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator, "Z_SRM_GET_PRINFO_02");
		}
		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
			result = traversalField(result, iterator);
		}
		return result;
	}


	
	public Map<String, Object> Z_SRM_GET_INVOICELIST(Map<String, String> head, InterMsgData interMsgData) throws EsbException {
		
		Log.getInstance().stdDebug("==================Z_SRM_GET_INVOICELIST================");
		JCoFunction function =  RfcManager.getInstance().getFunction("Z_SRM_GET_INVOICELIST");
		String functionId = "Z_SRM_GET_INVOICELIST";

		function.getImportParameterList().setValue("IM_CPUDT_F", head.get("imCpudtf"));
		function.getImportParameterList().setValue("IM_CPUDT_E", head.get("imCpudte"));
		function.getImportParameterList().setValue("IM_BUKRS", head.get("imBukrs"));

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

        Map<String, Object> result = new HashMap<String, Object>();
        
        JCoParameterList outputParam = function.getTableParameterList();
        getSapData(result, outputParam, functionId, interMsgData);
		
		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext(); ) {
            result = traversalField(result, iterator);
        }
        return result;
    }
	
	public Map<String, Object> Z_SRM_CLOSE_DEL_PO(List<Map<String, String>> items, InterMsgData interMsgData) throws EsbException {

		Log.getInstance().stdDebug("==================Z_SRM_CLOSE_DEL_PO================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_SRM_CLOSE_DEL_PO");

		JCoTable table = function.getTableParameterList().getTable("PT_EKPO");
		for (int i = 0; i < items.size(); i++) {
			table.appendRow();
			table.setRow(i);
			table.setValue("EBELN", items.get(i).get("ebeln"));
			table.setValue("EBELP", items.get(i).get("ebelp"));
			table.setValue("LOEKZ", items.get(i).get("loekz"));
			table.setValue("ELIKZ", items.get(i).get("elikz"));
		}

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, Object> result = new HashMap<String, Object>();

		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator);
		}

		String[] outTblArr = { "RE_RETURN" };
		List<String> outTblList = Arrays.asList(outTblArr);
		for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator, outTblList);
		}

		return result;
	}

	public Map<String, Object> Z_SRM_GET_PO_ZZ(Map<String, String> head, InterMsgData interMsgData) throws EsbException {

		Log.getInstance().stdDebug("==================Z_SRM_GET_PO_ZZ================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_SRM_GET_PO_ZZ");

		if (StringUtils.isNotEmpty(head.get("imEbeln"))) {
			function.getImportParameterList().setValue("IM_EBELN", head.get("imEbeln"));
		}

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, Object> result = new HashMap<String, Object>();

		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator);
		}

		for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator, "Z_SRM_GET_PO_ZZ");
		}

		return result;
	}

	public Map<String, Object> Z_SRM_UPDATE_ZDCHTH_ZZ(List<Map<String, String>> items, InterMsgData interMsgData) throws EsbException {

		Log.getInstance().stdDebug("==================Z_SRM_UPDATE_ZDCHTH_ZZ================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_SRM_UPDATE_ZDCHTH_ZZ");

		JCoTable table = function.getTableParameterList().getTable("PT_ZDCHTH");
		for (int i = 0; i < items.size(); i++) {
			table.appendRow();
			table.setRow(i);
			table.setValue("EBELN", items.get(i).get("ebeln"));
			table.setValue("ZDCHTH", items.get(i).get("zdchth"));
		}

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, Object> result = new HashMap<String, Object>();

		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator);
		}

		return result;
	}

	public Map<String, Object> Z_SRM_SRMRPSTATUS_UPDATE(List<Map<String, String>> items, InterMsgData interMsgData) throws EsbException {

		Log.getInstance().stdDebug("==================Z_SRM_SRMRPSTATUS_UPDATE================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_SRM_SRMRPSTATUS_UPDATE");

		JCoTable table = function.getTableParameterList().getTable("PT_PR");
		for (int i = 0; i < items.size(); i++) {
			table.appendRow();
			table.setRow(i);
			table.setValue("BANFN", items.get(i).get("banfn"));
			table.setValue("BNFPO", items.get(i).get("bnfpo"));
			table.setValue("ZRPTATUS", items.get(i).get("zrptatus"));
		}

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, Object> result = new HashMap<String, Object>();

		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator);
		}

		return result;
	}
	
	public Map<String, Object> Z_ZXD_CONFIRM_CANCEL(Map<String, String> head, InterMsgData interMsgData) throws EsbException {
		
		Log.getInstance().stdDebug("==================Z_ZXD_CONFIRM_CANCEL================");
		JCoFunction function =  RfcManager.getInstance().getFunction("Z_ZXD_CONFIRM_CANCEL");

		function.getImportParameterList().setValue("IM_XDNUM", head.get("imXdnum"));
        function.getImportParameterList().setValue("IM_XZHJ", head.get("imXzhj"));

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

        Map<String, Object> result = new HashMap<String, Object>();
        for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext(); ) {
            result = traversalField(result, iterator);
        }
        return result;
    }
	
	public Map<String, Object> Z_SPMS_MIGO(Map<String, String> head, List<Map<String, String>> items, InterMsgData interMsgData) throws EsbException {
		
		Log.getInstance().stdDebug("==================Z_SPMS_MIGO================");
		JCoFunction function =  RfcManager.getInstance().getFunction("Z_SPMS_MIGO");

		JCoTable ptHead = function.getTableParameterList().getTable("IT_HEAD");
        ptHead.appendRow();
        ptHead.setValue("XBLNR", head.get("xblnr"));
        ptHead.setValue("VBELN_IM", head.get("vbelnIm"));
        ptHead.setValue("BLDAT", head.get("bldat"));
        ptHead.setValue("BUDAT", head.get("budat"));
        ptHead.setValue("CREATEDBY", head.get("createdby"));
        ptHead.setValue("BWART", head.get("bwart"));
        ptHead.setValue("BKTXT", head.get("bktxt"));

		ptHead.setValue("EBELN", head.get("ebeln"));

        JCoTable ptItem = function.getTableParameterList().getTable("IT_ITME");
        for (int i = 0; i < items.size(); i++) {
            ptItem.appendRow();
            ptItem.setRow(i);
            ptItem.setValue("VBELN_IM", items.get(i).get("vbelnIm"));
            ptItem.setValue("VBELP_IM", items.get(i).get("vbelpIm"));
            ptItem.setValue("MATNR", items.get(i).get("matnr"));
            ptItem.setValue("BSTMG", items.get(i).get("bstmg"));
            ptItem.setValue("BSTME", items.get(i).get("bstme"));
            ptItem.setValue("WERKS", items.get(i).get("werks"));
            ptItem.setValue("LGORT", items.get(i).get("lgort"));
            ptItem.setValue("SGTXT", items.get(i).get("sgtxt"));

			ptItem.setValue("EBELN", items.get(i).get("ebeln"));
			ptItem.setValue("EBELP", items.get(i).get("ebelp"));
        }

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

        Map<String, Object> result = new HashMap<String, Object>();
        JCoTable jCoTable = function.getTableParameterList().getTable("IT_RETURN");
        StringBuilder stringBuilder = new StringBuilder();
        for (int i = 0; i < jCoTable.getNumRows(); i++) {
            jCoTable.setRow(i);
            for (JCoField fld : jCoTable) {
                stringBuilder.append(String.format("%s\t", fld.getValue()));
            }
        }
        result.put("IT_RETURN", stringBuilder.toString().replaceAll("\r|\n|\t", "-"));
        JCoTable jCoTable1 = function.getTableParameterList().getTable("IT_MSG");
        List<Map<String, String>> itMsgList=new ArrayList<>();
        for (int i = 0; i < jCoTable1.getNumRows(); i++) {
            jCoTable1.setRow(i);
            Map<String, String> rowMap = new HashMap<String, String>();
            for (JCoField fld : jCoTable1) {
				if ("BLDAT".equals(fld.getName()) || "BUDAT".equals(fld.getName())) {
					String date = fld.getValue() != null ? convertDate(fld.getValue()) : "";
					rowMap.put(fld.getName(), date);
				} else {
					rowMap.put(fld.getName(), fld.getValue() != null ? fld.getValue().toString() : "");
				}
            }
            itMsgList.add(rowMap);
        }

        result.put("IT_MSG", itMsgList);
        for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext(); ) {
            result = traversalField(result, iterator);
        }
        return result;
    }
	
	public Map<String, Object> Z_SPMS_1150(Map<String, String> head, List<Map<String, String>> items, InterMsgData interMsgData) throws EsbException {
		
		Log.getInstance().stdDebug("==================Z_SPMS_1150================");
		JCoFunction function =  RfcManager.getInstance().getFunction("Z_SPMS_1150");

		function.getImportParameterList().setValue("I_FLAG", head.get("iFlag"));
		function.getImportParameterList().setValue("I_MBLNR", head.get("iMblnr"));
		function.getImportParameterList().setValue("I_MJAHR", head.get("iMjahr"));
		function.getImportParameterList().setValue("I_XDNUM", head.get("iXdnum"));
		function.getImportParameterList().setValue("I_BKTXT", head.get("iBktxt"));
		function.getImportParameterList().setValue("I_BLDAT", head.get("iBldat"));
        function.getImportParameterList().setValue("I_BUDAT", head.get("iBudat"));

		JCoTable ptItem = function.getTableParameterList().getTable("IT_LIST");
		for (int i = 0; i < items.size(); i++) {
			ptItem.appendRow();
			ptItem.setRow(i);
			ptItem.setValue("ZEILE", items.get(i).get("zeile"));
			ptItem.setValue("XDNUM", items.get(i).get("xdnum"));
			ptItem.setValue("POSNR", items.get(i).get("posnr"));
			ptItem.setValue("MATNR", items.get(i).get("matnr"));
			ptItem.setValue("MENGE", items.get(i).get("menge"));
			ptItem.setValue("MEINS", items.get(i).get("meins"));
			ptItem.setValue("LGORT", items.get(i).get("lgort"));
			ptItem.setValue("SGTXT", items.get(i).get("sgtxt"));
			ptItem.setValue("AEMNG_CH", items.get(i).get("aemngCh"));
		}

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

        Map<String, Object> result = new HashMap<String, Object>();
		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
			result = traversalField(result, iterator);
		}
        return result;
    }
	
	public Map<String, Object> Z_SPMS_ANOMALY_POST(Map<String, String> head, List<Map<String, String>> items, InterMsgData interMsgData) throws EsbException {
		
		Log.getInstance().stdDebug("==================Z_SPMS_ANOMALY_POST================");
		JCoFunction function =  RfcManager.getInstance().getFunction("Z_SPMS_ANOMALY_POST");

		JCoStructure st = (JCoStructure) function.getImportParameterList().getValue("I_HEADER");
        st.setValue("LGORT", head.get("lgort"));
        st.setValue("BLDAT", head.get("bldat"));
        st.setValue("BUDAT", head.get("budat"));
        st.setValue("WERKS", head.get("werks"));
        st.setValue("BWART", head.get("bwart"));
        st.setValue("BKTXT", head.get("bktxt"));

        JCoTable ptItem = function.getTableParameterList().getTable("IT_ITME");
        for (int i = 0; i < items.size(); i++) {
            ptItem.appendRow();
            ptItem.setRow(i);
            ptItem.setValue("ZEILE", items.get(i).get("zeile"));
            ptItem.setValue("MATNR", items.get(i).get("matnr"));
            ptItem.setValue("MENGE", items.get(i).get("menge"));
            ptItem.setValue("MEINS", items.get(i).get("meins"));
            ptItem.setValue("LIFNR", items.get(i).get("lifnr"));
            ptItem.setValue("KOSTL", items.get(i).get("kostl"));
            ptItem.setValue("WERKS", items.get(i).get("werks"));
            ptItem.setValue("LGORT", items.get(i).get("lgort"));
            ptItem.setValue("SGTXT", items.get(i).get("sgtxt"));
            ptItem.setValue("ZSHWL", items.get(i).get("zshwl"));
            ptItem.setValue("GRUND", items.get(i).get("grund"));
            ptItem.setValue("GRUND", items.get(i).get("grund"));
            ptItem.setValue("EXBWR", items.get(i).get("exbwr"));
            ptItem.setValue("AUFNR", items.get(i).get("aufnr"));
            
        }

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

        Map<String, Object> result = new HashMap<String, Object>();
        JCoTable jCoTable = function.getTableParameterList().getTable("IT_RETURN");
        StringBuilder stringBuilder = new StringBuilder();
        for (int i = 0; i < jCoTable.getNumRows(); i++) {
            jCoTable.setRow(i);
            for (JCoField fld : jCoTable) {
                stringBuilder.append(String.format("%s\t", fld.getValue()));
            }
        }
        result.put("IT_RETURN", stringBuilder.toString().replaceAll("\r|\n|\t", "-"));
        for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext(); ) {
            result = traversalField(result, iterator);
        }
        return result;
    }
	
	public Map<String, Object> Z_SPMS_SETBOXCODE(Map<String, String> head, Map<String, List<Map<String, String>>> itemMap, InterMsgData interMsgData) throws EsbException {
		
		Log.getInstance().stdDebug("==================Z_SPMS_SETBOXCODE================");
		JCoFunction function =  RfcManager.getInstance().getFunction("Z_SPMS_SETBOXCODE");

		JCoTable ptHead = function.getTableParameterList().getTable("IT_HEAD");
        ptHead.appendRow();
        ptHead.setValue("PACKAGE_NO", head.get("packageNo"));
        ptHead.setValue("SRM_DN", head.get("srmDn"));
        ptHead.setValue("SAP_DN", head.get("sapDn"));
        ptHead.setValue("LIFNR", head.get("lifnr"));
        ptHead.setValue("VOLUM_T", head.get("volumT"));
        ptHead.setValue("BRGEW_T", head.get("brgewT"));
        ptHead.setValue("ZXDATE_F", head.get("zxdateF"));
        ptHead.setValue("ZXDATE_T", head.get("zxdateT"));
        ptHead.setValue("YSR", head.get("ysr"));
        ptHead.setValue("CYRLXFS", head.get("cyrlxfs"));
        ptHead.setValue("SHR", head.get("shr"));
        ptHead.setValue("SHRLXFS", head.get("shrlxfs"));
        ptHead.setValue("SHDZ", head.get("shdz"));
        ptHead.setValue("SPMS_CONTRACT_NO", head.get("spmsContractNo"));
        ptHead.setValue("SPMS_FLG", head.get("spmsFlg"));

        if (null != itemMap.get("IT_ITME")) {
            JCoTable ptItem = function.getTableParameterList().getTable("IT_ITME");
            List<Map<String, String>> items = itemMap.get("IT_ITME");
            for (int i = 0; i < items.size(); i++) {
                ptItem.appendRow();
                ptItem.setRow(i);
                ptItem.setValue("PACKAGE_NO", items.get(i).get("packageNo"));
                ptItem.setValue("SERIAL", items.get(i).get("serial"));
                ptItem.setValue("PACKAGE_CODE", items.get(i).get("packageCode"));
                ptItem.setValue("PACKAGE_POSNR", items.get(i).get("packagePosnr"));
                ptItem.setValue("MATNR", items.get(i).get("matnr"));
                ptItem.setValue("SPECS", items.get(i).get("specs"));
                ptItem.setValue("MENGE", items.get(i).get("menge"));
                ptItem.setValue("MEINS", items.get(i).get("meins"));
                ptItem.setValue("SRM_CONTRACT_NO", items.get(i).get("srmContractNo"));
                ptItem.setValue("EBELN", items.get(i).get("ebeln"));
                ptItem.setValue("EBELP", items.get(i).get("ebelp"));
                ptItem.setValue("VBELN", items.get(i).get("vbeln"));
                ptItem.setValue("POSNR", items.get(i).get("posnr"));
                ptItem.setValue("DEMAND_NO", items.get(i).get("demandNo"));
                ptItem.setValue("DEMAND_POSNR", items.get(i).get("demandPosnr"));
                ptItem.setValue("DEMANDER", items.get(i).get("demander"));
                ptItem.setValue("MARK", items.get(i).get("mark"));
                ptItem.setValue("BRGEW", items.get(i).get("brgew"));
                ptItem.setValue("NTGEW", items.get(i).get("ntgew"));
                ptItem.setValue("GNSHSL", items.get(i).get("gnshsl"));
                ptItem.setValue("GNSJSL", items.get(i).get("gnsjsl"));
                ptItem.setValue("HWSHSL", items.get(i).get("hwshsl"));

				ptItem.setValue("POSNR_SRM", items.get(i).get("posnrSrm"));
				ptItem.setValue("XDNUM", items.get(i).get("xdnum"));
				ptItem.setValue("ZPOSNR", items.get(i).get("zposnr"));
            }
        }

        if (null != itemMap.get("IT_XHEAD")) {
            JCoTable ptItem = function.getTableParameterList().getTable("IT_XHEAD");
            List<Map<String, String>> items = itemMap.get("IT_XHEAD");
            for (int i = 0; i < items.size(); i++) {
                ptItem.appendRow();
                ptItem.setRow(i);
                ptItem.setValue("PACKAGE_NO", items.get(i).get("packageNo"));
                ptItem.setValue("PACKAGE_CODE", items.get(i).get("packageCode"));
                ptItem.setValue("OUTER_BOX_TEXTURE", items.get(i).get("outerBoxTexture"));
                ptItem.setValue("TOTAL_QTY", items.get(i).get("totalQty"));
                ptItem.setValue("BOX_BRGEW", items.get(i).get("boxBrgew"));
                ptItem.setValue("BOX_VOLUM", items.get(i).get("boxVolum"));
                ptItem.setValue("LENGTH", items.get(i).get("length"));
                ptItem.setValue("WIDTH", items.get(i).get("width"));
                ptItem.setValue("HEIGHT", items.get(i).get("height"));
                ptItem.setValue("COLOR", items.get(i).get("color"));
				// 函数Z_SPMS_SETBOXCODE，表IT_XHEAD新增WERKS_CF（工厂）、LGORT_CF（库存地点）、STATSU_PKCODE（箱码状态）
                ptItem.setValue("FLG_YFP", items.get(i).get("flgYfp"));
                ptItem.setValue("WERKS_CF", items.get(i).get("werksCf"));
                ptItem.setValue("LGORT_CF", items.get(i).get("lgortCf"));
                ptItem.setValue("STATSU_PKCODE", items.get(i).get("statsuPkcode"));
                ptItem.setValue("BZ", items.get(i).get("bz"));
            }
        }

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

        Map<String, Object> result = new HashMap<String, Object>();
        for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext(); ) {
            result = traversalField(result, iterator);
        }
        return result;
    }
	
	public Map<String, Object> Z_SPMS_SET_ZXD_1120(Map<String, String> head, InterMsgData interMsgData) throws EsbException {
		
		Log.getInstance().stdDebug("==================Z_SPMS_SET_ZXD_1120================");
		JCoFunction function =  RfcManager.getInstance().getFunction("Z_SPMS_SET_ZXD_1120");

		function.getImportParameterList().setValue("I_FLAG", head.get("iFlag"));
		function.getImportParameterList().setValue("I_XDNUM", head.get("iXdnum"));

        sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();
        
        RfcManager.getInstance().execute(function);
        
        long endTime = System.currentTimeMillis();
        int time = (int) (endTime - startTime);
        sendResLog(interMsgData, "SAP", "ESB", time, function);
        
        Map<String, Object> result = new HashMap<String, Object>();
        for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext(); ) {
            result = traversalField(result, iterator);
        }
		for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator);
		}
        return result;
    }
	
	public Map<String, Object> Z_SPMS_GETDN_REST(Map<String, String> head, InterMsgData interMsgData) throws EsbException {

		Log.getInstance().stdDebug("==================Z_SPMS_GETDN_REST================");
		// 针对此接口，SPMS端分REST MQ两种调用形式，SAP端不分，函数名为Z_SPMS_GETDN
		JCoFunction function = RfcManager.getInstance().getFunction("Z_SPMS_GETDN");
		function.getImportParameterList().setValue("I_VBELN", head.get("iVbeln"));
		String iStatus=head.get("iStatus");
		if (StringUtils.isEmpty(iStatus) || "null".equals(iStatus) || "NULL".equals(iStatus)) {
			iStatus = "";
		}
		function.getImportParameterList().setValue("I_STATUS", iStatus);

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, Object> result = new HashMap<String, Object>();
		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator, "Z_SPMS_GETDN_REST");
		}

		for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator, "Z_SPMS_GETDN_REST");
		}

		return result;
	}

	public Map<String, Object> Z_SPMS_GETSKUDATA(Map<String, String> head, InterMsgData interMsgData) throws EsbException {

		Log.getInstance().stdDebug("==================Z_SPMS_GETSKUDATA================");
		
		Map<String, Object> result = new HashMap<String, Object>();
		
		// Z_SPMS_GETSKUDATA增加REST调用形式，日期必须，否则全量了
		JCoFunction function = RfcManager.getInstance().getFunction("Z_SPMS_GETSKUDATA");
		if (head == null || StringUtils.isEmpty(head.get("iDate"))) {
			result.put("O_TYPE", "E");
			result.put("O_MSTXT", "iDate is empty.");
			result.put("DATA", "");
			return result;
		}
		function.getImportParameterList().setValue("I_DATE", head.get("iDate"));

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		
		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator, "Z_SPMS_GETSKUDATA");
		}

		HashMap<String, List<String[]>> functionDate = getFunctionDate(function, "Z_SPMS_GETSKUDATA");
		result.put("DATA", functionDate);

		return result;
	}
	
	public Map<String, Object> Z_SPMS_CANCEL(Map<String, String> head, InterMsgData interMsgData) throws EsbException {

		Log.getInstance().stdDebug("==================Z_SPMS_CANCEL================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_SPMS_CANCEL");
		function.getImportParameterList().setValue("I_MBLNR", head.get("iMblnr"));
		function.getImportParameterList().setValue("I_MJAHR", head.get("iMjahr"));
		function.getImportParameterList().setValue("I_ZEILE", head.get("iZeile"));
		function.getImportParameterList().setValue("I_BUDAT", head.get("iBudat"));

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, Object> result = new HashMap<String, Object>();
		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator, "Z_SPMS_CANCEL");
		}

		return result;
	}
	
	public Map<String, Object> Z_SPMS_GETHWDN(Map<String, String> head, List<Map<String, String>> items, InterMsgData interMsgData) throws EsbException {
		
		Log.getInstance().stdDebug("==================Z_SPMS_GETHWDN================");
		JCoFunction function =  RfcManager.getInstance().getFunction("Z_SPMS_GETHWDN");

		function.getImportParameterList().setValue("I_VBELN", head.get("iVbeln"));
        function.getImportParameterList().setValue("I_DATES", head.get("iDates"));
        function.getImportParameterList().setValue("I_DATEE", head.get("iDatee"));
        function.getImportParameterList().setValue("I_WERKS", head.get("iWerks"));
        function.getImportParameterList().setValue("I_EBELN", head.get("iEbeln"));
        function.getImportParameterList().setValue("I_FLAG", head.get("iFlag"));


        sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

        RfcManager.getInstance().execute(function);

        long endTime = System.currentTimeMillis();
        int time = (int) (endTime - startTime);
        sendResLog(interMsgData, "SAP", "ESB", time, function);
        
        Map<String, Object> result = new HashMap<String, Object>();
        
        for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext(); ) {
        	result = traversalTable(result, iterator, "Z_SPMS_GETHWDN");
        }
        
        for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext(); ) {
            result = traversalField(result, iterator);
        }
        return result;
    }

	
	public Map<String, Object> Z_SPMS_READ_DATA(Map<String, String> head, List<Map<String, String>> items, InterMsgData interMsgData) throws EsbException {
		
		Log.getInstance().stdDebug("==================Z_SPMS_READ_DATA================");
		JCoFunction function =  RfcManager.getInstance().getFunction("Z_SPMS_READ_DATA");

		function.getImportParameterList().setValue("I_XDNUM", head.get("iXdnum"));

        sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

        long endTime = System.currentTimeMillis();
        int time = (int) (endTime - startTime);
        sendResLog(interMsgData, "SAP", "ESB", time, function);

        Map<String, Object> result = new HashMap<String, Object>();
        
		for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator, "Z_SPMS_READ_DATA");
		}

		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
			result = traversalField(result, iterator);
		}
        return result;
    }

	public Map<String, Object> Z_ESB_CON_CHECK_REST() {

		Log.getInstance().stdDebug("==================Z_ESB_CON_CHECK_REST================");

		Map<String, Object> result = new HashMap<String, Object>();
		result.put("resCode", "0");
		return result;
	}
	
	public Map<String, Object> Z_SPMS_SKU_CHECK(Map<String, String> head, List<Map<String, String>> items, InterMsgData interMsgData) throws EsbException {
		
		Log.getInstance().stdDebug("==================Z_SPMS_SKU_CHECK================");
		JCoFunction function =  RfcManager.getInstance().getFunction("Z_SPMS_SKU_CHECK");

		JCoStructure st = (JCoStructure) function.getImportParameterList().getValue("I_HEADER");
        st.setValue("BLDAT", head.get("bldat"));
        st.setValue("BUDAT", head.get("budat"));
        st.setValue("BWART", head.get("bwart"));
        st.setValue("WERKS", head.get("werks"));
        st.setValue("LGORT", head.get("lgort"));
        st.setValue("BKTXT", head.get("bktxt"));
        
        JCoTable ptItem = function.getTableParameterList().getTable("IT_ITME");
        for (int i = 0; i < items.size(); i++) {
            ptItem.appendRow();
            ptItem.setRow(i);
            ptItem.setValue("ZEILE", items.get(i).get("zeile"));
            ptItem.setValue("MATNR", items.get(i).get("matnr"));
            ptItem.setValue("MENGE", items.get(i).get("menge"));
            ptItem.setValue("MEINS", items.get(i).get("meins"));
            ptItem.setValue("LIFNR", items.get(i).get("lifnr"));
            ptItem.setValue("KOSTL", items.get(i).get("kostl"));
            ptItem.setValue("SGTXT", items.get(i).get("sgtxt"));
            ptItem.setValue("WERKS", items.get(i).get("werks"));
            ptItem.setValue("LGORT", items.get(i).get("lgort"));
        }

        sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

        long endTime = System.currentTimeMillis();
        int time = (int) (endTime - startTime);
        sendResLog(interMsgData, "SAP", "ESB", time, function);

        Map<String, Object> result = new HashMap<String, Object>();
        JCoTable jCoTable = function.getTableParameterList().getTable("IT_RETURN");
        StringBuilder stringBuilder = new StringBuilder();
        for (int i = 0; i < jCoTable.getNumRows(); i++) {
            jCoTable.setRow(i);
            for (JCoField fld : jCoTable) {
                stringBuilder.append(String.format("%s\t", fld.getValue()));
            }
        }
        result.put("IT_RETURN", stringBuilder.toString().replaceAll("\r|\n|\t", "-"));
        for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext(); ) {
            result = traversalField(result, iterator);
        }
        return result;
    }
	
	public Map<String, Object> Z_SPMS_CHECK_PL(Map<String, String> head, InterMsgData interMsgData) throws EsbException {
		
		Log.getInstance().stdDebug("==================Z_SPMS_CHECK_PL================");
		JCoFunction function =  RfcManager.getInstance().getFunction("Z_SPMS_CHECK_PL");

		function.getImportParameterList().setValue("I_XDNUM", head.get("iXdnum"));

        sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();
        
        RfcManager.getInstance().execute(function);
        
        long endTime = System.currentTimeMillis();
        int time = (int) (endTime - startTime);
        sendResLog(interMsgData, "SAP", "ESB", time, function);
        
        Map<String, Object> result = new HashMap<String, Object>();
        for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext(); ) {
            result = traversalField(result, iterator);
        }
		for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator);
		}
        return result;
    }
	
	public Map<String, Object> Z_ZXD_DEL(Map<String, String> head, InterMsgData interMsgData) throws EsbException {

		Log.getInstance().stdDebug("==================Z_ZXD_DEL================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_ZXD_DEL");

		function.getImportParameterList().setValue("IM_XDNUM", head.get("imXdnum"));
		function.getImportParameterList().setValue("IM_ZSPMS_TASKID", head.get("imZspmsTaskid"));
		function.getImportParameterList().setValue("IM_IMPORT_FLAG", head.get("imImportFlag"));

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, Object> result = new HashMap<String, Object>();
		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
			result = traversalField(result, iterator);
		}

		return result;
	}
	
	public Map<String, Object> Z_SPMS_CREATE_STO(Map<String, String> head, Map<String, List<Map<String, String>>> itemMap, InterMsgData interMsgData) throws EsbException {
		
		Log.getInstance().stdDebug("==================Z_SPMS_CREATE_STO================");
		JCoFunction function =  RfcManager.getInstance().getFunction("Z_SPMS_CREATE_STO");

		JCoStructure st = (JCoStructure) function.getImportParameterList().getValue("I_HEADER");
        st.setValue("AEDAT", head.get("aedat"));
        st.setValue("WERKS", head.get("werks"));
        st.setValue("EKGRP", head.get("ekgrp"));
        st.setValue("BSART", head.get("bsart"));
        st.setValue("ZTERM", head.get("zterm"));
        st.setValue("BUKRS", head.get("bukrs"));
        st.setValue("EKORG", head.get("ekorg"));
        st.setValue("EBELN", head.get("ebeln"));        
        st.setValue("ZSTRING", head.get("zstring"));
        
		if (itemMap.get("IT_POITEM") != null) {
			JCoTable tblPoitem = function.getTableParameterList().getTable("IT_POITEM");
			List<Map<String, String>> listPoitem = itemMap.get("IT_POITEM");
			for (int i = 0; i < listPoitem.size(); i++) {
				tblPoitem.appendRow();
				tblPoitem.setRow(i);
				tblPoitem.setValue("EBELP", listPoitem.get(i).get("ebelp"));
				tblPoitem.setValue("EINDT", listPoitem.get(i).get("eindt"));
				tblPoitem.setValue("WERKS", listPoitem.get(i).get("werks"));
				tblPoitem.setValue("LGORT", listPoitem.get(i).get("lgort"));
				tblPoitem.setValue("MATNR", listPoitem.get(i).get("matnr"));
				tblPoitem.setValue("MENGE", listPoitem.get(i).get("menge"));
				tblPoitem.setValue("MEINS", listPoitem.get(i).get("meins"));
				tblPoitem.setValue("LOEKZ", listPoitem.get(i).get("loekz"));
				tblPoitem.setValue("UEBTO", listPoitem.get(i).get("uebto"));
				tblPoitem.setValue("WEBRE", listPoitem.get(i).get("webre"));
				tblPoitem.setValue("LFART", listPoitem.get(i).get("lfart"));
				tblPoitem.setValue("VSBED", listPoitem.get(i).get("vsbed"));
				tblPoitem.setValue("LADGR", listPoitem.get(i).get("ladgr"));
				tblPoitem.setValue("TRAGR", listPoitem.get(i).get("tragr"));
				tblPoitem.setValue("VKORG", listPoitem.get(i).get("vkorg"));
				tblPoitem.setValue("VTWEG", listPoitem.get(i).get("vtweg"));
				tblPoitem.setValue("SPART", listPoitem.get(i).get("spart"));
				tblPoitem.setValue("KUNNR", listPoitem.get(i).get("kunnr"));

				tblPoitem.setValue("WHS_DUMP_NO", listPoitem.get(i).get("whs_dump_no"));
				tblPoitem.setValue("PACKAGE_CODE", listPoitem.get(i).get("package_code"));
				tblPoitem.setValue("SERIAL", listPoitem.get(i).get("serial"));
			}
		}

		if (itemMap.get("IT_XHEAD") != null) {
			JCoTable tblXhead = function.getTableParameterList().getTable("IT_XHEAD");
			List<Map<String, String>> listXhead = itemMap.get("IT_XHEAD");
			for (int i = 0; i < listXhead.size(); i++) {
				tblXhead.appendRow();
				tblXhead.setRow(i);
				tblXhead.setValue("PACKAGE_CODE", listXhead.get(i).get("packageCode"));
				tblXhead.setValue("FLG_YFP", listXhead.get(i).get("flgYfp"));
			}
		}

        sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();
        
        RfcManager.getInstance().execute(function);
        
        long endTime = System.currentTimeMillis();
        int time = (int) (endTime - startTime);
        sendResLog(interMsgData, "SAP", "ESB", time, function);
        
        Map<String, Object> result = new HashMap<String, Object>();
        for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext(); ) {
            result = traversalField(result, iterator);
        }
        return result;
    }
	
	public Map<String, Object> Z_SPMS_CHANGE_STO(Map<String, String> head, Map<String, List<Map<String, String>>> itemMap, InterMsgData interMsgData) throws EsbException {
		
		Log.getInstance().stdDebug("==================Z_SPMS_CHANGE_STO================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_SPMS_CHANGE_STO");

		JCoStructure st = (JCoStructure) function.getImportParameterList().getValue("I_HEADER");
		st.setValue("AEDAT", head.get("aedat"));
		st.setValue("WERKS", head.get("werks"));
		st.setValue("EKGRP", head.get("ekgrp"));
		st.setValue("BSART", head.get("bsart"));
		st.setValue("ZTERM", head.get("zterm"));
		st.setValue("BUKRS", head.get("bukrs"));
		st.setValue("EKORG", head.get("ekorg"));
		st.setValue("EBELN", head.get("ebeln"));
		st.setValue("ZSTRING", head.get("zstring"));

		if (itemMap.get("IT_POITEM") != null) {
			JCoTable tblPoitem = function.getTableParameterList().getTable("IT_POITEM");
			List<Map<String, String>> listPoitem = itemMap.get("IT_POITEM");
			for (int i = 0; i < listPoitem.size(); i++) {
				tblPoitem.appendRow();
				tblPoitem.setRow(i);
				tblPoitem.setValue("EBELP", listPoitem.get(i).get("ebelp"));
				tblPoitem.setValue("EINDT", listPoitem.get(i).get("eindt"));
				tblPoitem.setValue("WERKS", listPoitem.get(i).get("werks"));
				tblPoitem.setValue("LGORT", listPoitem.get(i).get("lgort"));
				tblPoitem.setValue("MATNR", listPoitem.get(i).get("matnr"));
				tblPoitem.setValue("MENGE", listPoitem.get(i).get("menge"));
				tblPoitem.setValue("MEINS", listPoitem.get(i).get("meins"));
				tblPoitem.setValue("LOEKZ", listPoitem.get(i).get("loekz"));
				tblPoitem.setValue("UEBTO", listPoitem.get(i).get("uebto"));
				tblPoitem.setValue("WEBRE", listPoitem.get(i).get("webre"));
				tblPoitem.setValue("LFART", listPoitem.get(i).get("lfart"));
				tblPoitem.setValue("VSBED", listPoitem.get(i).get("vsbed"));
				tblPoitem.setValue("LADGR", listPoitem.get(i).get("ladgr"));
				tblPoitem.setValue("TRAGR", listPoitem.get(i).get("tragr"));
				tblPoitem.setValue("VKORG", listPoitem.get(i).get("vkorg"));
				tblPoitem.setValue("VTWEG", listPoitem.get(i).get("vtweg"));
				tblPoitem.setValue("SPART", listPoitem.get(i).get("spart"));
				tblPoitem.setValue("KUNNR", listPoitem.get(i).get("kunnr"));

			}
		}

		if (itemMap.get("IT_XHEAD") != null) {
			JCoTable tblXhead = function.getTableParameterList().getTable("IT_XHEAD");
			List<Map<String, String>> listXhead = itemMap.get("IT_XHEAD");
			for (int i = 0; i < listXhead.size(); i++) {
				tblXhead.appendRow();
				tblXhead.setRow(i);
				tblXhead.setValue("PACKAGE_CODE", listXhead.get(i).get("packageCode"));
				tblXhead.setValue("FLG_YFP", listXhead.get(i).get("flgYfp"));
			}
		}

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, Object> result = new HashMap<String, Object>();
		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
			result = traversalField(result, iterator);
		}
		return result;
    }
	
	public Map<String, Object> Z_SPMS_DN_POST(Map<String, String> head, InterMsgData interMsgData) throws EsbException {

		Log.getInstance().stdDebug("==================Z_SPMS_DN_POST================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_SPMS_DN_POST");

		function.getImportParameterList().setValue("I_FLAG", head.get("iFlag"));
		function.getImportParameterList().setValue("I_BUDAT", head.get("iBudat"));
		function.getImportParameterList().setValue("I_EBELN", head.get("iEbeln"));
		function.getImportParameterList().setValue("I_VBELN", head.get("iVbeln"));
		function.getImportParameterList().setValue("I_LGORT", head.get("iLgort"));

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, Object> result = new HashMap<String, Object>();
		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
			result = traversalField(result, iterator);
		}

		return result;
	}
	
	public Map<String, Object> Z_SPMS_HWCSH(Map<String, String> head, InterMsgData interMsgData) throws EsbException {

		Log.getInstance().stdDebug("==================Z_SPMS_HWCSH================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_SPMS_HWCSH");

		function.getImportParameterList().setValue("I_CURRDATE", head.get("iCurrdate"));
		function.getImportParameterList().setValue("I_VBELN", head.get("iVbeln"));
		function.getImportParameterList().setValue("I_XDNUM", head.get("iXdnum"));
		function.getImportParameterList().setValue("I_TRAID", head.get("iTraid"));
		function.getImportParameterList().setValue("I_SYNC_DATE", head.get("iSyncDate"));

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, Object> result = new HashMap<String, Object>();
		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
			result = traversalField(result, iterator);
		}

		String[] outTblArr = { "IT_LIKP", "IT_LIPS", "IT_ZXDTKO", "IT_DNZXD", "IT_ZXDTPO" };
		List<String> outTblList = Arrays.asList(outTblArr);
		for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator, outTblList);
		}

		return result;
	}


	public Map<String, Object> Z_SPMS_SAVE_XHEAD(List<Map<String, String>> items, InterMsgData interMsgData) throws EsbException {

		Log.getInstance().stdDebug("==================Z_SPMS_SAVE_XHEAD================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_SPMS_SAVE_XHEAD");

		JCoTable tbltem = function.getTableParameterList().getTable("IT_TAB");
		for (int i = 0; i < items.size(); i++) {
			tbltem.appendRow();
			tbltem.setRow(i);
			tbltem.setValue("PACKAGE_NO", items.get(i).get("PACKAGE_NO"));
			tbltem.setValue("PACKAGE_CODE", items.get(i).get("PACKAGE_CODE"));
			tbltem.setValue("WERKS_CF", items.get(i).get("WERKS_CF"));
			tbltem.setValue("LGORT_CF", items.get(i).get("LGORT_CF"));
			tbltem.setValue("INBOUND_TIME", items.get(i).get("INBOUND_TIME"));
			tbltem.setValue("OUTBOUND_TIME", items.get(i).get("OUTBOUND_TIME"));
			tbltem.setValue("OVERSEAS_TIME", items.get(i).get("OVERSEAS_TIME"));
			tbltem.setValue("STATSU_PKCODE", items.get(i).get("STATSU_PKCODE"));
		}

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, Object> result = new HashMap<String, Object>();
		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
			result = traversalField(result, iterator);
		}

		return result;
	}
	
	private Map<String, Object> Z_MDM_MATER_PUSH(String message, InterMsgData interMsgData) throws EsbException {

		Log.getInstance().stdDebug("==================Z_MDM_MATER_PUSH================");

		String esbRestUrl = (String) cm.get("ESB_REST_URL")+"/mdmMaterPush";
		Log.getInstance().stdDebug("Z_MDM_MATER_PUSH esbRestUrl=" + esbRestUrl);
		
		RestTemplate restTemplate = new RestTemplate();
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);

		HttpEntity<String> requestEntity = new HttpEntity<String>(message, headers);

		List<HttpMessageConverter<?>> httpMessageConverters = restTemplate.getMessageConverters();
		httpMessageConverters.stream().forEach(httpMessageConverter -> {
			if (httpMessageConverter instanceof StringHttpMessageConverter) {
				StringHttpMessageConverter messageConverter = (StringHttpMessageConverter) httpMessageConverter;
				messageConverter.DEFAULT_CHARSET.forName("UTF-8");
			}
		});
		String restRes = restTemplate.postForObject(esbRestUrl, requestEntity, String.class);
		Log.getInstance().stdDebug("Z_MDM_MATER_PUSH restRes=" + restRes);
		
		Map restResMap = JSON.parseObject(restRes, Map.class);

		Map<String, Object> result = new HashMap<String, Object>();
		result.put("TYPE", restResMap.get("resCode"));
		result.put("MESSAGE", restResMap.get("resMsg"));
		return result;
	}
	
	private Map<String, Object> Z_MDM_CUSTOMER_PUSH(String message, InterMsgData interMsgData) throws EsbException {

		Log.getInstance().stdDebug("==================Z_MDM_CUSTOMER_PUSH================");

		String esbRestUrl = (String) cm.get("ESB_REST_URL")+"/mdmCustomerPush";
		Log.getInstance().stdDebug("Z_MDM_CUSTOMER_PUSH message=" + message);
		
		RestTemplate restTemplate = new RestTemplate();
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		HttpEntity<String> requestEntity = new HttpEntity<String>(message, headers);

		List<HttpMessageConverter<?>> httpMessageConverters = restTemplate.getMessageConverters();
		httpMessageConverters.stream().forEach(httpMessageConverter -> {
			if (httpMessageConverter instanceof StringHttpMessageConverter) {
				StringHttpMessageConverter messageConverter = (StringHttpMessageConverter) httpMessageConverter;
				messageConverter.DEFAULT_CHARSET.forName("UTF-8");
			}
		});
		String restRes = restTemplate.postForObject(esbRestUrl, requestEntity, String.class);
		Log.getInstance().stdDebug("Z_MDM_CUSTOMER_PUSH restRes=" + restRes);
		
		Map restResMap = JSON.parseObject(restRes, Map.class);

		Map<String, Object> result = new HashMap<String, Object>();
		
		result.put("TYPE", restResMap.get("resCode"));
		result.put("MESSAGE", restResMap.get("resMsg"));
		return result;
	}
	
	private Map<String, Object> Z_MDM_SUPPLIER_PUSH(String message, InterMsgData interMsgData) throws EsbException {

		Log.getInstance().stdDebug("==================Z_MDM_SUPPLIER_PUSH================");

		String esbRestUrl = (String) cm.get("ESB_REST_URL") + "/mdmSupplierPush";
		Log.getInstance().stdDebug("Z_MDM_SUPPLIER_PUSH esbRestUrl=" + esbRestUrl);
		
		RestTemplate restTemplate = new RestTemplate();
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		HttpEntity<String> requestEntity = new HttpEntity<String>(message, headers);

		List<HttpMessageConverter<?>> httpMessageConverters = restTemplate.getMessageConverters();
		httpMessageConverters.stream().forEach(httpMessageConverter -> {
			if (httpMessageConverter instanceof StringHttpMessageConverter) {
				StringHttpMessageConverter messageConverter = (StringHttpMessageConverter) httpMessageConverter;
				messageConverter.DEFAULT_CHARSET.forName("UTF-8");
			}
		});
		String restRes = restTemplate.postForObject(esbRestUrl, requestEntity, String.class);
		Log.getInstance().stdDebug("Z_MDM_SUPPLIER_PUSH restRes=" + restRes);

		Map restResMap = JSON.parseObject(restRes, Map.class);

		Map<String, Object> result = new HashMap<String, Object>();

		result.put("TYPE", restResMap.get("resCode"));
		result.put("MESSAGE", restResMap.get("resMsg"));
		return result;
	}
	
	public Map<String, Object> Z_MDM_CREATE_SKU(Map<String, List<Map<String, String>>> itemMap, InterMsgData interMsgData) throws EsbException {

		Log.getInstance().stdDebug("==================Z_MDM_CREATE_SKU================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_MDM_CREATE_SKU");

		JCoTable jbstTbl = function.getTableParameterList().getTable("IT_JBST");
		List<Map<String, String>> jbstItems = itemMap.get("IT_JBST");
		for (int i = 0; i < jbstItems.size(); i++) {
			Map<String, String> item = jbstItems.get(i);
			jbstTbl.appendRow();
			jbstTbl.setRow(i);

			jbstTbl.setValue("MTART", item.get("MTART"));
			jbstTbl.setValue("MBRSH", item.get("MBRSH"));
			jbstTbl.setValue("MATNR", item.get("MATNR"));
			jbstTbl.setValue("BISMT", item.get("BISMT"));
			jbstTbl.setValue("MAKTX", item.get("MAKTX"));
			jbstTbl.setValue("MAKTX01", item.get("MAKTX01"));
			jbstTbl.setValue("MAKTX02", item.get("MAKTX02"));
			jbstTbl.setValue("MAKTX03", item.get("MAKTX03"));
			jbstTbl.setValue("PRDHA", item.get("PRDHA"));
			jbstTbl.setValue("MATKL", item.get("MATKL"));
			jbstTbl.setValue("MEINS", item.get("MEINS"));
			jbstTbl.setValue("VOLEH", item.get("VOLEH"));
			jbstTbl.setValue("VOLUM", item.get("VOLUM"));
			jbstTbl.setValue("GEWEI", item.get("GEWEI"));
			jbstTbl.setValue("NTGEW", item.get("NTGEW"));
			jbstTbl.setValue("BRGEW", item.get("BRGEW"));
			jbstTbl.setValue("SPART", item.get("SPART"));
			jbstTbl.setValue("EXTWG", item.get("EXTWG"));
			jbstTbl.setValue("GROES", item.get("GROES"));
			jbstTbl.setValue("XCHPF", item.get("XCHPF"));
			jbstTbl.setValue("XGCHP", item.get("XGCHP"));
			jbstTbl.setValue("TRAGR", item.get("TRAGR"));
			jbstTbl.setValue("GROUPS", item.get("GROUPS"));
			jbstTbl.setValue("SMALL_GROUPS", item.get("SMALL_GROUPS"));
			jbstTbl.setValue("WLMX", item.get("WLMX"));
			jbstTbl.setValue("WLMX_EN", item.get("WLMX_EN"));
			jbstTbl.setValue("PRODUCT_TYPE", item.get("PRODUCT_TYPE"));
			jbstTbl.setValue("PRODUCT_PAC_SPEC", item.get("PRODUCT_PAC_SPEC"));
			jbstTbl.setValue("PRODUCT_PACKAGING", item.get("PRODUCT_PACKAGING"));
			jbstTbl.setValue("PRODUCT_MATERIAL", item.get("PRODUCT_MATERIAL"));
			jbstTbl.setValue("PRODUCT_ORIGIN", item.get("PRODUCT_ORIGIN"));
			jbstTbl.setValue("PRODUCT_STYLE", item.get("PRODUCT_STYLE"));
			jbstTbl.setValue("PRODUCT_CONTENT", item.get("PRODUCT_CONTENT"));
			jbstTbl.setValue("PRODUCT_PROCESS", item.get("PRODUCT_PROCESS"));
			jbstTbl.setValue("PRODUCT_TOLERANCE", item.get("PRODUCT_TOLERANCE"));
			jbstTbl.setValue("PRODUCT_FUNCTION", item.get("PRODUCT_FUNCTION"));
			jbstTbl.setValue("PRODUCT_SUPPLIER", item.get("PRODUCT_SUPPLIER"));
			jbstTbl.setValue("PRODUCT_FRAGRANCE", item.get("PRODUCT_FRAGRANCE"));
			jbstTbl.setValue("SALES_PLACE", item.get("SALES_PLACE"));
			jbstTbl.setValue("PRODUCT_MODEL", item.get("PRODUCT_MODEL"));
			jbstTbl.setValue("PRODUCT_COLOR", item.get("PRODUCT_COLOR"));
			jbstTbl.setValue("PRODUCT_STANDARD", item.get("PRODUCT_STANDARD"));
			jbstTbl.setValue("RATED_VOLTAGE", item.get("RATED_VOLTAGE"));
			jbstTbl.setValue("RATED_POWER", item.get("RATED_POWER"));
			jbstTbl.setValue("NATIONAL_LOCAL", item.get("NATIONAL_LOCAL"));
			jbstTbl.setValue("PRODUCT_NAME", item.get("PRODUCT_NAME"));
			jbstTbl.setValue("CONTROL_POST", item.get("CONTROL_POST"));
			jbstTbl.setValue("DATA_OWNER", item.get("DATA_OWNER"));
			jbstTbl.setValue("BIG_CATEGORY", item.get("BIG_CATEGORY"));
			jbstTbl.setValue("MIDDLE_CATEGORY", item.get("MIDDLE_CATEGORY"));
			jbstTbl.setValue("SMALL_CATEGORY", item.get("SMALL_CATEGORY"));
			jbstTbl.setValue("SC_WERKS", item.get("SC_WERKS"));
		}

		JCoTable cgstTbl = function.getTableParameterList().getTable("IT_CGST");
		List<Map<String, String>> cgstItems = itemMap.get("IT_CGST");
		for (int i = 0; i < cgstItems.size(); i++) {
			Map<String, String> item = cgstItems.get(i);
			cgstTbl.appendRow();
			cgstTbl.setRow(i);

			cgstTbl.setValue("WERKS", item.get("WERKS"));
			cgstTbl.setValue("EKGRP", item.get("EKGRP"));
			cgstTbl.setValue("BSTME", item.get("BSTME"));
			cgstTbl.setValue("EKWSL", item.get("EKWSL"));
			cgstTbl.setValue("VABME", item.get("VABME"));
			cgstTbl.setValue("LADGR", item.get("LADGR"));
			cgstTbl.setValue("CG_NTGEW", item.get("CG_NTGEW"));
			cgstTbl.setValue("CG_GEWEI", item.get("CG_GEWEI"));
			cgstTbl.setValue("CG_BRGEW", item.get("CG_BRGEW"));
			cgstTbl.setValue("CG_VOLEH", item.get("CG_VOLEH"));
			cgstTbl.setValue("CG_VOLUM", item.get("CG_VOLUM"));
			cgstTbl.setValue("ZD_BSTME", item.get("ZD_BSTME"));
			cgstTbl.setValue("ZD_NTGEW", item.get("ZD_NTGEW"));
			cgstTbl.setValue("ZD_GEWEI", item.get("ZD_GEWEI"));
			cgstTbl.setValue("ZD_BRGEW", item.get("ZD_BRGEW"));
			cgstTbl.setValue("ZD_VOLEH", item.get("ZD_VOLEH"));
			cgstTbl.setValue("ZD_VOLUM", item.get("ZD_VOLUM"));
		}

		JCoTable mrpstTbl = function.getTableParameterList().getTable("IT_MRPST");
		List<Map<String, String>> mrpstItems = itemMap.get("IT_MRPST");
		for (int i = 0; i < mrpstItems.size(); i++) {
			Map<String, String> item = mrpstItems.get(i);
			mrpstTbl.appendRow();
			mrpstTbl.setRow(i);

			mrpstTbl.setValue("WERKS", item.get("WERKS"));
			mrpstTbl.setValue("BESKZ", item.get("BESKZ"));
			mrpstTbl.setValue("DISMM", item.get("DISMM"));
			mrpstTbl.setValue("DISGR", item.get("DISGR"));
			mrpstTbl.setValue("DISPO", item.get("DISPO"));
			mrpstTbl.setValue("EISBE", item.get("EISBE"));
			mrpstTbl.setValue("DZEIT", item.get("DZEIT"));
			mrpstTbl.setValue("AUSME", item.get("AUSME"));
			mrpstTbl.setValue("RGEKZ", item.get("RGEKZ"));
			mrpstTbl.setValue("XMCNG", item.get("XMCNG"));
			mrpstTbl.setValue("UEETO", item.get("UEETO"));
			mrpstTbl.setValue("INSMK", item.get("INSMK"));
			mrpstTbl.setValue("NFMAT", item.get("NFMAT"));
			mrpstTbl.setValue("FHORI", item.get("FHORI"));
			mrpstTbl.setValue("STRGR", item.get("STRGR"));
			mrpstTbl.setValue("PLIFZ", item.get("PLIFZ"));
			mrpstTbl.setValue("MTVFP", item.get("MTVFP"));
			mrpstTbl.setValue("DISLS", item.get("DISLS"));
			mrpstTbl.setValue("KZECH", item.get("KZECH"));
			mrpstTbl.setValue("BSTRF", item.get("BSTRF"));
			mrpstTbl.setValue("LGPRO", item.get("LGPRO"));
			mrpstTbl.setValue("FRTME", item.get("FRTME"));
			mrpstTbl.setValue("SFCPF", item.get("SFCPF"));
			mrpstTbl.setValue("AUSDT", item.get("AUSDT"));
			mrpstTbl.setValue("WEBAZ", item.get("WEBAZ"));
			mrpstTbl.setValue("SOBSL", item.get("SOBSL"));
			mrpstTbl.setValue("LGFSB", item.get("LGFSB"));
			mrpstTbl.setValue("ALTSL", item.get("ALTSL"));
			mrpstTbl.setValue("KZAUS", item.get("KZAUS"));
			mrpstTbl.setValue("BSTMI", item.get("BSTMI"));
			mrpstTbl.setValue("SC_NTGEW", item.get("SC_NTGEW"));
			mrpstTbl.setValue("SC_BRGEW", item.get("SC_BRGEW"));
			mrpstTbl.setValue("SC_VOLUM", item.get("SC_VOLUM"));
			mrpstTbl.setValue("SC_VOLEH", item.get("SC_VOLEH"));
			mrpstTbl.setValue("SC_GEWEI", item.get("SC_GEWEI"));
		}

		JCoTable bhstTbl = function.getTableParameterList().getTable("IT_BHST");
		List<Map<String, String>> bhstItems = itemMap.get("IT_BHST");
		for (int i = 0; i < bhstItems.size(); i++) {
			Map<String, String> item = bhstItems.get(i);
			bhstTbl.appendRow();
			bhstTbl.setRow(i);

			bhstTbl.setValue("FORMT", item.get("FORMT"));
		}

		JCoTable cwstTbl = function.getTableParameterList().getTable("IT_CWST");
		List<Map<String, String>> cwstItems = itemMap.get("IT_CWST");
		for (int i = 0; i < cwstItems.size(); i++) {
			Map<String, String> item = cwstItems.get(i);
			cwstTbl.appendRow();
			cwstTbl.setRow(i);

			cwstTbl.setValue("WERKS", item.get("WERKS"));
			cwstTbl.setValue("PEINH", item.get("PEINH"));
			cwstTbl.setValue("VPRSV", item.get("VPRSV"));
			cwstTbl.setValue("KOSGR", item.get("KOSGR"));
			cwstTbl.setValue("PRCTR", item.get("PRCTR"));
			cwstTbl.setValue("BKLAS", item.get("BKLAS"));
			cwstTbl.setValue("BWTTY", item.get("BWTTY"));
			cwstTbl.setValue("HKMAT", item.get("HKMAT"));
			cwstTbl.setValue("AWSLS", item.get("AWSLS"));
			cwstTbl.setValue("EKALR", item.get("EKALR"));
			cwstTbl.setValue("LOSGR", item.get("LOSGR"));
			cwstTbl.setValue("ML_ABST", item.get("ML_ABST"));
		}

		JCoTable gckcstTbl = function.getTableParameterList().getTable("IT_GCKCST");
		List<Map<String, String>> gckcstItems = itemMap.get("IT_GCKCST");
		for (int i = 0; i < gckcstItems.size(); i++) {
			Map<String, String> item = gckcstItems.get(i);
			gckcstTbl.appendRow();
			gckcstTbl.setRow(i);

			gckcstTbl.setValue("WERKS", item.get("WERKS"));
			gckcstTbl.setValue("IPRKZ", item.get("IPRKZ"));
			gckcstTbl.setValue("MHDRZ", item.get("MHDRZ"));
			gckcstTbl.setValue("LZEIH", item.get("LZEIH"));
			gckcstTbl.setValue("MHDHB", item.get("MHDHB"));
			gckcstTbl.setValue("MAXLZ", item.get("MAXLZ"));
			gckcstTbl.setValue("RDMHD", item.get("RDMHD"));
		}

		JCoTable dwhsTbl = function.getTableParameterList().getTable("IT_DWHS");
		List<Map<String, String>> dwhsItems = itemMap.get("IT_DWHS");
		for (int i = 0; i < dwhsItems.size(); i++) {
			Map<String, String> item = dwhsItems.get(i);
			dwhsTbl.appendRow();
			dwhsTbl.setRow(i);

			dwhsTbl.setValue("BSTME", item.get("BSTME"));
			dwhsTbl.setValue("UMREN", item.get("UMREN"));
			dwhsTbl.setValue("UMREZ", item.get("UMREZ"));
			dwhsTbl.setValue("MEINS", item.get("MEINS"));
		}

		JCoTable hgstTbl = function.getTableParameterList().getTable("IT_HGST");
		List<Map<String, String>> hgstItems = itemMap.get("IT_HGST");
		for (int i = 0; i < hgstItems.size(); i++) {
			Map<String, String> item = hgstItems.get(i);
			hgstTbl.appendRow();
			hgstTbl.setRow(i);

			hgstTbl.setValue("WERKS", item.get("WERKS"));
			hgstTbl.setValue("STAWN", item.get("STAWN"));
			hgstTbl.setValue("EXPME", item.get("EXPME"));
			hgstTbl.setValue("HERKL", item.get("HERKL"));
			hgstTbl.setValue("HG_GEWEI", item.get("HG_GEWEI"));
			hgstTbl.setValue("HG_NTGEW", item.get("HG_NTGEW"));
			hgstTbl.setValue("HG_BRGEW", item.get("HG_BRGEW"));
			hgstTbl.setValue("HG_VOLEH", item.get("HG_VOLEH"));
			hgstTbl.setValue("HG_VOLUM", item.get("HG_VOLUM"));

		}

		JCoTable xsstTbl = function.getTableParameterList().getTable("IT_XSST");
		List<Map<String, String>> xsstItems = itemMap.get("IT_XSST");
		for (int i = 0; i < xsstItems.size(); i++) {
			Map<String, String> item = xsstItems.get(i);
			xsstTbl.appendRow();
			xsstTbl.setRow(i);

			xsstTbl.setValue("WERKS", item.get("WERKS"));
			xsstTbl.setValue("VKORG", item.get("VKORG"));
			xsstTbl.setValue("KONDM", item.get("KONDM"));
			xsstTbl.setValue("VTWEG", item.get("VTWEG"));
			xsstTbl.setValue("KTGRM", item.get("KTGRM"));
			xsstTbl.setValue("TAXKM", item.get("TAXKM"));
			xsstTbl.setValue("TATYP", item.get("TATYP"));
			xsstTbl.setValue("VERSG", item.get("VERSG"));
			xsstTbl.setValue("MTPOS", item.get("MTPOS"));
			xsstTbl.setValue("VRKME", item.get("VRKME"));
			xsstTbl.setValue("XS_VOLEH", item.get("XS_VOLEH"));
			xsstTbl.setValue("XS_VOLUM", item.get("XS_VOLUM"));
			xsstTbl.setValue("XS_GEWEI", item.get("XS_GEWEI"));
			xsstTbl.setValue("XS_NTGEW", item.get("XS_NTGEW"));
			xsstTbl.setValue("XS_BRGEW", item.get("XS_BRGEW"));
		}

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, Object> result = new HashMap<String, Object>();

		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
			result = traversalField(result, iterator);
		}

		for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator);
		}

		return result;
	}
	
	public Map<String, Object> Z_MDM_CHANGE_SKU(Map<String, String> head, Map<String, List<Map<String, String>>> itemMap, InterMsgData interMsgData) throws EsbException {

		Log.getInstance().stdDebug("==================Z_MDM_CHANGE_SKU================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_MDM_CHANGE_SKU");

		JCoStructure st = (JCoStructure) function.getImportParameterList().getValue("IP_MATNR");
		st.setValue("MATNR", head.get("MATNR"));
		st.setValue("MTART", head.get("MTART"));
		st.setValue("MBRSH", head.get("MBRSH"));

		JCoTable jbstTbl = function.getTableParameterList().getTable("IT_JBST");
		List<Map<String, String>> jbstItems = itemMap.get("IT_JBST");
		for (int i = 0; i < jbstItems.size(); i++) {
			Map<String, String> item = jbstItems.get(i);
			jbstTbl.appendRow();
			jbstTbl.setRow(i);

			jbstTbl.setValue("MTART", item.get("MTART"));
			jbstTbl.setValue("MBRSH", item.get("MBRSH"));
			jbstTbl.setValue("MATNR", item.get("MATNR"));
			jbstTbl.setValue("BISMT", item.get("BISMT"));
			jbstTbl.setValue("MAKTX", item.get("MAKTX"));
			jbstTbl.setValue("MAKTX01", item.get("MAKTX01"));
			jbstTbl.setValue("MAKTX02", item.get("MAKTX02"));
			jbstTbl.setValue("MAKTX03", item.get("MAKTX03"));
			jbstTbl.setValue("PRDHA", item.get("PRDHA"));
			jbstTbl.setValue("MATKL", item.get("MATKL"));
			jbstTbl.setValue("MEINS", item.get("MEINS"));
			jbstTbl.setValue("VOLEH", item.get("VOLEH"));
			jbstTbl.setValue("VOLUM", item.get("VOLUM"));
			jbstTbl.setValue("GEWEI", item.get("GEWEI"));
			jbstTbl.setValue("NTGEW", item.get("NTGEW"));
			jbstTbl.setValue("BRGEW", item.get("BRGEW"));
			jbstTbl.setValue("SPART", item.get("SPART"));
			jbstTbl.setValue("EXTWG", item.get("EXTWG"));
			jbstTbl.setValue("GROES", item.get("GROES"));
			jbstTbl.setValue("XCHPF", item.get("XCHPF"));
			jbstTbl.setValue("XGCHP", item.get("XGCHP"));
			jbstTbl.setValue("TRAGR", item.get("TRAGR"));
			jbstTbl.setValue("GROUPS", item.get("GROUPS"));
			jbstTbl.setValue("SMALL_GROUPS", item.get("SMALL_GROUPS"));
			jbstTbl.setValue("WLMX", item.get("WLMX"));
			jbstTbl.setValue("WLMX_EN", item.get("WLMX_EN"));
			jbstTbl.setValue("PRODUCT_TYPE", item.get("PRODUCT_TYPE"));
			jbstTbl.setValue("PRODUCT_PAC_SPEC", item.get("PRODUCT_PAC_SPEC"));
			jbstTbl.setValue("PRODUCT_PACKAGING", item.get("PRODUCT_PACKAGING"));
			jbstTbl.setValue("PRODUCT_MATERIAL", item.get("PRODUCT_MATERIAL"));
			jbstTbl.setValue("PRODUCT_ORIGIN", item.get("PRODUCT_ORIGIN"));
			jbstTbl.setValue("PRODUCT_STYLE", item.get("PRODUCT_STYLE"));
			jbstTbl.setValue("PRODUCT_CONTENT", item.get("PRODUCT_CONTENT"));
			jbstTbl.setValue("PRODUCT_PROCESS", item.get("PRODUCT_PROCESS"));
			jbstTbl.setValue("PRODUCT_TOLERANCE", item.get("PRODUCT_TOLERANCE"));
			jbstTbl.setValue("PRODUCT_FUNCTION", item.get("PRODUCT_FUNCTION"));
			jbstTbl.setValue("PRODUCT_SUPPLIER", item.get("PRODUCT_SUPPLIER"));
			jbstTbl.setValue("PRODUCT_FRAGRANCE", item.get("PRODUCT_FRAGRANCE"));
			jbstTbl.setValue("SALES_PLACE", item.get("SALES_PLACE"));
			jbstTbl.setValue("PRODUCT_MODEL", item.get("PRODUCT_MODEL"));
			jbstTbl.setValue("PRODUCT_COLOR", item.get("PRODUCT_COLOR"));
			jbstTbl.setValue("PRODUCT_STANDARD", item.get("PRODUCT_STANDARD"));
			jbstTbl.setValue("RATED_VOLTAGE", item.get("RATED_VOLTAGE"));
			jbstTbl.setValue("RATED_POWER", item.get("RATED_POWER"));
			jbstTbl.setValue("NATIONAL_LOCAL", item.get("NATIONAL_LOCAL"));
			jbstTbl.setValue("PRODUCT_NAME", item.get("PRODUCT_NAME"));
			jbstTbl.setValue("CONTROL_POST", item.get("CONTROL_POST"));
			jbstTbl.setValue("DATA_OWNER", item.get("DATA_OWNER"));
			jbstTbl.setValue("BIG_CATEGORY", item.get("BIG_CATEGORY"));
			jbstTbl.setValue("MIDDLE_CATEGORY", item.get("MIDDLE_CATEGORY"));
			jbstTbl.setValue("SMALL_CATEGORY", item.get("SMALL_CATEGORY"));
			jbstTbl.setValue("SC_WERKS", item.get("SC_WERKS"));
		}

		JCoTable cgstTbl = function.getTableParameterList().getTable("IT_CGST");
		List<Map<String, String>> cgstItems = itemMap.get("IT_CGST");
		for (int i = 0; i < cgstItems.size(); i++) {
			Map<String, String> item = cgstItems.get(i);
			cgstTbl.appendRow();
			cgstTbl.setRow(i);

			cgstTbl.setValue("WERKS", item.get("WERKS"));
			cgstTbl.setValue("EKGRP", item.get("EKGRP"));
			cgstTbl.setValue("BSTME", item.get("BSTME"));
			cgstTbl.setValue("EKWSL", item.get("EKWSL"));
			cgstTbl.setValue("VABME", item.get("VABME"));
			cgstTbl.setValue("LADGR", item.get("LADGR"));
			cgstTbl.setValue("CG_NTGEW", item.get("CG_NTGEW"));
			cgstTbl.setValue("CG_GEWEI", item.get("CG_GEWEI"));
			cgstTbl.setValue("CG_BRGEW", item.get("CG_BRGEW"));
			cgstTbl.setValue("CG_VOLEH", item.get("CG_VOLEH"));
			cgstTbl.setValue("CG_VOLUM", item.get("CG_VOLUM"));
			cgstTbl.setValue("ZD_BSTME", item.get("ZD_BSTME"));
			cgstTbl.setValue("ZD_NTGEW", item.get("ZD_NTGEW"));
			cgstTbl.setValue("ZD_GEWEI", item.get("ZD_GEWEI"));
			cgstTbl.setValue("ZD_BRGEW", item.get("ZD_BRGEW"));
			cgstTbl.setValue("ZD_VOLEH", item.get("ZD_VOLEH"));
			cgstTbl.setValue("ZD_VOLUM", item.get("ZD_VOLUM"));
		}

		JCoTable mrpstTbl = function.getTableParameterList().getTable("IT_MRPST");
		List<Map<String, String>> mrpstItems = itemMap.get("IT_MRPST");
		for (int i = 0; i < mrpstItems.size(); i++) {
			Map<String, String> item = mrpstItems.get(i);
			mrpstTbl.appendRow();
			mrpstTbl.setRow(i);

			mrpstTbl.setValue("WERKS", item.get("WERKS"));
			mrpstTbl.setValue("BESKZ", item.get("BESKZ"));
			mrpstTbl.setValue("DISMM", item.get("DISMM"));
			mrpstTbl.setValue("DISGR", item.get("DISGR"));
			mrpstTbl.setValue("DISPO", item.get("DISPO"));
			mrpstTbl.setValue("EISBE", item.get("EISBE"));
			mrpstTbl.setValue("DZEIT", item.get("DZEIT"));
			mrpstTbl.setValue("AUSME", item.get("AUSME"));
			mrpstTbl.setValue("RGEKZ", item.get("RGEKZ"));
			mrpstTbl.setValue("XMCNG", item.get("XMCNG"));
			mrpstTbl.setValue("UEETO", item.get("UEETO"));
			mrpstTbl.setValue("INSMK", item.get("INSMK"));
			mrpstTbl.setValue("NFMAT", item.get("NFMAT"));
			mrpstTbl.setValue("FHORI", item.get("FHORI"));
			mrpstTbl.setValue("STRGR", item.get("STRGR"));
			mrpstTbl.setValue("PLIFZ", item.get("PLIFZ"));
			mrpstTbl.setValue("MTVFP", item.get("MTVFP"));
			mrpstTbl.setValue("DISLS", item.get("DISLS"));
			mrpstTbl.setValue("KZECH", item.get("KZECH"));
			mrpstTbl.setValue("BSTRF", item.get("BSTRF"));
			mrpstTbl.setValue("LGPRO", item.get("LGPRO"));
			mrpstTbl.setValue("FRTME", item.get("FRTME"));
			mrpstTbl.setValue("SFCPF", item.get("SFCPF"));
			mrpstTbl.setValue("AUSDT", item.get("AUSDT"));
			mrpstTbl.setValue("WEBAZ", item.get("WEBAZ"));
			mrpstTbl.setValue("SOBSL", item.get("SOBSL"));
			mrpstTbl.setValue("LGFSB", item.get("LGFSB"));
			mrpstTbl.setValue("ALTSL", item.get("ALTSL"));
			mrpstTbl.setValue("KZAUS", item.get("KZAUS"));
			mrpstTbl.setValue("BSTMI", item.get("BSTMI"));
			mrpstTbl.setValue("SC_NTGEW", item.get("SC_NTGEW"));
			mrpstTbl.setValue("SC_BRGEW", item.get("SC_BRGEW"));
			mrpstTbl.setValue("SC_VOLUM", item.get("SC_VOLUM"));
			mrpstTbl.setValue("SC_VOLEH", item.get("SC_VOLEH"));
			mrpstTbl.setValue("SC_GEWEI", item.get("SC_GEWEI"));
		}

		JCoTable bhstTbl = function.getTableParameterList().getTable("IT_BHST");
		List<Map<String, String>> bhstItems = itemMap.get("IT_BHST");
		for (int i = 0; i < bhstItems.size(); i++) {
			Map<String, String> item = bhstItems.get(i);
			bhstTbl.appendRow();
			bhstTbl.setRow(i);

			bhstTbl.setValue("FORMT", item.get("FORMT"));
		}

		JCoTable cwstTbl = function.getTableParameterList().getTable("IT_CWST");
		List<Map<String, String>> cwstItems = itemMap.get("IT_CWST");
		for (int i = 0; i < cwstItems.size(); i++) {
			Map<String, String> item = cwstItems.get(i);
			cwstTbl.appendRow();
			cwstTbl.setRow(i);

			cwstTbl.setValue("WERKS", item.get("WERKS"));
			cwstTbl.setValue("PEINH", item.get("PEINH"));
			cwstTbl.setValue("VPRSV", item.get("VPRSV"));
			cwstTbl.setValue("KOSGR", item.get("KOSGR"));
			cwstTbl.setValue("PRCTR", item.get("PRCTR"));
			cwstTbl.setValue("BKLAS", item.get("BKLAS"));
			cwstTbl.setValue("BWTTY", item.get("BWTTY"));
			cwstTbl.setValue("HKMAT", item.get("HKMAT"));
			cwstTbl.setValue("AWSLS", item.get("AWSLS"));
			cwstTbl.setValue("EKALR", item.get("EKALR"));
			cwstTbl.setValue("LOSGR", item.get("LOSGR"));
			cwstTbl.setValue("ML_ABST", item.get("ML_ABST"));
		}

		JCoTable gckcstTbl = function.getTableParameterList().getTable("IT_GCKCST");
		List<Map<String, String>> gckcstItems = itemMap.get("IT_GCKCST");
		for (int i = 0; i < gckcstItems.size(); i++) {
			Map<String, String> item = gckcstItems.get(i);
			gckcstTbl.appendRow();
			gckcstTbl.setRow(i);

			gckcstTbl.setValue("WERKS", item.get("WERKS"));
			gckcstTbl.setValue("IPRKZ", item.get("IPRKZ"));
			gckcstTbl.setValue("MHDRZ", item.get("MHDRZ"));
			gckcstTbl.setValue("LZEIH", item.get("LZEIH"));
			gckcstTbl.setValue("MHDHB", item.get("MHDHB"));
			gckcstTbl.setValue("MAXLZ", item.get("MAXLZ"));
			gckcstTbl.setValue("RDMHD", item.get("RDMHD"));
		}

		JCoTable dwhsTbl = function.getTableParameterList().getTable("IT_DWHS");
		List<Map<String, String>> dwhsItems = itemMap.get("IT_DWHS");
		for (int i = 0; i < dwhsItems.size(); i++) {
			Map<String, String> item = dwhsItems.get(i);
			dwhsTbl.appendRow();
			dwhsTbl.setRow(i);

			dwhsTbl.setValue("BSTME", item.get("BSTME"));
			dwhsTbl.setValue("UMREN", item.get("UMREN"));
			dwhsTbl.setValue("UMREZ", item.get("UMREZ"));
			dwhsTbl.setValue("MEINS", item.get("MEINS"));
		}

		JCoTable hgstTbl = function.getTableParameterList().getTable("IT_HGST");
		List<Map<String, String>> hgstItems = itemMap.get("IT_HGST");
		for (int i = 0; i < hgstItems.size(); i++) {
			Map<String, String> item = hgstItems.get(i);
			hgstTbl.appendRow();
			hgstTbl.setRow(i);

			hgstTbl.setValue("WERKS", item.get("WERKS"));
			hgstTbl.setValue("STAWN", item.get("STAWN"));
			hgstTbl.setValue("EXPME", item.get("EXPME"));
			hgstTbl.setValue("HERKL", item.get("HERKL"));
			hgstTbl.setValue("HG_GEWEI", item.get("HG_GEWEI"));
			hgstTbl.setValue("HG_NTGEW", item.get("HG_NTGEW"));
			hgstTbl.setValue("HG_BRGEW", item.get("HG_BRGEW"));
			hgstTbl.setValue("HG_VOLEH", item.get("HG_VOLEH"));
			hgstTbl.setValue("HG_VOLUM", item.get("HG_VOLUM"));

		}

		JCoTable xsstTbl = function.getTableParameterList().getTable("IT_XSST");
		List<Map<String, String>> xsstItems = itemMap.get("IT_XSST");
		for (int i = 0; i < xsstItems.size(); i++) {
			Map<String, String> item = xsstItems.get(i);
			xsstTbl.appendRow();
			xsstTbl.setRow(i);

			xsstTbl.setValue("WERKS", item.get("WERKS"));
			xsstTbl.setValue("VKORG", item.get("VKORG"));
			xsstTbl.setValue("KONDM", item.get("KONDM"));
			xsstTbl.setValue("VTWEG", item.get("VTWEG"));
			xsstTbl.setValue("KTGRM", item.get("KTGRM"));
			xsstTbl.setValue("TAXKM", item.get("TAXKM"));
			xsstTbl.setValue("TATYP", item.get("TATYP"));
			xsstTbl.setValue("VERSG", item.get("VERSG"));
			xsstTbl.setValue("MTPOS", item.get("MTPOS"));
			xsstTbl.setValue("VRKME", item.get("VRKME"));
			xsstTbl.setValue("XS_VOLEH", item.get("XS_VOLEH"));
			xsstTbl.setValue("XS_VOLUM", item.get("XS_VOLUM"));
			xsstTbl.setValue("XS_GEWEI", item.get("XS_GEWEI"));
			xsstTbl.setValue("XS_NTGEW", item.get("XS_NTGEW"));
			xsstTbl.setValue("XS_BRGEW", item.get("XS_BRGEW"));
		}

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, Object> result = new HashMap<String, Object>();

//		if (function.getExportParameterList() != null) {
//			for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
//				result = traversalField(result, iterator);
//			}
//		}

		for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator);
		}

		return result;
	}
	
	public Map<String, Object> Z_MDM_DELETE_SKU(Map<String, String> head, Map<String, List<Map<String, String>>> itemMap, InterMsgData interMsgData) throws EsbException {

		Log.getInstance().stdDebug("==================Z_MDM_DELETE_SKU================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_MDM_DELETE_SKU");

		JCoStructure st = (JCoStructure) function.getImportParameterList().getValue("IP_DETEL");
		st.setValue("MATNR", head.get("MATNR"));
		st.setValue("MTART", head.get("MTART"));
		st.setValue("MBRSH", head.get("MBRSH"));
		st.setValue("MSTAE", head.get("MSTAE"));
		st.setValue("LVORM", head.get("LVORM"));

		JCoTable detelTbl = function.getTableParameterList().getTable("IT_DETEL");
		List<Map<String, String>> detelItems = itemMap.get("IT_DETEL");
		for (int i = 0; i < detelItems.size(); i++) {
			Map<String, String> item = detelItems.get(i);
			detelTbl.appendRow();
			detelTbl.setRow(i);

			detelTbl.setValue("MATNR", item.get("MATNR"));
			detelTbl.setValue("WERKS", item.get("WERKS"));
			detelTbl.setValue("MMSTA", item.get("MMSTA"));
		}

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, Object> result = new HashMap<String, Object>();

		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
			result = traversalField(result, iterator);
		}

//		JCoTable jCoTable = function.getTableParameterList().getTable("IT_RETURN");
//		List<Map<String, Object>> list = new ArrayList<>();
//		Map<String, Object> map;
//		for (int i = 0; i < jCoTable.getNumRows(); i++) {
//			jCoTable.setRow(i);
//			map = new HashMap<>();
//			for (JCoField fld : jCoTable) {
//				map.put(fld.getName(), fld.getValue());
//			}
//			list.add(map);
//		}
//		result.put("IT_RETURN", list);
		for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator);
		}

		return result;
	}
	
	public Map<String, Object> Z_MDM_VENDER_MAINTAIN_CREATE(Map<String, String> head, Map<String, List<Map<String, String>>> itemMap, InterMsgData interMsgData) throws EsbException {

		Log.getInstance().stdDebug("==================Z_MDM_VENDER_MAINTAIN_CREATE================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_MDM_VENDER_MAINTAIN_CREATE");

		JCoStructure st = (JCoStructure) function.getImportParameterList().getValue("IM_VENDER");
		st.setValue("LIFNR", head.get("LIFNR"));
		st.setValue("KTOKK", head.get("KTOKK"));
		st.setValue("BRSCH", head.get("BRSCH"));
		st.setValue("ANRED", head.get("ANRED"));
		st.setValue("STCEG", head.get("STCEG"));
		st.setValue("NAME1", head.get("NAME1"));
		st.setValue("NAME2", head.get("NAME2"));
		st.setValue("NAME3", head.get("NAME3"));
		st.setValue("SORTL", head.get("SORTL"));
		st.setValue("STRAS", head.get("STRAS"));
		st.setValue("ORT01", head.get("ORT01"));
		st.setValue("PSTLZ", head.get("PSTLZ"));
		st.setValue("LAND1", head.get("LAND1"));
		st.setValue("REGIO", head.get("REGIO"));
		st.setValue("SPRAS", head.get("SPRAS"));
		st.setValue("SPRAS_T002", head.get("SPRAS_T002"));
		st.setValue("TELF1", head.get("TELF1"));
		st.setValue("TELF2", head.get("TELF2"));
		st.setValue("TELFX", head.get("TELFX"));
		st.setValue("PFACH", head.get("PFACH"));
		st.setValue("SMTP_ADDR", head.get("SMTP_ADDR"));
		st.setValue("TZONE", head.get("TZONE"));
		st.setValue("SPERR", head.get("SPERR"));
		st.setValue("SPERM", head.get("SPERM"));
		st.setValue("LOEVM", head.get("LOEVM"));

		JCoTable banksTbl = function.getTableParameterList().getTable("PT_BANKS");
		List<Map<String, String>> banksItems = itemMap.get("PT_BANKS");
		for (int i = 0; i < banksItems.size(); i++) {
			Map<String, String> item = banksItems.get(i);
			banksTbl.appendRow();
			banksTbl.setRow(i);

			banksTbl.setValue("BANKS", item.get("BANKS"));
			banksTbl.setValue("BANKL", item.get("BANKL"));
			banksTbl.setValue("BANKN", item.get("BANKN"));
			banksTbl.setValue("KOINH", item.get("KOINH"));
			banksTbl.setValue("BKREF", item.get("BKREF"));
			banksTbl.setValue("BANKA", item.get("BANKA"));
			banksTbl.setValue("PROVZ", item.get("PROVZ"));
			banksTbl.setValue("STRAS", item.get("STRAS"));
			banksTbl.setValue("ORT01", item.get("ORT01"));
			banksTbl.setValue("BRNCH", item.get("BRNCH"));
			banksTbl.setValue("SWIFT", item.get("SWIFT"));
		}

		JCoTable bukrsTbl = function.getTableParameterList().getTable("PT_BUKRS");
		List<Map<String, String>> bukrsItems = itemMap.get("PT_BUKRS");
		for (int i = 0; i < bukrsItems.size(); i++) {
			Map<String, String> item = bukrsItems.get(i);
			bukrsTbl.appendRow();
			bukrsTbl.setRow(i);

			bukrsTbl.setValue("BUKRS", item.get("BUKRS"));
			bukrsTbl.setValue("AKONT", item.get("AKONT"));
			bukrsTbl.setValue("FDGRV", item.get("FDGRV"));
			bukrsTbl.setValue("ZTERM", item.get("ZTERM"));
			bukrsTbl.setValue("REPRF", item.get("REPRF"));
			bukrsTbl.setValue("SPERR", item.get("SPERR"));
			bukrsTbl.setValue("LOEVM", item.get("LOEVM"));
		}

		JCoTable ekorgTbl = function.getTableParameterList().getTable("PT_EKORG");
		List<Map<String, String>> ekorgItems = itemMap.get("PT_EKORG");
		for (int i = 0; i < ekorgItems.size(); i++) {
			Map<String, String> item = ekorgItems.get(i);
			ekorgTbl.appendRow();
			ekorgTbl.setRow(i);

			ekorgTbl.setValue("EKORG", item.get("EKORG"));
			ekorgTbl.setValue("WAERS", item.get("WAERS"));
			ekorgTbl.setValue("ZTERM", item.get("ZTERM"));
			ekorgTbl.setValue("VERKF", item.get("VERKF"));
			ekorgTbl.setValue("EKGRP", item.get("EKGRP"));
			ekorgTbl.setValue("KALSK", item.get("KALSK"));
			ekorgTbl.setValue("LEBRE", item.get("LEBRE"));
			ekorgTbl.setValue("SPERM", item.get("SPERM"));
		}

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, Object> result = new HashMap<String, Object>();

		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
			result = traversalField(result, iterator);
		}

		String[] outTblArr = { "RE_MESSAGE", "RE_RFC_RETURN" };
		List<String> outTblList = Arrays.asList(outTblArr);
		for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator, outTblList);
		}

		return result;
	}
	
	public Map<String, Object> Z_MDM_VENDER_MAINTAIN_CHANGE(Map<String, String> head, Map<String, List<Map<String, String>>> itemMap, InterMsgData interMsgData) throws EsbException {

		Log.getInstance().stdDebug("==================Z_MDM_VENDER_MAINTAIN_CHANGE================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_MDM_VENDER_MAINTAIN_CHANGE");

		JCoStructure st = (JCoStructure) function.getImportParameterList().getValue("IM_VENDER");
		st.setValue("LIFNR", head.get("LIFNR"));
		st.setValue("KTOKK", head.get("KTOKK"));
		st.setValue("BRSCH", head.get("BRSCH"));
		st.setValue("ANRED", head.get("ANRED"));
		st.setValue("STCEG", head.get("STCEG"));
		st.setValue("NAME1", head.get("NAME1"));
		st.setValue("NAME2", head.get("NAME2"));
		st.setValue("NAME3", head.get("NAME3"));
		st.setValue("SORTL", head.get("SORTL"));
		st.setValue("STRAS", head.get("STRAS"));
		st.setValue("ORT01", head.get("ORT01"));
		st.setValue("PSTLZ", head.get("PSTLZ"));
		st.setValue("LAND1", head.get("LAND1"));
		st.setValue("REGIO", head.get("REGIO"));
		st.setValue("SPRAS", head.get("SPRAS"));
		st.setValue("SPRAS_T002", head.get("SPRAS_T002"));
		st.setValue("TELF1", head.get("TELF1"));
		st.setValue("TELF2", head.get("TELF2"));
		st.setValue("TELFX", head.get("TELFX"));
		st.setValue("PFACH", head.get("PFACH"));
		st.setValue("SMTP_ADDR", head.get("SMTP_ADDR"));
		st.setValue("TZONE", head.get("TZONE"));
		st.setValue("SPERR", head.get("SPERR"));
		st.setValue("SPERM", head.get("SPERM"));
		st.setValue("LOEVM", head.get("LOEVM"));

		JCoTable banksTbl = function.getTableParameterList().getTable("PT_BANKS");
		List<Map<String, String>> banksItems = itemMap.get("PT_BANKS");
		for (int i = 0; i < banksItems.size(); i++) {
			Map<String, String> item = banksItems.get(i);
			banksTbl.appendRow();
			banksTbl.setRow(i);

			banksTbl.setValue("BANKS", item.get("BANKS"));
			banksTbl.setValue("BANKL", item.get("BANKL"));
			banksTbl.setValue("BANKN", item.get("BANKN"));
			banksTbl.setValue("KOINH", item.get("KOINH"));
			banksTbl.setValue("BKREF", item.get("BKREF"));
			banksTbl.setValue("BANKA", item.get("BANKA"));
			banksTbl.setValue("PROVZ", item.get("PROVZ"));
			banksTbl.setValue("STRAS", item.get("STRAS"));
			banksTbl.setValue("ORT01", item.get("ORT01"));
			banksTbl.setValue("BRNCH", item.get("BRNCH"));
			banksTbl.setValue("SWIFT", item.get("SWIFT"));
		}

		JCoTable bukrsTbl = function.getTableParameterList().getTable("PT_BUKRS");
		List<Map<String, String>> bukrsItems = itemMap.get("PT_BUKRS");
		for (int i = 0; i < bukrsItems.size(); i++) {
			Map<String, String> item = bukrsItems.get(i);
			bukrsTbl.appendRow();
			bukrsTbl.setRow(i);

			bukrsTbl.setValue("BUKRS", item.get("BUKRS"));
			bukrsTbl.setValue("AKONT", item.get("AKONT"));
			bukrsTbl.setValue("FDGRV", item.get("FDGRV"));
			bukrsTbl.setValue("ZTERM", item.get("ZTERM"));
			bukrsTbl.setValue("REPRF", item.get("REPRF"));
			bukrsTbl.setValue("SPERR", item.get("SPERR"));
			bukrsTbl.setValue("LOEVM", item.get("LOEVM"));
		}

		JCoTable ekorgTbl = function.getTableParameterList().getTable("PT_EKORG");
		List<Map<String, String>> ekorgItems = itemMap.get("PT_EKORG");
		for (int i = 0; i < ekorgItems.size(); i++) {
			Map<String, String> item = ekorgItems.get(i);
			ekorgTbl.appendRow();
			ekorgTbl.setRow(i);

			ekorgTbl.setValue("EKORG", item.get("EKORG"));
			ekorgTbl.setValue("WAERS", item.get("WAERS"));
			ekorgTbl.setValue("ZTERM", item.get("ZTERM"));
			ekorgTbl.setValue("VERKF", item.get("VERKF"));
			ekorgTbl.setValue("EKGRP", item.get("EKGRP"));
			ekorgTbl.setValue("KALSK", item.get("KALSK"));
			ekorgTbl.setValue("LEBRE", item.get("LEBRE"));
			ekorgTbl.setValue("SPERM", item.get("SPERM"));
		}

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, Object> result = new HashMap<String, Object>();

		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
			result = traversalField(result, iterator);
		}

		String[] outTblArr = { "RE_MESSAGE", "RE_RFC_RETURN" };
		List<String> outTblList = Arrays.asList(outTblArr);
		for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator, outTblList);
		}

		return result;
	}
	
	public Map<String, Object> Z_MDM_HRP1001(Map<String, String> head, InterMsgData interMsgData) throws EsbException {

		Log.getInstance().stdDebug("==================Z_MDM_HRP1001================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_MDM_HRP1001");

		function.getImportParameterList().setValue("I_FLAG", head.get("I_FLAG"));
		function.getImportParameterList().setValue("I_LANGU", head.get("I_LANGU"));

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, Object> result = new HashMap<String, Object>();

		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
			result = traversalField(result, iterator);
		}

		for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator);
		}

		return result;
	}
	
	public Map<String, Object> Z_MDM_HRP1002(Map<String, String> head, InterMsgData interMsgData) throws EsbException {

		Log.getInstance().stdDebug("==================Z_MDM_HRP1002================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_MDM_HRP1002");

		function.getImportParameterList().setValue("I_FLAG", head.get("I_FLAG"));
		function.getImportParameterList().setValue("I_LANGU", head.get("I_LANGU"));

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, Object> result = new HashMap<String, Object>();

		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
			result = traversalField(result, iterator);
		}

		for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator, "Z_MDM_HRP1002");
		}

		return result;
	}
	

	public Map<String, Object> Z_MDM_CUSTOMER_MAINTAIN_CREATE(Map<String, String> head, Map<String, List<Map<String, String>>> itemMap, InterMsgData interMsgData) throws EsbException {

		Log.getInstance().stdDebug("==================Z_MDM_CUSTOMER_MAINTAIN_CREATE================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_MDM_CUSTOMER_MAINTAIN_CREATE");

		JCoStructure st = (JCoStructure) function.getImportParameterList().getValue("IS_KNA1");
		st.setValue("KUNNR", head.get("KUNNR"));
		st.setValue("NAME1", head.get("NAME1"));
		st.setValue("NAME2", head.get("NAME2"));
		st.setValue("ANRED", head.get("ANRED"));
		st.setValue("SORTL", head.get("SORTL"));
		st.setValue("LAND1", head.get("LAND1"));
		st.setValue("ORT01", head.get("ORT01"));
		st.setValue("REGIO", head.get("REGIO"));
		st.setValue("STRAS", head.get("STRAS"));
		st.setValue("PSTLZ", head.get("PSTLZ"));
		st.setValue("TZONE", head.get("TZONE"));
		st.setValue("PFACH", head.get("PFACH"));
		st.setValue("SPRAS", head.get("SPRAS"));
		st.setValue("EXTENSION1", head.get("EXTENSION1"));
		st.setValue("TELF1", head.get("TELF1"));
		st.setValue("TELF2", head.get("TELF2"));
		st.setValue("TELFX", head.get("TELFX"));
		st.setValue("TELBX", head.get("TELBX"));
		st.setValue("STCEG", head.get("STCEG"));
		st.setValue("SPERR", head.get("SPERR"));
		st.setValue("AUFSD", head.get("AUFSD"));
		st.setValue("LIFSD", head.get("LIFSD"));
		st.setValue("FAKSD", head.get("FAKSD"));
		st.setValue("LOEVM", head.get("LOEVM"));
		st.setValue("NODEL", head.get("NODEL"));
		
		st.setValue("CASSD", head.get("CASSD"));
		st.setValue("KTOKD", head.get("KTOKD"));

		// 20220517 xpw 附加字段
//		st.setValue("ZJLX", head.get("ZJLX"));
//		st.setValue("ZTYPE", head.get("ZTYPE"));
//		st.setValue("ZNUMBER", head.get("ZNUMBER"));
//		st.setValue("ZNAME", head.get("ZNAME"));
//		st.setValue("ZDRESS1", head.get("ZDRESS1"));
//		st.setValue("ZDRESS2", head.get("ZDRESS2"));
//		st.setValue("ZCITY1", head.get("ZCITY1"));
//		st.setValue("ZCITY2", head.get("ZCITY2"));
//		st.setValue("ZDEPART", head.get("ZDEPART"));
//		st.setValue("ZPOST", head.get("ZPOST"));
//		st.setValue("ZOWNER", head.get("ZOWNER"));
//		st.setValue("ZFLAG", head.get("ZFLAG"));
//		st.setValue("ZDATE", head.get("ZDATE"));
//		st.setValue("ZTIME", head.get("ZTIME"));
//		st.setValue("ZUNAME", head.get("ZUNAME"));


		JCoTable knb1Tbl = function.getTableParameterList().getTable("IT_KNB1");
		List<Map<String, String>> knb1Items = itemMap.get("IT_KNB1");
		for (int i = 0; i < knb1Items.size(); i++) {
			Map<String, String> item = knb1Items.get(i);
			knb1Tbl.appendRow();
			knb1Tbl.setRow(i);

			knb1Tbl.setValue("BUKRS", item.get("BUKRS"));
			knb1Tbl.setValue("ZTERM", item.get("ZTERM"));
			knb1Tbl.setValue("AKONT", item.get("AKONT"));
			knb1Tbl.setValue("FDGRV", item.get("FDGRV"));
			knb1Tbl.setValue("SPERR", item.get("SPERR"));
			knb1Tbl.setValue("LOEVM", item.get("LOEVM"));
			knb1Tbl.setValue("NODEL", item.get("NODEL"));
		}

		JCoTable bankTbl = function.getTableParameterList().getTable("IT_BANK");
		List<Map<String, String>> bankItems = itemMap.get("IT_BANK");
		for (int i = 0; i < bankItems.size(); i++) {
			Map<String, String> item = bankItems.get(i);
			bankTbl.appendRow();
			bankTbl.setRow(i);

			bankTbl.setValue("BANKS", item.get("BANKS"));
			bankTbl.setValue("BANKL", item.get("BANKL"));
			bankTbl.setValue("BANKA", item.get("BANKA"));
			bankTbl.setValue("BANKN", item.get("BANKN"));
			bankTbl.setValue("BKREF", item.get("BKREF"));
			bankTbl.setValue("KOINH", item.get("KOINH"));
			bankTbl.setValue("PROVZ", item.get("PROVZ"));
			bankTbl.setValue("STRAS", item.get("STRAS"));
			bankTbl.setValue("ORT01", item.get("ORT01"));
			bankTbl.setValue("BRNCH", item.get("BRNCH"));
			bankTbl.setValue("SWIFT", item.get("SWIFT"));
		}

		JCoTable knvvTbl = function.getTableParameterList().getTable("IT_KNVV");
		List<Map<String, String>> knvvItems = itemMap.get("IT_KNVV");
		for (int i = 0; i < knvvItems.size(); i++) {
			Map<String, String> item = knvvItems.get(i);
			knvvTbl.appendRow();
			knvvTbl.setRow(i);

			knvvTbl.setValue("BEGRU", item.get("BEGRU"));
			knvvTbl.setValue("VKORG", item.get("VKORG"));
			knvvTbl.setValue("VTWEG", item.get("VTWEG"));
			knvvTbl.setValue("SPART", item.get("SPART"));
			knvvTbl.setValue("VKGRP", item.get("VKGRP"));
			knvvTbl.setValue("KDGRP", item.get("KDGRP"));
			knvvTbl.setValue("AWAHR", item.get("AWAHR"));
			knvvTbl.setValue("WAERS", item.get("WAERS"));
			knvvTbl.setValue("KONDA", item.get("KONDA"));
			knvvTbl.setValue("KALKS", item.get("KALKS"));
			knvvTbl.setValue("INCO1", item.get("INCO1"));
			knvvTbl.setValue("INCO2", item.get("INCO2"));
			knvvTbl.setValue("ZTERM", item.get("ZTERM"));
			knvvTbl.setValue("TAXKD", item.get("TAXKD"));
			knvvTbl.setValue("PARVW", item.get("PARVW"));
			knvvTbl.setValue("GPANR", item.get("GPANR"));
			knvvTbl.setValue("VERSG", item.get("VERSG"));
			knvvTbl.setValue("VSBED", item.get("VSBED"));
			knvvTbl.setValue("KTGRD", item.get("KTGRD"));
			knvvTbl.setValue("AUFSD", item.get("AUFSD"));
			knvvTbl.setValue("LIFSD", item.get("LIFSD"));
			knvvTbl.setValue("FAKSD", item.get("FAKSD"));
			knvvTbl.setValue("LOEVM", item.get("LOEVM"));

			knvvTbl.setValue("DQ_1", item.get("DQ_1"));
			knvvTbl.setValue("DQ_2", item.get("DQ_2"));
			knvvTbl.setValue("CITY_1", item.get("CITY_1"));
			knvvTbl.setValue("CITY_2", item.get("CITY_2"));

			knvvTbl.setValue("CASSD", item.get("CASSD"));
			knvvTbl.setValue("ORDER_COUNTRY", item.get("ORDER_COUNTRY"));
		}

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, Object> result = new HashMap<String, Object>();

		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
			result = traversalField(result, iterator);
		}

		String[] outTblArr = { "ET_RETURN", "RE_RFC_RETURN" };
		List<String> outTblList = Arrays.asList(outTblArr);
		for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator, outTblList);
		}

		return result;
	}
	
	public Map<String, Object> Z_MDM_CUSTOMER_MAINTAIN_CHANGE(Map<String, String> head, Map<String, List<Map<String, String>>> itemMap, InterMsgData interMsgData) throws EsbException {

		Log.getInstance().stdDebug("==================Z_MDM_CUSTOMER_MAINTAIN_CHANGE================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_MDM_CUSTOMER_MAINTAIN_CHANGE");

		JCoStructure st = (JCoStructure) function.getImportParameterList().getValue("IS_KNA1");
		st.setValue("KUNNR", head.get("KUNNR"));
		st.setValue("NAME1", head.get("NAME1"));
		st.setValue("NAME2", head.get("NAME2"));
		st.setValue("ANRED", head.get("ANRED"));
		st.setValue("SORTL", head.get("SORTL"));
		st.setValue("LAND1", head.get("LAND1"));
		st.setValue("ORT01", head.get("ORT01"));
		st.setValue("REGIO", head.get("REGIO"));
		st.setValue("STRAS", head.get("STRAS"));
		st.setValue("PSTLZ", head.get("PSTLZ"));
		st.setValue("TZONE", head.get("TZONE"));
		st.setValue("PFACH", head.get("PFACH"));
		st.setValue("SPRAS", head.get("SPRAS"));
		st.setValue("EXTENSION1", head.get("EXTENSION1"));
		st.setValue("TELF1", head.get("TELF1"));
		st.setValue("TELF2", head.get("TELF2"));
		st.setValue("TELFX", head.get("TELFX"));
		st.setValue("TELBX", head.get("TELBX"));
		st.setValue("STCEG", head.get("STCEG"));
		st.setValue("SPERR", head.get("SPERR"));
		st.setValue("AUFSD", head.get("AUFSD"));
		st.setValue("LIFSD", head.get("LIFSD"));
		st.setValue("FAKSD", head.get("FAKSD"));
		st.setValue("LOEVM", head.get("LOEVM"));
		st.setValue("NODEL", head.get("NODEL"));

		st.setValue("CASSD", head.get("CASSD"));
		st.setValue("KTOKD", head.get("KTOKD"));

		// 20220517 xpw 附加字段
//		st.setValue("ZJLX", head.get("ZJLX"));
//		st.setValue("ZTYPE", head.get("ZTYPE"));
//		st.setValue("ZNUMBER", head.get("ZNUMBER"));
//		st.setValue("ZNAME", head.get("ZNAME"));
//		st.setValue("ZDRESS1", head.get("ZDRESS1"));
//		st.setValue("ZDRESS2", head.get("ZDRESS2"));
//		st.setValue("ZCITY1", head.get("ZCITY1"));
//		st.setValue("ZCITY2", head.get("ZCITY2"));
//		st.setValue("ZDEPART", head.get("ZDEPART"));
//		st.setValue("ZPOST", head.get("ZPOST"));
//		st.setValue("ZOWNER", head.get("ZOWNER"));
//		st.setValue("ZFLAG", head.get("ZFLAG"));
//		st.setValue("ZDATE", head.get("ZDATE"));
//		st.setValue("ZTIME", head.get("ZTIME"));
//		st.setValue("ZUNAME", head.get("ZUNAME"));

		JCoTable knb1Tbl = function.getTableParameterList().getTable("IT_KNB1");
		List<Map<String, String>> knb1Items = itemMap.get("IT_KNB1");
		for (int i = 0; i < knb1Items.size(); i++) {
			Map<String, String> item = knb1Items.get(i);
			knb1Tbl.appendRow();
			knb1Tbl.setRow(i);

			knb1Tbl.setValue("BUKRS", item.get("BUKRS"));
			knb1Tbl.setValue("ZTERM", item.get("ZTERM"));
			knb1Tbl.setValue("AKONT", item.get("AKONT"));
			knb1Tbl.setValue("FDGRV", item.get("FDGRV"));
			knb1Tbl.setValue("SPERR", item.get("SPERR"));
			knb1Tbl.setValue("LOEVM", item.get("LOEVM"));
			knb1Tbl.setValue("NODEL", item.get("NODEL"));
		}

		JCoTable bankTbl = function.getTableParameterList().getTable("IT_BANK");
		List<Map<String, String>> bankItems = itemMap.get("IT_BANK");
		for (int i = 0; i < bankItems.size(); i++) {
			Map<String, String> item = bankItems.get(i);
			bankTbl.appendRow();
			bankTbl.setRow(i);

			bankTbl.setValue("BANKS", item.get("BANKS"));
			bankTbl.setValue("BANKL", item.get("BANKL"));
			bankTbl.setValue("BANKA", item.get("BANKA"));
			bankTbl.setValue("BANKN", item.get("BANKN"));
			bankTbl.setValue("BKREF", item.get("BKREF"));
			bankTbl.setValue("KOINH", item.get("KOINH"));
			bankTbl.setValue("PROVZ", item.get("PROVZ"));
			bankTbl.setValue("STRAS", item.get("STRAS"));
			bankTbl.setValue("ORT01", item.get("ORT01"));
			bankTbl.setValue("BRNCH", item.get("BRNCH"));
			bankTbl.setValue("SWIFT", item.get("SWIFT"));
		}

		JCoTable knvvTbl = function.getTableParameterList().getTable("IT_KNVV");
		List<Map<String, String>> knvvItems = itemMap.get("IT_KNVV");
		for (int i = 0; i < knvvItems.size(); i++) {
			Map<String, String> item = knvvItems.get(i);
			knvvTbl.appendRow();
			knvvTbl.setRow(i);

			knvvTbl.setValue("BEGRU", item.get("BEGRU"));
			knvvTbl.setValue("VKORG", item.get("VKORG"));
			knvvTbl.setValue("VTWEG", item.get("VTWEG"));
			knvvTbl.setValue("SPART", item.get("SPART"));
			knvvTbl.setValue("VKGRP", item.get("VKGRP"));
			knvvTbl.setValue("KDGRP", item.get("KDGRP"));
			knvvTbl.setValue("AWAHR", item.get("AWAHR"));
			knvvTbl.setValue("WAERS", item.get("WAERS"));
			knvvTbl.setValue("KONDA", item.get("KONDA"));
			knvvTbl.setValue("KALKS", item.get("KALKS"));
			knvvTbl.setValue("INCO1", item.get("INCO1"));
			knvvTbl.setValue("INCO2", item.get("INCO2"));
			knvvTbl.setValue("ZTERM", item.get("ZTERM"));
			knvvTbl.setValue("TAXKD", item.get("TAXKD"));
			knvvTbl.setValue("PARVW", item.get("PARVW"));
			knvvTbl.setValue("GPANR", item.get("GPANR"));
			knvvTbl.setValue("VERSG", item.get("VERSG"));
			knvvTbl.setValue("VSBED", item.get("VSBED"));
			knvvTbl.setValue("KTGRD", item.get("KTGRD"));
			knvvTbl.setValue("AUFSD", item.get("AUFSD"));
			knvvTbl.setValue("LIFSD", item.get("LIFSD"));
			knvvTbl.setValue("FAKSD", item.get("FAKSD"));
			knvvTbl.setValue("LOEVM", item.get("LOEVM"));

			knvvTbl.setValue("DQ_1", item.get("DQ_1"));
			knvvTbl.setValue("DQ_2", item.get("DQ_2"));
			knvvTbl.setValue("CITY_1", item.get("CITY_1"));
			knvvTbl.setValue("CITY_2", item.get("CITY_2"));

			knvvTbl.setValue("CASSD", item.get("CASSD"));
			knvvTbl.setValue("ORDER_COUNTRY", item.get("ORDER_COUNTRY"));
		}

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, Object> result = new HashMap<String, Object>();

		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
			result = traversalField(result, iterator);
		}

		String[] outTblArr = { "ET_RETURN", "RE_RFC_RETURN" };
		List<String> outTblList = Arrays.asList(outTblArr);
		for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator, outTblList);
		}

		return result;
	}
	
	public Map<String, Object> Z_MDM_READ_TABLE(Map<String, String> head, Map<String, List<Map<String, String>>> itemMap, InterMsgData interMsgData) throws EsbException {

		Log.getInstance().stdDebug("==================Z_MDM_READ_TABLE================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_MDM_READ_TABLE");
		
		function.getImportParameterList().setValue("QUERY_TABLE", head.get("QUERY_TABLE"));
		function.getImportParameterList().setValue("ROWCOUNT", head.get("ROWCOUNT"));
		function.getImportParameterList().setValue("DELIMITER", head.get("DELIMITER"));
		function.getImportParameterList().setValue("FIELDNAME1", head.get("FIELDNAME1"));
		function.getImportParameterList().setValue("FIELDNAME1X", head.get("FIELDNAME1X"));
		function.getImportParameterList().setValue("FIELDNAME2", head.get("FIELDNAME2"));
		function.getImportParameterList().setValue("FIELDNAME2X", head.get("FIELDNAME2X"));
		function.getImportParameterList().setValue("FIELDNAME3", head.get("FIELDNAME3"));
		function.getImportParameterList().setValue("FIELDNAME3X", head.get("FIELDNAME3X"));
		function.getImportParameterList().setValue("FIELDNAME4", head.get("FIELDNAME4"));
		function.getImportParameterList().setValue("FIELDNAME4X", head.get("FIELDNAME4X"));
		function.getImportParameterList().setValue("FIELDNAME5", head.get("FIELDNAME5"));
		function.getImportParameterList().setValue("FIELDNAME5X", head.get("FIELDNAME5X"));

		function.getImportParameterList().setValue("FIELDNAME6", head.get("FIELDNAME6"));
		function.getImportParameterList().setValue("FIELDNAME6X", head.get("FIELDNAME6X"));
		function.getImportParameterList().setValue("FIELDNAME7", head.get("FIELDNAME7"));
		function.getImportParameterList().setValue("FIELDNAME7X", head.get("FIELDNAME7X"));

		JCoTable optTbl = function.getTableParameterList().getTable("OPTIONS");
		List<Map<String, String>> optItems = itemMap.get("OPTIONS");
		for (int i = 0; i < optItems.size(); i++) {
			Map<String, String> item = optItems.get(i);
			optTbl.appendRow();
			optTbl.setRow(i);

			optTbl.setValue("TEXT", item.get("TEXT"));
		}

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, Object> result = new HashMap<String, Object>();

		String[] outTblArr = { "DATA", "IT_TAB", "RETURN_MESSAGE" };
		List<String> outTblList = Arrays.asList(outTblArr);
		for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator, outTblList);
		}

		return result;
	}

	public Map<String, Object> Z_MDM_READ_SKB1(Map<String, String> head, InterMsgData interMsgData) throws EsbException {

		Log.getInstance().stdDebug("==================Z_MDM_READ_SKB1================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_MDM_READ_SKB1");

		function.getImportParameterList().setValue("I_BUKRS", head.get("I_BUKRS"));
		function.getImportParameterList().setValue("I_SAKNR", head.get("I_SAKNR"));
		function.getImportParameterList().setValue("I_SPRAS", head.get("I_SPRAS"));
		function.getImportParameterList().setValue("I_MITKZ", head.get("I_MITKZ"));

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, Object> result = new HashMap<String, Object>();

		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
			result = traversalField(result, iterator);
		}

		for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator);
		}

		return result;
	}
	
//	public Map<String, Object> Z_TMS_READ_SKU_HSCODE(Map<String, String> head, List<Map<String, String>> items, InterMsgData interMsgData) throws EsbException {
//
//		Log.getInstance().stdDebug("==================Z_TMS_READ_SKU_HSCODE================");
//		JCoFunction function = RfcManager.getInstance().getFunction("Z_TMS_READ_SKU_HSCODE");
////		JCoFunction function = getFunctionTest("Z_TMS_READ_SKU_HSCODE");
//
//
//		sendReqLog(interMsgData, "ESB", "SAP", function);
//		long startTime = System.currentTimeMillis();
//
//		RfcManager.getInstance().execute(function);
////		executeTest(function);
//
//		long endTime = System.currentTimeMillis();
//		int time = (int) (endTime - startTime);
//		sendResLog(interMsgData, "SAP", "ESB", time, function);
//
//		Map<String, Object> result = new HashMap<String, Object>();
//
//		for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
//			result = traversalTable(result, iterator, "Z_TMS_READ_SKU_HSCODE");
//		}
//
//		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
//			result = traversalField(result, iterator);
//		}
//		return result;
//	}

	public Map<String, Object> Z_TMS_READ_TCURR(Map<String, String> head, InterMsgData interMsgData) throws EsbException {

		Log.getInstance().stdDebug("==================Z_TMS_READ_TCURR================");
		
		JCoFunction function = RfcManager.getInstance().getFunction("Z_TMS_READ_TCURR");
//		JCoFunction function = getFunctionTest("Z_TMS_READ_TCURR");
		function.getImportParameterList().setValue("I_FLAG", head.get("I_FLAG"));

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);
//		executeTest(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, Object> result = new HashMap<String, Object>();

		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
			result = traversalField(result, iterator);
		}

		for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator);
		}

		return result;
	}
	
	public  Map<String, Object> Z_TMS_GET_TOKEN(Map<String, String> head, InterMsgData interMsgData) throws Exception {
		Log.getInstance().stdDebug("==================Z_TMS_GET_TOKEN================");
		
		long startTime = System.currentTimeMillis();
		

		String apikey = head.get("apikey");
//		String tmsRestUrl = (String) cm.get("TMS_GET_TOKEN_URL") + "?apikey={apikey}";
		String redisPre = ConstantUtil.REDIS_PRE_DICT + ":" + ConstantUtil.REDIS_PRE_DICT_TYPE_URL_TMS + ":";
		String tmsRestUrl = getRedisValue(redisPre + "TMS_GET_TOKEN_URL") + "?apikey={apikey}";

		Map<String, String> reqLog = new HashMap<>();
		reqLog.put("METHOD", "get");
		reqLog.put("URL", tmsRestUrl.replace("{apikey}", apikey));
		reqLog.put("FUNCTION", "Z_TMS_GET_TOKEN");
		String opration = "调用TMS接口";
		sendReqLog(interMsgData, "ESB", "TMS", JSON.toJSONString(reqLog, SerializerFeature.WriteMapNullValue), opration);

		RestTemplate restTemplate = HttpsClientUtils.getRestTemplate();
		String resJson = restTemplate.getForObject(tmsRestUrl, String.class, apikey);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "TMS", "ESB", time, resJson, "收到TMS接口返回信息");

		Map resMap = JSON.parseObject(resJson, Map.class);
		return resMap;
	}

	public Map<String, Object> Z_TMS_SAVE_ORDERINFO(Map requestData, InterMsgData interMsgData) throws Exception {
		Log.getInstance().stdDebug("==================Z_TMS_SAVE_ORDERINFO================");

		Map dataMap = (Map) requestData.get("Data");
		String data = JSON.toJSONString(dataMap, SerializerFeature.WriteMapNullValue);

		String opration = "调用TMS接口";
		sendReqLog(interMsgData, "ESB", "TMS", data, opration);
		long startTime = System.currentTimeMillis();

		RestTemplate restTemplate = HttpsClientUtils.getRestTemplate();
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		HttpEntity<String> requestEntity = new HttpEntity<String>(data, headers);

		List<HttpMessageConverter<?>> httpMessageConverters = restTemplate.getMessageConverters();
		httpMessageConverters.stream().forEach(httpMessageConverter -> {
			if (httpMessageConverter instanceof StringHttpMessageConverter) {
				StringHttpMessageConverter messageConverter = (StringHttpMessageConverter) httpMessageConverter;
				messageConverter.DEFAULT_CHARSET.forName("UTF-8");
			}
		});

//		String tmsRestUrl = (String) cm.get("TMS_SAVE_ORDERINFO_URL");
		String redisPre = ConstantUtil.REDIS_PRE_DICT + ":" + ConstantUtil.REDIS_PRE_DICT_TYPE_URL_TMS + ":";
		String tmsRestUrl = getRedisValue(redisPre + "TMS_SAVE_ORDERINFO_URL");
		Log.getInstance().stdDebug("TMS_SAVE_ORDERINFO_URL=" + tmsRestUrl);
		String resJson = restTemplate.postForObject(tmsRestUrl, requestEntity, String.class);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "TMS", "ESB", time, resJson, "收到TMS接口返回信息");

		Map resMap = JSON.parseObject(resJson, Map.class);
		return resMap;
	}
	
	public Map<String, Object> Z_TMS_UPDATE_ORDERINFO(Map requestData, InterMsgData interMsgData) throws Exception {
		Log.getInstance().stdDebug("==================Z_TMS_UPDATE_ORDERINFO================");

		Map dataMap = (Map) requestData.get("ReqTmsData");
		String data = JSON.toJSONString(dataMap, SerializerFeature.WriteMapNullValue);

		String opration = "调用TMS接口";
		sendReqLog(interMsgData, "ESB", "TMS", data, opration);
		long startTime = System.currentTimeMillis();

		RestTemplate restTemplate = HttpsClientUtils.getRestTemplate();
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		HttpEntity<String> requestEntity = new HttpEntity<String>(data, headers);

		List<HttpMessageConverter<?>> httpMessageConverters = restTemplate.getMessageConverters();
		httpMessageConverters.stream().forEach(httpMessageConverter -> {
			if (httpMessageConverter instanceof StringHttpMessageConverter) {
				StringHttpMessageConverter messageConverter = (StringHttpMessageConverter) httpMessageConverter;
				messageConverter.DEFAULT_CHARSET.forName("UTF-8");
			}
		});

//		String tmsRestUrl = (String) cm.get("TMS_UPDATE_ORDERINFO_URL");
		String redisPre = ConstantUtil.REDIS_PRE_DICT + ":" + ConstantUtil.REDIS_PRE_DICT_TYPE_URL_TMS + ":";
		String tmsRestUrl = getRedisValue(redisPre + "TMS_UPDATE_ORDERINFO_URL");
		Log.getInstance().stdDebug("TMS_UPDATE_ORDERINFO_URL=" + tmsRestUrl);
		String resJson = restTemplate.postForObject(tmsRestUrl, requestEntity, String.class);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "TMS", "ESB", time, resJson, "收到TMS接口返回信息");

		Map resMap = JSON.parseObject(resJson, Map.class);
		return resMap;
	}
	
	public Map<String, Object> Z_TMS_CANCEL_ORDERINFO(Map requestData, InterMsgData interMsgData) throws Exception {
		Log.getInstance().stdDebug("==================Z_TMS_CANCEL_ORDERINFO================");
		
		Map dataMap = (Map) requestData.get("Data");
		String data = JSON.toJSONString(dataMap, SerializerFeature.WriteMapNullValue);
		
		String opration = "调用TMS接口";
		sendReqLog(interMsgData, "ESB", "TMS", data, opration);
		long startTime = System.currentTimeMillis();
		
		RestTemplate restTemplate = HttpsClientUtils.getRestTemplate();
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		HttpEntity<String> requestEntity = new HttpEntity<String>(data, headers);
		
		List<HttpMessageConverter<?>> httpMessageConverters = restTemplate.getMessageConverters();
		httpMessageConverters.stream().forEach(httpMessageConverter -> {
			if (httpMessageConverter instanceof StringHttpMessageConverter) {
				StringHttpMessageConverter messageConverter = (StringHttpMessageConverter) httpMessageConverter;
				messageConverter.DEFAULT_CHARSET.forName("UTF-8");
			}
		});
		
		String redisPre = ConstantUtil.REDIS_PRE_DICT + ":" + ConstantUtil.REDIS_PRE_DICT_TYPE_URL_TMS + ":";
		String tmsRestUrl = getRedisValue(redisPre + "TMS_CANCEL_ORDERINFO_URL");
		Log.getInstance().stdDebug("TMS_CANCEL_ORDERINFO_URL=" + tmsRestUrl);
		String resJson = restTemplate.postForObject(tmsRestUrl, requestEntity, String.class);
		
		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "TMS", "ESB", time, resJson, "收到TMS接口返回信息");
		
		Map resMap = JSON.parseObject(resJson, Map.class);
		return resMap;
	}
	
	public Map<String, Object> Z_TMS_TRD_CUSTOMER_CREATE(String message, InterMsgData interMsgData) throws EsbException {
		Log.getInstance().stdDebug("==================Z_TMS_TRD_CUSTOMER_CREATE================");

		String opration = "调用MDM接口";
		sendReqLog(interMsgData, "ESB", "MDM", message, opration);
		long startTime = System.currentTimeMillis();

		RestTemplate restTemplate = new RestTemplate();
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		HttpEntity<String> requestEntity = new HttpEntity<String>(message, headers);

		List<HttpMessageConverter<?>> httpMessageConverters = restTemplate.getMessageConverters();
		httpMessageConverters.stream().forEach(httpMessageConverter -> {
			if (httpMessageConverter instanceof StringHttpMessageConverter) {
				StringHttpMessageConverter messageConverter = (StringHttpMessageConverter) httpMessageConverter;
				messageConverter.DEFAULT_CHARSET.forName("UTF-8");
			}
		});

//		String mdmRestUrl = (String) cm.get("TMS_TRD_CUSTOMER_CREATE_URL");
		String redisPre = ConstantUtil.REDIS_PRE_DICT + ":" + ConstantUtil.REDIS_PRE_DICT_TYPE_URL_MDM + ":";
		String mdmRestUrl = getRedisValue(redisPre + "TMS_TRD_CUSTOMER_CREATE_URL");
		Log.getInstance().stdDebug("TMS_TRD_CUSTOMER_CREATE_URL=" + mdmRestUrl);
		String resJson = restTemplate.postForObject(mdmRestUrl, requestEntity, String.class);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "MDM", "ESB", time, resJson, "收到MDM接口返回信息");

		Map resMap = JSON.parseObject(resJson, Map.class);
		return resMap;
	}
	
	public Map<String, Object> Z_TMS_TRD_CUSTOMER_CHANGE(String message, InterMsgData interMsgData) throws EsbException {
		Log.getInstance().stdDebug("==================Z_TMS_TRD_CUSTOMER_CHANGE================");

		String opration = "调用MDM接口";
		sendReqLog(interMsgData, "ESB", "MDM", message, opration);
		long startTime = System.currentTimeMillis();

		RestTemplate restTemplate = new RestTemplate();
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		HttpEntity<String> requestEntity = new HttpEntity<String>(message, headers);

		List<HttpMessageConverter<?>> httpMessageConverters = restTemplate.getMessageConverters();
		httpMessageConverters.stream().forEach(httpMessageConverter -> {
			if (httpMessageConverter instanceof StringHttpMessageConverter) {
				StringHttpMessageConverter messageConverter = (StringHttpMessageConverter) httpMessageConverter;
				messageConverter.DEFAULT_CHARSET.forName("UTF-8");
			}
		});

//		String mdmRestUrl = (String) cm.get("TMS_TRD_CUSTOMER_CHANGE_URL");
		String redisPre = ConstantUtil.REDIS_PRE_DICT + ":" + ConstantUtil.REDIS_PRE_DICT_TYPE_URL_MDM + ":";
		String mdmRestUrl = getRedisValue(redisPre + "TMS_TRD_CUSTOMER_CHANGE_URL");
		Log.getInstance().stdDebug("TMS_TRD_CUSTOMER_CHANGE_URL=" + mdmRestUrl);
		String resJson = restTemplate.postForObject(mdmRestUrl, requestEntity, String.class);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "MDM", "ESB", time, resJson, "收到MDM接口返回信息");

		Map resMap = JSON.parseObject(resJson, Map.class);
		return resMap;
	}

	public Map<String, Object> Z_TMS_UPDATE_SPINFO(Map requestData, InterMsgData interMsgData) throws Exception {
		Log.getInstance().stdDebug("==================Z_TMS_UPDATE_SPINFO================");

		Map dataMap = (Map) requestData.get("ReqTmsData");
		String data = JSON.toJSONString(dataMap, SerializerFeature.WriteMapNullValue);

		String opration = "调用TMS接口";
		sendReqLog(interMsgData, "ESB", "TMS", data, opration);
		long startTime = System.currentTimeMillis();

		RestTemplate restTemplate = HttpsClientUtils.getRestTemplate();
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		HttpEntity<String> requestEntity = new HttpEntity<String>(data, headers);

		List<HttpMessageConverter<?>> httpMessageConverters = restTemplate.getMessageConverters();
		httpMessageConverters.stream().forEach(httpMessageConverter -> {
			if (httpMessageConverter instanceof StringHttpMessageConverter) {
				StringHttpMessageConverter messageConverter = (StringHttpMessageConverter) httpMessageConverter;
				messageConverter.DEFAULT_CHARSET.forName("UTF-8");
			}
		});

//		String tmsRestUrl = (String) cm.get("TMS_UPDATE_SPINFO_URL");
		String redisPre = ConstantUtil.REDIS_PRE_DICT + ":" + ConstantUtil.REDIS_PRE_DICT_TYPE_URL_TMS + ":";
		String tmsRestUrl = getRedisValue(redisPre + "TMS_UPDATE_SPINFO_URL");
		Log.getInstance().stdDebug("TMS_UPDATE_SPINFO_URL=" + tmsRestUrl);
		String resJson = restTemplate.postForObject(tmsRestUrl, requestEntity, String.class);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "TMS", "ESB", time, resJson, "收到TMS接口返回信息");

		Map resMap = JSON.parseObject(resJson, Map.class);
		return resMap;
	}

	public Map<String, Object> Z_TMS_READ_COST_TYPE(InterMsgData interMsgData) throws EsbException {

		Log.getInstance().stdDebug("==================Z_TMS_READ_COST_TYPE================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_TMS_READ_COST_TYPE");
//		JCoFunction function = getFunctionTest("Z_TMS_READ_COST_TYPE");

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);
//		executeTest(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, Object> result = new HashMap<String, Object>();

		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
			result = traversalField(result, iterator);
		}

		for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator);
		}

		return result;
	}
	
	public Map<String, Object> Z_MDM_GET_MATER_TYPE(InterMsgData interMsgData) throws EsbException {
		Log.getInstance().stdDebug("==================Z_MDM_GET_MATER_TYPE================");

		long startTime = System.currentTimeMillis();

//		String mdmRestUrl = (String) cm.get("MDM_GET_MATER_TYPE_URL");
		String redisPre = ConstantUtil.REDIS_PRE_DICT + ":" + ConstantUtil.REDIS_PRE_DICT_TYPE_URL_MDM + ":";
		String mdmRestUrl = getRedisValue(redisPre + "MDM_GET_MATER_TYPE_URL");
		Log.getInstance().stdDebug("MDM_GET_MATER_TYPE_URL=" + mdmRestUrl);

		Map<String, String> reqLog = new HashMap<>();
		reqLog.put("METHOD", "get");
		reqLog.put("URL", mdmRestUrl);
		reqLog.put("FUNCTION", "Z_MDM_GET_MATER_TYPE");
		String opration = "调用MDM接口";
		sendReqLog(interMsgData, "ESB", "MDM", JSON.toJSONString(reqLog, SerializerFeature.WriteMapNullValue), opration);

		RestTemplate restTemplate = new RestTemplate();
		String resJson = restTemplate.getForObject(mdmRestUrl, String.class);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "MDM", "ESB", time, resJson, "收到MDM接口返回信息");

		Map resMap = JSON.parseObject(resJson, Map.class);
		return resMap;
	}
	
	public Map<String, Object> Z_TMS_SET_ESTIMATE(Map<String, String> head, Map<String, List<Map<String, String>>> itemMap, InterMsgData interMsgData) throws EsbException {
		
		Log.getInstance().stdDebug("==================Z_TMS_SET_ESTIMATE================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_TMS_SET_ESTIMATE");


		JCoTable tblHead = function.getTableParameterList().getTable("IT_HEAD");
		List<Map<String, String>> listHead = itemMap.get("IT_HEAD");
		for (int i = 0; i < listHead.size(); i++) {
			tblHead.appendRow();
			tblHead.setRow(i);
			tblHead.setValue("ZJJDH", listHead.get(i).get("ZJJDH"));
			tblHead.setValue("EBELN", listHead.get(i).get("EBELN"));
			//新接口Z_TMS_SET_ESTIMATE_STATUS做好后，原来的预估接口Z_TMS_SET_ESTIMATE要删掉1个字段weakt
			// tblHead.setValue("WEAKT", listHead.get(i).get("WEAKT"));
			tblHead.setValue("ZCLLX", listHead.get(i).get("ZCLLX"));
		}

		JCoTable tblItem = function.getTableParameterList().getTable("IT_ITEM");
		List<Map<String, String>> listItem = itemMap.get("IT_ITEM");
		for (int i = 0; i < listItem.size(); i++) {
			tblItem.appendRow();
			tblItem.setRow(i);
			tblItem.setValue("EBELN", listItem.get(i).get("EBELN"));
			tblItem.setValue("EBELP", listItem.get(i).get("EBELP"));

			tblItem.setValue("KBETR", listItem.get(i).get("KBETR"));
			tblItem.setValue("KSCHL", listItem.get(i).get("KSCHL"));
			tblItem.setValue("KSTAT", listItem.get(i).get("KSTAT"));
			tblItem.setValue("LIFNR", listItem.get(i).get("LIFNR"));
			tblItem.setValue("WAERS", listItem.get(i).get("WAERS"));
			tblItem.setValue("ZFYSJ", listItem.get(i).get("ZFYSJ"));
			tblItem.setValue("ZFYSL", listItem.get(i).get("ZFYSL"));
			//2021-08-23删除业务金额ZYWJE字段
//			tblItem.setValue("ZYWJE", listItem.get(i).get("ZYWJE"));
			
			//输出参数，不用设
			tblItem.setValue("COND_ST_NO", listItem.get(i).get("COND_ST_NO"));
			tblItem.setValue("COND_COUNT", listItem.get(i).get("COND_COUNT"));
		}

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, Object> result = new HashMap<String, Object>();
		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
			result = traversalField(result, iterator);
		}

		String[] outTblArr = { "IT_ITEM", "OUT_RETURN", "IT_KONV" };// IT_ITEM即是入参也是出差
		List<String> outTblList = Arrays.asList(outTblArr);
		for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator, outTblList);
		}
		return result;
    }

	public Map<String, Object> Z_TMS_GET_PO_MIGO_STATUS(Map<String, String> head, List<Map<String, Object>> items, InterMsgData interMsgData) throws EsbException {

		Log.getInstance().stdDebug("==================Z_TMS_GET_PO_MIGO_STATUS================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_TMS_GET_PO_MIGO_STATUS");

		JCoTable ptItem = function.getTableParameterList().getTable("PT_EBELN");
		for (int i = 0; i < items.size(); i++) {
			ptItem.appendRow();
			ptItem.setRow(i);
			ptItem.setValue("EBELN", items.get(i).get("EBELN"));
		}

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, Object> result = new HashMap<String, Object>();
		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
			result = traversalField(result, iterator);
		}

		String[] outTblArr = { "PT_MIGO" };
		List<String> outTblList = Arrays.asList(outTblArr);
		for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator, outTblList);
		}
		return result;
	}
	
	public Map<String, Object> Z_TMS_INVOICE_CHECK(Map<String, String> head, List<Map<String, Object>> items, InterMsgData interMsgData) throws EsbException {
		Log.getInstance().stdDebug("==================Z_TMS_INVOICE_CHECK================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_TMS_INVOICE_CHECK");

		JCoStructure st = (JCoStructure) function.getImportParameterList().getValue("I_HEADER");
		st.setValue("XBLNR", head.get("XBLNR"));
		st.setValue("GJAHR", head.get("GJAHR"));
		st.setValue("BLDAT", head.get("BLDAT"));
		st.setValue("BUDAT", head.get("BUDAT"));
		st.setValue("USNAM", head.get("USNAM"));
		st.setValue("LIFNR", head.get("LIFNR"));
		st.setValue("WAERS", head.get("WAERS"));
		st.setValue("RMWWR", head.get("RMWWR"));
		st.setValue("BKTXT", head.get("BKTXT"));
		st.setValue("REBZG", head.get("REBZG"));

		JCoTable ptItem = function.getTableParameterList().getTable("IT_ITEM");
		for (int i = 0; i < items.size(); i++) {
			ptItem.appendRow();
			ptItem.setRow(i);
			ptItem.setValue("EBELN", items.get(i).get("EBELN"));
			ptItem.setValue("WERKS", items.get(i).get("WERKS"));
			ptItem.setValue("EBELP", items.get(i).get("EBELP"));
			ptItem.setValue("KSCHL", items.get(i).get("KSCHL"));
			ptItem.setValue("TXZ01", items.get(i).get("TXZ01"));
			ptItem.setValue("MATNR", items.get(i).get("MATNR"));
			ptItem.setValue("WAERS", items.get(i).get("WAERS"));
			ptItem.setValue("ZFYSJ", items.get(i).get("ZFYSJ"));
			ptItem.setValue("KBETR", items.get(i).get("KBETR"));
			ptItem.setValue("ZFYSL", items.get(i).get("ZFYSL"));
			ptItem.setValue("ZSFYG", items.get(i).get("ZSFYG"));
			ptItem.setValue("COND_ST_NO", items.get(i).get("COND_ST_NO"));
			ptItem.setValue("COND_COUNT", items.get(i).get("COND_COUNT"));
		}

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, Object> result = new HashMap<String, Object>();
		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
			result = traversalField(result, iterator);
		}

		String[] outTblArr = { "RT_RETURN" };
		List<String> outTblList = Arrays.asList(outTblArr);
		for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator, "Z_TMS_INVOICE_CHECK", outTblList, null);
		}
		return result;
	}
	
public Map<String, Object> Z_TMS_INVOICE_HXJDJ(Map<String, String> head, List<Map<String, Object>> items, InterMsgData interMsgData) throws EsbException {
		
		Log.getInstance().stdDebug("==================Z_TMS_INVOICE_HXJDJ================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_TMS_INVOICE_HXJDJ");

		JCoStructure st = (JCoStructure) function.getImportParameterList().getValue("I_HEADER");
		st.setValue("XBLNR", head.get("XBLNR"));
		st.setValue("GJAHR", head.get("GJAHR"));
		st.setValue("BLDAT", head.get("BLDAT"));
		st.setValue("BUDAT", head.get("BUDAT"));
		st.setValue("USNAM", head.get("USNAM"));
		st.setValue("LIFNR", head.get("LIFNR"));
		st.setValue("WAERS", head.get("WAERS"));
		st.setValue("RMWWR", head.get("RMWWR"));
		st.setValue("BKTXT", head.get("BKTXT"));
		st.setValue("REBZG", head.get("REBZG"));

        JCoTable ptItem = function.getTableParameterList().getTable("IT_ITEM");
        for (int i = 0; i < items.size(); i++) {
            ptItem.appendRow();
            ptItem.setRow(i);
            ptItem.setValue("EBELN", items.get(i).get("EBELN"));
            ptItem.setValue("WERKS", items.get(i).get("WERKS"));
            ptItem.setValue("EBELP", items.get(i).get("EBELP"));
            ptItem.setValue("KSCHL", items.get(i).get("KSCHL"));
            ptItem.setValue("TXZ01", items.get(i).get("TXZ01"));
            ptItem.setValue("MATNR", items.get(i).get("MATNR"));
            ptItem.setValue("WAERS", items.get(i).get("WAERS"));
            ptItem.setValue("ZFYSJ", items.get(i).get("ZFYSJ"));
            ptItem.setValue("KBETR", items.get(i).get("KBETR"));
            ptItem.setValue("ZFYSL", items.get(i).get("ZFYSL"));
            ptItem.setValue("ZSFYG", items.get(i).get("ZSFYG"));
        }

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, Object> result = new HashMap<String, Object>();
		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
			result = traversalField(result, iterator);
		}
		
		String[] outTblArr = { "RT_RETURN" };
		List<String> outTblList = Arrays.asList(outTblArr);
		for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator, "Z_TMS_INVOICE_HXJDJ", outTblList, null);
		}
		return result;
    }

	public Map<String, Object> Z_TMS_SET_ESTIMATE_STATUS(List<Map<String, Object>> items, InterMsgData interMsgData) throws EsbException {

		Log.getInstance().stdDebug("==================Z_TMS_SET_ESTIMATE_STATUS================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_TMS_SET_ESTIMATE_STATUS");

		JCoTable ptItem = function.getTableParameterList().getTable("PT_WEAKT");
		for (int i = 0; i < items.size(); i++) {
			ptItem.appendRow();
			ptItem.setRow(i);
			ptItem.setValue("EBELN", items.get(i).get("EBELN"));
			ptItem.setValue("WEAKT", items.get(i).get("WEAKT"));
		}

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, Object> result = new HashMap<String, Object>();
		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
			result = traversalField(result, iterator);
		}

		String[] outTblArr = { "PT_STATUS" };
		List<String> outTblList = Arrays.asList(outTblArr);
		for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator, outTblList);
		}
		return result;
	}
	
	public Map<String, Object> Z_TMS_GET_PO_ESTIMATE_STATUS(List<Map<String, Object>> items, InterMsgData interMsgData) throws EsbException {

		Log.getInstance().stdDebug("==================Z_TMS_GET_PO_ESTIMATE_STATUS================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_TMS_GET_PO_ESTIMATE_STATUS");

		JCoTable ptItem = function.getTableParameterList().getTable("PT_EBELN");
		for (int i = 0; i < items.size(); i++) {
			ptItem.appendRow();
			ptItem.setRow(i);
			ptItem.setValue("EBELN", items.get(i).get("EBELN"));
		}

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, Object> result = new HashMap<String, Object>();
//		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
//			result = traversalField(result, iterator);
//		}

		String[] outTblArr = { "PT_MIGO" };
		List<String> outTblList = Arrays.asList(outTblArr);
		for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator, outTblList);
		}
		return result;
	}

	public Map<String, Object> Z_MDM_HRP1003(Map<String, String> head, InterMsgData interMsgData) throws EsbException {

		Log.getInstance().stdDebug("==================Z_MDM_HRP1003================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_MDM_HRP1003");

//		JCoStructure st =  function.getImportParameterList().getValue("IT_LIST");
//		JCoTable st = function.getTableParameterList().getTable("IT_LIST");

		function.getImportParameterList().setValue("I_PERNR", head.get("I_PERNR"));
		function.getImportParameterList().setValue("I_AEDTM_S", head.get("I_AEDTM_S"));
		function.getImportParameterList().setValue("I_AEDTM_E", head.get("I_AEDTM_E"));

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, Object> result = new HashMap<String, Object>();
//		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
//			result = traversalField(result, iterator);
//		}

		String[] outTblArr = { "IT_LIST" };
		List<String> outTblList = Arrays.asList(outTblArr);
		for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator, "Z_MDM_HRP1003");
		}
		return result;
	}

	public Map<String, Object> Z_MDM_FI_READ(Map<String, String> head, InterMsgData interMsgData) throws EsbException {

		Log.getInstance().stdDebug("==================Z_MDM_FI_REA================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_MDM_FI_READ");

//		function.getImportParameterList().setValue("BWKEY", head.get("BWKEY"));

//		JCoTable ptItem = function.getTableParameterList().getTable("IT_LIST");
//		for (int i = 0; i < items.size(); i++) {
//			ptItem.appendRow();
//			ptItem.setRow(i);
//			ptItem.setValue("BWKEY", items.get(i).get("BWKEY"));
//			ptItem.setValue("NAME1", items.get(i).get("NAME1"));
//			ptItem.setValue("WERKS_PPCO", items.get(i).get("WERKS_PPCO"));
//			ptItem.setValue("WERKS_XUNI", items.get(i).get("WERKS_XUNI"));
//			ptItem.setValue("DISPO", items.get(i).get("DISPO"));
//			ptItem.setValue("PRDHA", items.get(i).get("PRDHA"));
//			ptItem.setValue("BKLAS", items.get(i).get("BKLAS"));
//			ptItem.setValue("BWTTY", items.get(i).get("BWTTY"));
//			ptItem.setValue("BESKZ", items.get(i).get("BESKZ"));
//			ptItem.setValue("SOBSL", items.get(i).get("SOBSL"));
//			ptItem.setValue("VPRSV", items.get(i).get("VPRSV"));
//			ptItem.setValue("PEINH", items.get(i).get("PEINH"));
//			ptItem.setValue("MLAST", items.get(i).get("MLAST"));
//			ptItem.setValue("EKALR", items.get(i).get("EKALR"));
//			ptItem.setValue("HKMAT", items.get(i).get("HKMAT"));
//			ptItem.setValue("KOSGR", items.get(i).get("KOSGR"));
//			ptItem.setValue("AWSLS", items.get(i).get("AWSLS"));
//			ptItem.setValue("LOSGR", items.get(i).get("LOSGR"));
//			ptItem.setValue("PRCTR", items.get(i).get("PRCTR"));
//			ptItem.setValue("TEXT", items.get(i).get("TEXT"));
//			ptItem.setValue("TEXT", items.get(i).get("TEXT"));
//			ptItem.setValue("TEXT", items.get(i).get("TEXT"));
//
//		}

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, Object> result = new HashMap<String, Object>();
//		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
//			result = traversalField(result, iterator);
//		}

		String[] outTblArr = { "IT_LIST" };
		List<String> outTblList = Arrays.asList(outTblArr);
		for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator, outTblList);
		}
		return result;
	}

	public Map<String, Object> Z_MDM_CHECK_MATNR(Map<String, String> head, InterMsgData interMsgData) throws EsbException {

		Log.getInstance().stdDebug("==================Z_MDM_CHECK_MATNR================");
		JCoFunction function = RfcManager.getInstance().getFunction("Z_MDM_CHECK_MATNR");

		function.getImportParameterList().setValue("I_MATNR", head.get("I_MATNR"));

		sendReqLog(interMsgData, "ESB", "SAP", function);
		long startTime = System.currentTimeMillis();

		RfcManager.getInstance().execute(function);

		long endTime = System.currentTimeMillis();
		int time = (int) (endTime - startTime);
		sendResLog(interMsgData, "SAP", "ESB", time, function);

		Map<String, Object> result = new HashMap<String, Object>();
		String[] outTblArr = { "IT_LIST" };
		List<String> outTblList = Arrays.asList(outTblArr);
		for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator, outTblList);
		}
		return result;
	}

    public Map<String, Object> Z_TR_PAYMENT_REQUEST(Map<String, String> head, InterMsgData interMsgData) throws EsbException {

        Log.getInstance().stdDebug("==================Z_TR_PAYMENT_REQUEST================");
        JCoFunction function = RfcManager.getInstance().getFunction("Z_TR_PAYMENT_REQUEST");
        function.getImportParameterList().setValue("STARTDATE", head.get("STARTDATE"));

        sendReqLog(interMsgData, "ESB", "SAP", function);
        long startTime = System.currentTimeMillis();

        RfcManager.getInstance().execute(function);

        long endTime = System.currentTimeMillis();
        int time = (int) (endTime - startTime);
        sendResLog(interMsgData, "SAP", "ESB", time, function);

        Map<String, Object> result = new HashMap<String, Object>();

        JCoTable jCoTable = function.getTableParameterList().getTable("IT_LIST");
        List resultList = new ArrayList();

        for (int i = 0; i < jCoTable.getNumRows(); i++) {
			Map retMap = new HashMap();
            jCoTable.setRow(i);
            for (JCoField fld : jCoTable) {
            	if (fld.getName().equals("ORG_PAY_DATE")
						|| fld.getName().equals("DATEVALUE1")
						|| fld.getName().equals("DATEVALUE2")){
					retMap.put(fld.getName(), convertDate(fld.getValue()));
				}else {
					retMap.put(fld.getName(), fld.getValue());
				}
            }
            resultList.add(retMap);
        }
		result.put("IT_LIST", resultList);

        return result;
    }

    public Map<String, Object> Z_TR_PAYMENT_REQUEST_RET(Map<String, String> head, List<Map<String, Object>> items, InterMsgData interMsgData) throws EsbException {

        Log.getInstance().stdDebug("==================Z_TR_PAYMENT_REQUEST_RET================");
        JCoFunction function = RfcManager.getInstance().getFunction("Z_TR_PAYMENT_REQUEST_RET");

        JCoTable ptItem = function.getTableParameterList().getTable("IT_LIST");
        for (int i = 0; i < items.size(); i++) {
            ptItem.appendRow();
            ptItem.setRow(i);
            ptItem.setValue("PAYNO", items.get(i).get("PAYNO"));
            ptItem.setValue("RET_CODE", items.get(i).get("RET_CODE"));
            ptItem.setValue("RET_MSG", items.get(i).get("RET_MSG"));
        }

        sendReqLog(interMsgData, "ESB", "SAP", function);
        long startTime = System.currentTimeMillis();

        RfcManager.getInstance().execute(function);

        long endTime = System.currentTimeMillis();
        int time = (int) (endTime - startTime);
        sendResLog(interMsgData, "SAP", "ESB", time, function);

        Map<String, Object> result = new HashMap<String, Object>();

		String[] outTblArr = { "ET_LIST" };
		List<String> outTblList = Arrays.asList(outTblArr);
		for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
			result = traversalTable(result, iterator, outTblList);
		}
        return result;
    }

//	public Map<String, Object> Z_TR_PAYMENT_RETURN(Map requestData, InterMsgData interMsgData) throws EsbException {
//		String rfcName = (String) requestData.get("Operation");
//		Integer headType = (Integer) requestData.get("HeadType"); // 输入类型
//		String headName = (String) requestData.get("HeadName");
//		Map<String, Object> head = (Map<String, Object>) requestData.get("Head");
//
//		Log.getInstance().stdDebug("==================Z_TR_PAYMENT_RETURN================");
//		JCoFunction function = RfcManager.getInstance().getFunction(rfcName);
//
//		JCoTable table = function.getTableParameterList().getTable(headName);
//		table.appendRow();
//		table.setRow(0);
//		Iterator it = head.entrySet().iterator();
//		while (it.hasNext()){
//			Map.Entry entry = (Map.Entry) it.next();
//			switch ((String) entry.getKey()){
//				case "DATASOURCE":
//					table.setValue("SOURCE", entry.getValue());
//					break;
//				case "CURRENCYNO":
//					table.setValue("WAERS_ORG", entry.getValue());
//					break;
//				case "PAYAMOUNT":
//					table.setValue("RWBTR_ORG", entry.getValue());
//					break;
//				case "ACT_PAY_MODE":
//					table.setValue("RZAWE", entry.getValue());
//					break;
//				case "ACT_PAY_DATE":
//					table.setValue("BUDAT", entry.getValue());
//					break;
//				case "ACT_PAYAMOUNT":
//					table.setValue("RWBTR", entry.getValue());
//					break;
//				case "ACT_CURRENCYNO":
//					table.setValue("WAERS", entry.getValue());
//					break;
//				case "EXCH_RATE":
//					table.setValue("KURSF", entry.getValue());
//					break;
//				case "RECE_ACC_NO":
//					table.setValue("BANKN_ALL", entry.getValue());
//					break;
//				case "RECE_ACC_NAME":
//					table.setValue("KOINH", entry.getValue());
//					break;
//				case "CREATETIME":
//					table.setValue("REDAT", entry.getValue());
//					break;
//				default:
//					table.setValue((String) entry.getKey(), entry.getValue());
//					break;
//			}
//		}
//
//		sendReqLog(interMsgData, "ESB", "SAP", function);
//		long startTime = System.currentTimeMillis();
//
//		RfcManager.getInstance().execute(function);
//
//		long endTime = System.currentTimeMillis();
//		int time = (int) (endTime - startTime);
//		sendResLog(interMsgData, "SAP", "ESB", time, function);
//
//		// 返回取数表名 导出数据类型 0:字段Filed 1:结构Structure 2:表Table 3:既有表又有字段输出
//		Integer exportParamsType = (Integer) requestData.get("ExportParamsType"); // 输入类型
//		List<String> exportTableName = (List<String>) requestData.get("ExportTableName");
//
//		Map<String, Object> result = new HashMap<String, Object>();
//		exportParamsType = exportParamsType == null ? 2 : exportParamsType;
//		if (exportParamsType.equals(0) || exportParamsType.equals(1)){
//			// 导出数据类型 0:字段Filed 1:结构Structure
//			for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
//				result = traversalTable(result, iterator, rfcName, exportTableName);
//			}
//		}else if(exportParamsType.equals(2)) {
//			// 导出数据类型 2: 表
//			for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
//				result = traversalTable(result, iterator, rfcName, exportTableName);
//			}
//		} else if (exportParamsType.equals(3)) {
//			// 导出数据类型 3: 字段 + 表
//			for (Iterator<JCoField> iterator = function.getExportParameterList().iterator(); iterator.hasNext();) {
//				result = traversalTable(result, iterator, rfcName, exportTableName);
//			}
//			for (Iterator<JCoField> iterator = function.getTableParameterList().iterator(); iterator.hasNext();) {
//				result = traversalTable(result, iterator, rfcName, exportTableName);
//			}
//		}
//		return result;
//	}

	private Map traversalField(Map resultMap, Iterator<JCoField> iterator) {
        JCoField jCoField = iterator.next();
        if (jCoField.isTable()) {
            JCoTable table = jCoField.getTable();
            List resultList = new ArrayList();
            for (int i = 0, len = table.getNumRows(); i < len; i++) {
                Map retMap = new HashMap();
                table.setRow(i);
                for (JCoRecordFieldIterator jCoRecordFieldIterator = table.getRecordFieldIterator(); jCoRecordFieldIterator.hasNextField(); ) {
                    JCoField field = jCoRecordFieldIterator.nextRecordField();
                    retMap.put(field.getName(), field.getValue());
                }
                resultList.add(retMap);
            }
            resultMap.put(jCoField.getName(), resultList);
        } else if (jCoField.isStructure()) {
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

	private Map traversalTable(Map resultMap, Iterator<JCoField> iterator) {
		return traversalTable(resultMap, iterator, null, null, null);
	}

	private Map traversalTable(Map resultMap, Iterator<JCoField> iterator, List<String> outTblList) {
		return traversalTable(resultMap, iterator, null, outTblList, null);
	}

	private Map traversalTable(Map resultMap, Iterator<JCoField> iterator, String function) {
		return traversalTable(resultMap, iterator, function, null, null);
	}

	private Map traversalTable(Map resultMap, Iterator<JCoField> iterator, String function,List<String> outTblList, JSONObject exportParameterConfig) {
        JCoField jCoField = iterator.next();
        if (jCoField.isTable()) {
			if (outTblList != null) {
				if (!outTblList.contains(jCoField.getName())) {
					return resultMap;
				}
			}
            JCoTable table = jCoField.getTable();
            List resultList = new ArrayList();
            for (int i = 0, len = table.getNumRows(); i < len; i++) {
                Map retMap = new HashMap();
                table.setRow(i);
                for (JCoRecordFieldIterator jCoRecordFieldIterator = table.getRecordFieldIterator(); jCoRecordFieldIterator.hasNextField(); ) {
                    JCoField field = jCoRecordFieldIterator.nextRecordField();
                    //Log.getInstance().stdDebug("function="+function+",table="+jCoField.getName()+",field="+field.getName()+",value="+field.getValue());
                    if (StringUtils.isNotEmpty(function)) {
                    	formatDateField(retMap, function, jCoField, table, field, exportParameterConfig);
                    }else{
						retMap.put(field.getName(), field.getValue());
					}
                }
                resultList.add(retMap);
            }
            resultMap.put(jCoField.getName(), resultList);
		} else if (jCoField.isStructure()) {
			JCoStructure jCoStructure = jCoField.getStructure();
			Map resultStructureMap = new HashMap();
			for (JCoFieldIterator jCoFieldIterator = jCoStructure.getFieldIterator(); jCoFieldIterator.hasNextField();) {
				JCoField jcf = jCoFieldIterator.nextField();
				// resultStructureMap.put(jcf.getName(), jcf.getValue());
				// 从配置表读取
				JSONObject dateConfig = null;
				JSONObject timeConfig = null;
				if(null != exportParameterConfig){
					dateConfig = exportParameterConfig.getJSONObject("Date");
					timeConfig = exportParameterConfig.getJSONObject("Time");
				}
				if (null != dateConfig && StringUtils.isNotEmpty(dateConfig.getString(jcf.getName()))){
					String date = DateUtil.convertDate(jcf.getValue(), dateConfig.getString(jcf.getName()));
					resultStructureMap.put(jcf.getName(), date);
				}else if (null != timeConfig && StringUtils.isNotEmpty(timeConfig.getString(jcf.getName()))){
					String time = DateUtil.convertDate(jcf.getValue(), timeConfig.getString(jcf.getName()));
					resultStructureMap.put(jcf.getName(), time);
				}else {
					resultStructureMap.put(jcf.getName(), jcf.getValue());
				}
			}
			resultMap.put(jCoField.getName(), resultStructureMap);
		} else {
			// 从配置表读取
			JSONObject dateConfig = null;
			JSONObject timeConfig = null;
			if(null != exportParameterConfig){
				dateConfig = exportParameterConfig.getJSONObject("Date");
				timeConfig = exportParameterConfig.getJSONObject("Time");
			}
			if (null != dateConfig && StringUtils.isNotEmpty(dateConfig.getString(jCoField.getName()))){
				String date = DateUtil.convertDate(jCoField.getValue(), dateConfig.getString(jCoField.getName()));
				resultMap.put(jCoField.getName(), date);
			}else if (null != timeConfig && StringUtils.isNotEmpty(timeConfig.getString(jCoField.getName()))){
				String time = DateUtil.convertDate(jCoField.getValue(), timeConfig.getString(jCoField.getName()));
				resultMap.put(jCoField.getName(), time);
			}else {
				resultMap.put(jCoField.getName(), jCoField.getValue());
			}
            // resultMap.put(jCoField.getName(), jCoField.getValue());
        }
        return resultMap;
    }

	private Map formatDateField(Map retMap, String function, JCoField jCoField, JCoTable table, JCoField field, JSONObject exportParameterConfig) {
		if ("Z_SPMS_GETHWDN".equals(function)) {
			// System.out.println("=========table.getRecordMetaData().getName()====="+table.getRecordMetaData().getName());
			String[] tblArr = RfcTableMappingUtil.rfcTableMapping.get(table.getRecordMetaData().getName());
			if (tblArr == null) {
				Log.getInstance().stdDebug("=========Warning Z_SPMS_GETHWDN: RfcTableMappingUtil.rfcTableMapping.get(table.getRecordMetaData().getName()) res is null. RecordMetaDataName=" + table.getRecordMetaData().getName());
			} else if (Arrays.asList(tblArr).contains(field.getName().trim())) {
				if ("AEDAT".equals(field.getName())) {
					String date = convertDate(field.getValue());
					retMap.put(field.getName(), date);
				} else if ("ERDAT".equals(field.getName())) {
					String date = convertDate(field.getValue());
					retMap.put(field.getName(), date);
				} else if ("LFDAT".equals(field.getName())) {
					String date = convertDate(field.getValue());
					retMap.put(field.getName(), date);
				}else {
					retMap.put(field.getName(), field.getValue());
				}
			}
		} else if ("Z_SPMS_READ_DATA".equals(function)) {
			if ("DGDAT".equals(field.getName())) {
				String date = convertDate(field.getValue());
				retMap.put(field.getName(), date);
			} else {
				retMap.put(field.getName(), field.getValue());
			}
		} else if ("Z_SPMS_GETDN_REST".equals(function)) {
//			if (Arrays.asList(RfcTableMappingUtil.rfcGetDnTableMapping.get(jCoField.getName())).contains(field.getName().trim())) {
//			}
			if ("LFDAT".equals(field.getName())) {
				String date = convertDate(field.getValue());
				retMap.put(field.getName(), date);
			} else if ("ERDAT".equals(field.getName())) {
				String date = convertDate(field.getValue());
				retMap.put(field.getName(), date);
			} else {
				retMap.put(field.getName(), field.getValue());
			}
		} else if ("Z_SRM_GET_PRINFO".equals(function)) {
			if ("LFDAT".equals(field.getName())) {
				String date = convertDate(field.getValue() != null ? field.getValue().toString() : null);
				retMap.put(field.getName(), date);
			} else if ("ZDATUV".equals(field.getName())) {
				String date = convertDate(field.getValue() != null ? field.getValue().toString() : null);
				retMap.put(field.getName(), date);
			} else if ("ZDATUB".equals(field.getName())) {
				String date = convertDate(field.getValue() != null ? field.getValue().toString() : null);
				retMap.put(field.getName(), date);
			} else if ("BADAT".equals(field.getName())) {
				String date = convertDate(field.getValue() != null ? field.getValue().toString() : null);
				retMap.put(field.getName(), date);
			} else if ("ZAUDIDATE".equals(field.getName())) {
				String date = convertDate(field.getValue() != null ? field.getValue().toString() : null);
				retMap.put(field.getName(), date);
			} else {
				retMap.put(field.getName(), field.getValue());
			}

		} else if ("Z_SRM_GET_PRINFO_02".equals(function)) {
			if ("LFDAT".equals(field.getName())) {
				String date = convertDate(field.getValue() != null ? field.getValue().toString() : null);
				retMap.put(field.getName(), date);
			} else if ("ZDATUV".equals(field.getName())) {
				String date = convertDate(field.getValue() != null ? field.getValue().toString() : null);
				retMap.put(field.getName(), date);
			} else if ("ZDATUB".equals(field.getName())) {
				String date = convertDate(field.getValue() != null ? field.getValue().toString() : null);
				retMap.put(field.getName(), date);
			} else if ("BADAT".equals(field.getName())) {
				String date = convertDate(field.getValue() != null ? field.getValue().toString() : null);
				retMap.put(field.getName(), date);
			} else if ("ZAUDIDATE".equals(field.getName())) {
				String date = convertDate(field.getValue() != null ? field.getValue().toString() : null);
				retMap.put(field.getName(), date);
			} else {
				retMap.put(field.getName(), field.getValue());
			}

		} else if ("Z_MDM_HRP1002".equals(function)) {
			if ("BEGDA".equals(field.getName())) {
				String date = convertDate(field.getValue() != null ? field.getValue().toString() : null);
				retMap.put(field.getName(), date);
			} else if ("ENDDA".equals(field.getName())) {
				String date = convertDate(field.getValue() != null ? field.getValue().toString() : null);
				retMap.put(field.getName(), date);
			} else {
				retMap.put(field.getName(), field.getValue());
			}
		} else if ("Z_SRM_GET_PO_ZZ".equals(function)) {
			if ("EINDT".equals(field.getName())) {
				String date = convertDate(field.getValue() != null ? field.getValue().toString() : null);
				retMap.put(field.getName(), date);
			} else if ("BEDAT".equals(field.getName())) {
				String date = convertDate(field.getValue() != null ? field.getValue().toString() : null);
				retMap.put(field.getName(), date);
			} else {
				retMap.put(field.getName(), field.getValue());
			}
		} else if ("Z_TMS_INVOICE_CHECK".equals(function)) {
			if ("BLDAT".equals(field.getName())) {
				String date = convertDate(field.getValue() != null ? field.getValue().toString() : null);
				retMap.put(field.getName(), date);
			} else {
				retMap.put(field.getName(), field.getValue());
			}
		} else if ("Z_TMS_INVOICE_HXJDJ".equals(function)) {
			if ("BLDAT".equals(field.getName())) {
				String date = convertDate(field.getValue() != null ? field.getValue().toString() : null);
				retMap.put(field.getName(), date);
			} else {
				retMap.put(field.getName(), field.getValue());
			}
		} else if ("Z_SRM_GET_GOODS_IN".equals(function)) {
			if ("BLDAT".equals(field.getName()) || "BUDAT".equals(field.getName()) || "CPUDT".equals(field.getName())) {
				String date = DateUtil.convertDate(field.getValue(), "yyyy-MM-dd");
				retMap.put(field.getName(), date);
			} else if ("CPUTM".equals(field.getName())) {
				String date = DateUtil.formatDate(DateUtil.getDate(field.getValue().toString()), "HH:mm:ss");
				retMap.put(field.getName(), date);
			} else {
				retMap.put(field.getName(), field.getValue());
			}
		} else if ("Z_SRM_PAYMENT_QSRQ".equals(function)) {
			if ("DATE_FIQS".equals(field.getName())) {
				String date = DateUtil.convertDate(field.getValue(), "yyyy-MM-dd");
				retMap.put(field.getName(), date);
			} else {
				retMap.put(field.getName(), field.getValue());
			}
		} else if ("Z_SRM_RETURN_PAYMENY".equals(function)) {
			if ("BUDAT".equals(field.getName()) || "ERDAT".equals(field.getName())) {
				String date = DateUtil.convertDate(field.getValue(), "yyyy-MM-dd");
				retMap.put(field.getName(), date);
			} else if ("CPUTM".equals(field.getName())) {
				String date = DateUtil.convertDate(field.getValue(), "HH:mm:ss");
				retMap.put(field.getName(), date);
			} else {
				retMap.put(field.getName(), field.getValue());
			}
		} else if ("Z_SRM_SOA_SYNC".equals(function)) {
			if ("ZFBDT".equals(field.getName())) {
				String date = DateUtil.convertDate(field.getValue(), "yyyy-MM-dd");
				retMap.put(field.getName(), date);
			}else if ("RETDUEDT".equals(field.getName())) {
				String date = DateUtil.convertDate(field.getValue(), "yyyy-MM-dd");
				retMap.put(field.getName(), date);
			}  else {
				retMap.put(field.getName(), field.getValue());
			}
		} else if ("Z_MDM_HRP1003".equals(function)) {
			if ("BEGDA".equals(field.getName())) {
				String date = convertDate(field.getValue() != null ? field.getValue().toString() : null);
				retMap.put(field.getName(), date);
			} else if ("AEDTM".equals(field.getName())) {
				String date = convertDate(field.getValue() != null ? field.getValue().toString() : null);
				retMap.put(field.getName(), date);
			} else {
				retMap.put(field.getName(), field.getValue());
			}
		} else {
			// 从配置表读取
			JSONObject dateConfig = null;
			JSONObject timeConfig = null;
			if(null != exportParameterConfig){
				dateConfig = exportParameterConfig.getJSONObject("Date");
				timeConfig = exportParameterConfig.getJSONObject("Time");
			}
			if (null != dateConfig && StringUtils.isNotEmpty(dateConfig.getString(field.getName()))){
				String date = DateUtil.convertDate(field.getValue(), dateConfig.getString(field.getName()));
				retMap.put(field.getName(), date);
			}else if (null != timeConfig && StringUtils.isNotEmpty(timeConfig.getString(field.getName()))){
				String time = DateUtil.convertDate(field.getValue(), timeConfig.getString(field.getName()));
				retMap.put(field.getName(), time);
			}else {
				retMap.put(field.getName(), field.getValue());
			}
		}
		return retMap;
	}
	
	@Override
	public boolean isValid() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public void close() {
		// TODO Auto-generated method stub

	}

	@Override
	public void handleSendMsgException(DataBean databean, Exception ex) {
		// TODO Auto-generated method stub

	}

	@Override
	public void handleReceiveMsgException(DataBean databean, Exception ex) {
		// TODO Auto-generated method stub

	}

	@Override
	public void refresh() throws EsbException {
		// TODO Auto-generated method stub

	}
	
	
	private  String tranfersInfoMessage(JCoFunction function){
		
		
		List<String> messageList = new ArrayList<String>();
		if (function.getTableParameterList() != null && function.getTableParameterList().getFieldCount() > 0) {
			for(int i = 0; i < function.getTableParameterList().getFieldCount(); ++i) {
				String tablename = function.getTableParameterList().getMetaData().getName(i);
				JCoTable returnuTable = function.getTableParameterList().getTable(tablename);
				if (returnuTable.getNumRows() > 0) {
					do {
						JCoFieldIterator e = returnuTable.getFieldIterator();

						while(e.hasNextField()) {
							JCoField field = e.nextField();
							String fieldValue = field.getString();
							if ("MESSAGE".equals(field.getName())){
								messageList.add(fieldValue);
							}
						}

					} while(returnuTable.nextRow());
				}

			}
		}

		
		HashSet h = new HashSet(messageList);
		messageList.clear();
		messageList.addAll(h);
		if(messageList.isEmpty())
		{
			return null;
		}
		return Joiner.on("").join(messageList);
	}
	
	private void getSapData(Map<String, Object> result, JCoParameterList outputParam, String functionId,InterMsgData interMsgData) {
		JCoMetaData md = outputParam.getMetaData();

		SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd");
		String date = df.format(new Date());
		Log.getInstance().stdDebug(LOG_PREFIX + " md.getFieldCount() = ======== " + md.getFieldCount());
		List<Object[]> stringList = new ArrayList<>();//定义一个list对象
		String transactionID = interMsgData.getTransactionID();

		for (int i = 0; i < md.getFieldCount(); i++) {
			String table = md.getName(i);
			Log.getInstance().stdDebug(LOG_PREFIX + " table = ======== " + table);

			JCoTable bt = outputParam.getTable(table);

			int fileCount = 0;
//			String tablename = funStr + "_" + table;
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
//					sendMQInit(sb.toString(), fileName, tablename, isSend);
//					isSend = false;
					sb.delete(0, sb.length());
					fileCount++;
					fileName = table + "_" + date + "_" + fileCount + ".csv";

					for (int k = 0; k < fieldCount; k++) {
						sb.append(bt.getMetaData().getName(k));

						if (k == fieldCount - 1) {
//							isSend = true;
							continue;
						} else {
							sb.append(separator);
						}
					}

					sb.append(NEW_LINE);
				}

				for (int k = 0; k < fieldCount; k++) {
					sb.append(bt.getString(k));

					if (k == fieldCount - 1) {
//						isSend = true;
						continue;
					} else {
						sb.append(separator);
					}
				}

				sb.append(NEW_LINE);
			}
			
			interMsgData.setMsgContent(sb.toString());
			interMsgData.setMsgOrder(interMsgData.getMsgOrder() + 1);
			interMsgData.setCreateTime(new Date());

			String resultMsg = JSON.toJSONString(interMsgData, SerializerFeature.WriteMapNullValue);
			Log.getInstance().stdDebug("transactionID=" + transactionID + ",fileName=" + fileName + "resultMsg=" + resultMsg);

			sendMQInit(resultMsg, fileName, functionId, true);
			String reqdataTemp = sb.toString().replaceAll("\\n", ConstantUtil.SEPARATOR);
            String[] reqdatas = reqdataTemp.split(ConstantUtil.SEPARATOR);
            String stcolumnStr = StringUtils.join(reqdatas[0].split("[$],[$]"), ",");
            String[] stringColumns = stcolumnStr.split(",");
			for (int x = 0; x < reqdatas.length; x++) {
                String[] ob = new String[stringColumns.length];
                if (reqdatas[x].endsWith("$")) {
                    reqdatas[x] = reqdatas[x].concat(" ");
                }
                String[] bbb = reqdatas[x].split("[$],[$]");
                System.arraycopy(bbb, 0, ob, 0, bbb.length);
                stringList.add(ob);
            }
            HashMap<String, List<Object[]>> resultMap = new HashMap<>();
            resultMap.put(functionId, stringList);
			net.sf.json.JSONObject mapObject = net.sf.json.JSONObject.fromObject(resultMap);
            result.put("reqData", mapObject.toString());
		}
	}
	
	private void sendMQInit(String str, String fileName, String tablename, boolean isSend) {
		// TODO Auto-generated method stub

		Log.getInstance().stdDebug("send file use internal buffer " + isSend);

		if (isSend) {
			ServiceRequest servReq = new ServiceRequest();

			Map<String, String> tmp = new HashMap<String, String>();
			tmp.put("reqData", str);
			tmp.put("reqFileName", fileName);
			tmp.put("reqTableName", tablename);

			String json = JSON.toJSONString(tmp, SerializerFeature.WriteMapNullValue);
			Log.getInstance().stdDebug("sendmessage json====" + json);

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
				Log.getInstance().stdError("setTargetCon failed");
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
				Log.getInstance().stdDebug("send file use internal buffer error", e1);
			}
			Log.getInstance().stdDebug("send file use internal buffer ing");
		}
		Log.getInstance().stdDebug("send file use internal buffer end ");

	}
	
	private InterMsgData getReqInfo( String methodName, String appId, String applicationId, String transactionId, String msgContent,String methodNameEn) {
		InterMsgData interMsgData = new InterMsgData();
		interMsgData.setIp(NetworkUtil.getIP());
		interMsgData.setClassName("SAPClientCon");
		interMsgData.setMethodName(methodName);
		
		// POS接口没有AppId。Z_POST_MIGO_CREATE/Z_POS_PO_CREATE/Z_POS_DO_CREATE2/Z_POS_MB1A_CREATE2/Z_POS_MBST_CREATE/Z_POS_ORDER_MAT_CREATE/Z_POS_SO_CREATE
		if (StringUtils.isEmpty(appId) || "null".equals(appId)) {
			if (StringUtils.isNotEmpty(methodName) && methodName.startsWith("Z_POS")) {
				appId = "POS";
			}
		}		
		interMsgData.setTime(0);
		interMsgData.setReqAppId(appId);
		interMsgData.setApplicationId(applicationId);
		interMsgData.setTransactionID(transactionId);
		interMsgData.setMsgFrom(appId);
		interMsgData.setMsgTo("ESB");
		if (msgContent.length() > ConstantUtil.MSG_MAX_LENGTH) {
			Log.getInstance().stdDebug("msgContent=" + msgContent);
			msgContent = msgContent.substring(0, ConstantUtil.MSG_MAX_LENGTH) + "...";
		}
		interMsgData.setMsgContent(msgContent);
		interMsgData.setMethodNameEn(methodNameEn);
		interMsgData.setCreateTime(new Date());
		interMsgData.setMsgOrder(-1);
		return interMsgData;
	}

	private void sendReqLog(InterMsgData interMsgData, String from, String to, JCoFunction function) {
		try {
			String reqParameter = RfcManager.getInstance().getReqParameter(function);
			interMsgData.setMsgFrom(from);
			interMsgData.setMsgTo(to);
			interMsgData.setOperation(URLEncoder.encode("向SAP发送函数调用请求", "utf-8"));
			interMsgData.setTime(0);
			if (reqParameter.length() > ConstantUtil.MSG_MAX_LENGTH) {
				Log.getInstance().stdDebug("消息内容大于指定长度" + ConstantUtil.MSG_MAX_LENGTH +",TransactionID="+interMsgData.getTransactionID()+ "reqParameter=" + reqParameter);
				reqParameter = reqParameter.substring(0, ConstantUtil.MSG_MAX_LENGTH) + "...";
			}
			interMsgData.setMsgContent(reqParameter);
			interMsgData.setCreateTime(new Date());
			interMsgData.setMsgOrder(interMsgData.getMsgOrder() + 1);

			String reqLog = JSON.toJSONString(interMsgData, SerializerFeature.WriteMapNullValue);

			String dbLog = (String) cm.get("DB_LOG");
			if (!"1".equals(dbLog) || noDbLogOptList.contains(interMsgData.getMethodName())) {
				Log.getInstance().stdDebug(reqLog);
				return;
			}

			new Thread() {
				@Override
				public void run() {
					sendMQInit(reqLog, "ReqLog", "ReqLog", true);
				}
			}.start();

		} catch (Exception e) {
			Log.getInstance().stdError(e);
		}
	}
	
	private void sendReqLog(InterMsgData interMsgData, String from, String to, String msgContent,String opration) {
		try {
			interMsgData.setMsgFrom(from);
			interMsgData.setMsgTo(to);
			interMsgData.setTime(0);
			interMsgData.setOperation(URLEncoder.encode(opration, "utf-8"));
			if (msgContent.length() > ConstantUtil.MSG_MAX_LENGTH) {
				Log.getInstance().stdDebug("消息内容大于指定长度" + ConstantUtil.MSG_MAX_LENGTH +",TransactionID="+interMsgData.getTransactionID()+ ",msgContent=" + msgContent);
				msgContent = msgContent.substring(0, ConstantUtil.MSG_MAX_LENGTH) + "...";
			}
			interMsgData.setMsgContent(msgContent);
			interMsgData.setCreateTime(new Date());
			interMsgData.setMsgOrder(interMsgData.getMsgOrder() + 1);
			String reqLog = JSON.toJSONString(interMsgData, SerializerFeature.WriteMapNullValue);

			String dbLog = (String) cm.get("DB_LOG");
			if (!"1".equals(dbLog) || noDbLogOptList.contains(interMsgData.getMethodName())) {
				Log.getInstance().stdDebug(reqLog);
				return;
			}

			new Thread() {
				@Override
				public void run() {
					sendMQInit(reqLog, "ReqLog", "ReqLog", true);
				}
			}.start();
		} catch (Exception e) {
			Log.getInstance().stdError(e);
		}
	}

	private void sendResLog(InterMsgData interMsgData, String from, String to, int time, JCoFunction function) {
		try {
			interMsgData.setMsgFrom(from);
			interMsgData.setMsgTo(to);
			interMsgData.setTime(time);
			interMsgData.setOperation(URLEncoder.encode("收到SAP函数返回结果", "utf-8"));
			String resultMsg = RfcManager.getInstance().getResultMsg(function);
			if (resultMsg.length() > ConstantUtil.MSG_MAX_LENGTH) {
				Log.getInstance().stdDebug("消息内容大于指定长度" + ConstantUtil.MSG_MAX_LENGTH +",TransactionID="+interMsgData.getTransactionID()+ "，resultMsg=" + resultMsg);
				resultMsg = resultMsg.substring(0, ConstantUtil.MSG_MAX_LENGTH) + "...";
			}
			interMsgData.setMsgContent(resultMsg);
			interMsgData.setCreateTime(new Date());
			interMsgData.setMsgOrder(interMsgData.getMsgOrder() + 1);
			String resLog = JSON.toJSONString(interMsgData, SerializerFeature.WriteMapNullValue);

			String dbLog = (String) cm.get("DB_LOG");
			if (!"1".equals(dbLog) || noDbLogOptList.contains(interMsgData.getMethodName())) {
				Log.getInstance().stdDebug(resLog);
				return;
			}

			new Thread() {
				@Override
				public void run() {
					sendMQInit(resLog, "ResLog", "ResLog", true);
				}
			}.start();
		} catch (Exception e) {
			Log.getInstance().stdError(e);
		}
	}
	
	private void sendResLog(InterMsgData interMsgData, String from, String to, int time, String msgContent, String operation) {
		try {
			interMsgData.setMsgFrom(from);
			interMsgData.setMsgTo(to);
			interMsgData.setTime(time);
			interMsgData.setOperation(URLEncoder.encode(operation, "utf-8"));
			if (msgContent.length() > ConstantUtil.MSG_MAX_LENGTH) {
				Log.getInstance().stdDebug("消息内容大于指定长度" + ConstantUtil.MSG_MAX_LENGTH +",TransactionID="+interMsgData.getTransactionID()+ "，msgContent=" + msgContent);
				msgContent = msgContent.substring(0, ConstantUtil.MSG_MAX_LENGTH) + "...";
			}
			interMsgData.setMsgContent(msgContent);
			interMsgData.setCreateTime(new Date());
			interMsgData.setMsgOrder(interMsgData.getMsgOrder() + 1);
			String resLog = JSON.toJSONString(interMsgData, SerializerFeature.WriteMapNullValue);

			String dbLog = (String) cm.get("DB_LOG");
			if (!"1".equals(dbLog) || noDbLogOptList.contains(interMsgData.getMethodName())) {
				Log.getInstance().stdDebug(resLog);
				return;
			}

			new Thread() {
				@Override
				public void run() {
					sendMQInit(resLog, "ResLog", "ResLog", true);
				}
			}.start();
		} catch (Exception e) {
			Log.getInstance().stdError(e);
		}
	}

	private HashMap<String, List<String[]>> getFunctionDate(JCoFunction function, String functionName) {
		HashMap<String, List<String[]>> data = new HashMap<>();

		JCoParameterList outputParam = function.getTableParameterList();
		JCoMetaData md = outputParam.getMetaData();

		for (int i = 0; i < md.getFieldCount(); i++) {
			String table = md.getName(i);
//			Log.getInstance().stdDebug("functionName=" + functionName + ",i=" + i + ", table = ======== " + table);

			JCoTable bt = outputParam.getTable(table);

			List<String> columns = new ArrayList<>();
			int fieldCount = bt.getMetaData().getFieldCount();
			for (int j = 0; j < fieldCount; j++) {
				columns.add(bt.getMetaData().getName(j));
			}

			List<String[]> list = new ArrayList<>();// 行
			String[] cls = columns.toArray(new String[columns.size()]);
			list.add(cls);

			for (int j = 0; j < bt.getNumRows(); j++) {
				bt.setRow(j);

				List<String> rs = new ArrayList<>();// 列
				for (int k = 0; k < fieldCount; k++) {
					rs.add(bt.getString(k).replaceAll("\n", ""));
				}

				String[] resultArrs = rs.toArray(new String[rs.size()]);
				list.add(resultArrs);
			}

			data.put(functionName + "_" + table, list);
		}
		return data;
	}

	public static String convertDate(Object str) {
        String res = "";
        try {
        	if(str==null || StringUtils.isEmpty(str.toString())){
        		return res;
        	}
        	
            SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd");
            Date date = new Date(str.toString());
            res = format.format(date);
        } catch (Exception e) {
        	Log.getInstance().stdError(e);
        }
        return res;
    }

	public String getRedisValue(String key) {
		String value = null;
		if (StringUtils.isNotEmpty(ConstantUtil.configMap.get(key))) {
			value = ConstantUtil.configMap.get(key);
		} else {
			String redisHost = (String) cm.get("redis.host");
			Jedis jedis = new Jedis(redisHost);
			jedis.auth((String) cm.get("redis.password"));
			value = jedis.get(key);
			jedis.close();
		}
		return value;
	}

//	private JCoFunction getFunctionTest(String functionName) {
//		JCoFunction function = null;
//		try {
//			Properties properties = new Properties();
//			// 开发
//			properties.setProperty("jco.client.user", "IT-GTS");
//			properties.setProperty("jco.client.passwd", "sundapos2018");
//			properties.setProperty("jco.client.lang", "en");
//			properties.setProperty("jco.client.client", "310");
//			properties.setProperty("jco.client.sysnr", "10");
//			properties.setProperty("jco.client.ashost", "153.95.192.113");
//			properties.setProperty("jco.destination.peak_limit", "10");
//			properties.setProperty("jco.destination.pool_capacity", "10");
//			properties.setProperty("jco.destination.r3name", "STQ");
//			JCOProvider provider = new JCOProvider();
//			Environment.registerDestinationDataProvider(provider);
//			provider.changePropertiesForABAP_AS("ABAP_AS_POOL", properties);
//			JCoDestination tmp;
//			tmp = JCoDestinationManager.getDestination("ABAP_AS_POOL");
//			JCoRepository tmpre = tmp.getRepository();
//			JCoFunctionTemplate tmpfuntemplate = tmpre.getFunctionTemplate(functionName);
//			function = tmpfuntemplate.getFunction();
//		} catch (Exception e) {
//			e.printStackTrace();
//		}
//		return function;
//	}
//	
//    private  void executeTest(JCoFunction function) {
//    	Log.getInstance().stdDebug(LOG_PREFIX + " SAP Function Name : " + function.getName());
//        try {
//        	JCoDestination destination = JCoDestinationManager.getDestination("ABAP_AS_POOL");
//            function.execute(destination);
//        } catch (JCoException e) {
//            e.printStackTrace();
//        }
//    }
}
