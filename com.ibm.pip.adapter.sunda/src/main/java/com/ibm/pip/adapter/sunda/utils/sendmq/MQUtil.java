package com.ibm.pip.adapter.sunda.utils.sendmq;

import java.util.HashMap;
import java.util.Map;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.ibm.pip.framework.buffer.MsgBuffer;
import com.ibm.pip.framework.config.ConfigManager;
import com.ibm.pip.framework.exception.EsbException;
import com.ibm.pip.framework.messageobject.ServiceRequest;
import com.ibm.pip.util.log.Log;

public class MQUtil {
	
	
	public static void sendMQInit(String str, String fileName, String tablename, String targetcon, MsgBuffer receiveBuffer) {
		// TODO Auto-generated method stub
		Log.getInstance().stdDebug("send file use internal buffer");

			ServiceRequest servReq = new ServiceRequest();
			
			Map<String, String> tmp = new HashMap<String,String>();
			tmp.put("reqData", str);
			tmp.put("reqFileName", fileName);
			tmp.put("reqTableName", tablename);
			
			
			String json = JSON.toJSONString(tmp, SerializerFeature.WriteMapNullValue);
			Log.getInstance().stdDebug("MQUtil sendmessage json=" + json);
			

			json = json.replaceAll("&", "&amp;");
			json = json.replaceAll("<", "&lt;");
			json = json.replaceAll(">", "&gt;");
			json = json.replaceAll("'", "&apos;");
			json = json.replaceAll("\"", "&quot;");
			
			String sendmessage = "<DATA>" + json + "</DATA>";
			

			servReq.setRequest(sendmessage.getBytes());
			Log.getInstance().stdDebug("send file use internal buffer ing");
			try {
				servReq.setTargetCon((String) ConfigManager.getInstance().get("targetcon"));
			} catch (EsbException e) {
				Log.getInstance().stdError("setTargetCon failed"+e.toString());
				Log.getInstance().stdError(e);
			}
			// messageType=0 stand for standard request process
			servReq.setMessageType(0);
			servReq.setExecId("00010000000002");
			receiveBuffer.put(servReq);

			
			
		Log.getInstance().stdDebug("send file use internal buffer end ");

	}

}
