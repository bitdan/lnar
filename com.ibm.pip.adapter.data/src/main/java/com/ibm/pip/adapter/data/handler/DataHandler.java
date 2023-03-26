package com.ibm.pip.adapter.data.handler;

import java.util.HashMap;
import java.util.Map;

import oracle.net.ns.Communication;

import com.alibaba.fastjson.JSON;
import com.ibm.pip.adapter.connection.redisclient.SessionMap;
import com.ibm.pip.adapter.exec.IEventAppsExec;
import com.ibm.pip.adapter.filter.IDataFilter;
import com.ibm.pip.adapter.formatter.DataProcParameter;
import com.ibm.pip.adapter.formatter.IDataFormatter;
import com.ibm.pip.framework.buffer.AbstractMsgReceiveSend;
import com.ibm.pip.framework.buffer.IMsgBuffer;
import com.ibm.pip.framework.cache.vo.RtnCodeMsg;
import com.ibm.pip.framework.config.ConfigManager;
import com.ibm.pip.framework.connection.client.DataBean;
import com.ibm.pip.framework.exception.EsbException;
import com.ibm.pip.framework.exception.FrameworkErr;
import com.ibm.pip.framework.handler.BaseEasHandler;
import com.ibm.pip.framework.handler.BaseEasHandlerUtil;
import com.ibm.pip.framework.handler.HandlerAE;
import com.ibm.pip.framework.messageobject.IBMMSG;
import com.ibm.pip.framework.messageobject.ServiceParameter;
import com.ibm.pip.framework.messageobject.ServiceRequest;
import com.ibm.pip.framework.messageobject.ServiceResponse;
import com.ibm.pip.framework.messageobject.domparser.DomNodeOperation;
import com.ibm.pip.framework.messageobject.domparser.MsgObject;
import com.ibm.pip.util.DateUtil;
import com.ibm.pip.util.PublicPrint;
import com.ibm.pip.util.log.Log;

public class DataHandler extends BaseEasHandler {

	private IMsgBuffer replyBuffer = null;
	private IMsgBuffer replyDataBuffer = null;

	private IMsgBuffer syncReplyBuffer = null;

	protected IDataFormatter OutdataFormatter = null;

	public DataHandler() throws EsbException {
		receiveBuffer = AbstractMsgReceiveSend.getMsgBufferInstance("ReceiveBuffer.Instance");
		replyBuffer = AbstractMsgReceiveSend.getMsgBufferInstance("SendBuffer.Instance");
		replyDataBuffer = AbstractMsgReceiveSend.getMsgBufferInstance("OpSendBuffer.Instance");
		syncReplyBuffer = AbstractMsgReceiveSend.getMsgBufferInstance("SyncSendBuffer.Instance");
		LOG_PREFIX = "DataHandler";

		OutdataFormatter = (IDataFormatter) cm.get("Formatter.Instance");
		if (OutdataFormatter == null) {
//				throw new EsbException(FrameworkErr.FORMATTER_INIT_ERRCODE,FrameworkErr.FORMATTER_INIT_ERRDESC);
			Log.getInstance().stdWarn(FrameworkErr.FORMATTER_INIT_ERRCODE + "-lack IndataFormatter");
		}

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.ibm.pip.framework.handler.BaseEasHandler#NeedForwardReq(com.ibm.pip.
	 * framework.messageobject.ServiceRequest)
	 */
	@Override
	protected boolean NeedForwardReq(ServiceRequest servReq) {
		return false;
	}

	@Override
	protected void sendResponse(ServiceResponse servResponse) {
		// TODO Auto-generated method stub

	}

	@Override
	public void release() {
		// TODO Auto-generated method stub

	}

	public void providerMessageProc(ServiceRequest servRequest) {


		if (servRequest.getOrginalCon().equals("MQServer")) {
			
			MsgObject mo = null;
			try {
				mo = new MsgObject(servRequest.getRequest(), MsgObject.initSR);
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

			String resMsg = mo.getRequestParameter("DATA");
			Map mapType = JSON.parseObject(resMsg, Map.class);
			String haid = mo.getRequesterHaID();
			Log.getInstance().stdDebug("haid providerMessageProc ====================" + haid);
			if(haid != null && !"".equals(haid))
			{
				((Map)((Map)mapType.get("REQUEST")).get("RequestData")).put("Ha_Id", "@$haidbg"+mo.getRequesterHaID()+"haidbg$@");
				resMsg = JSON.toJSONString(mapType);
			}
			
			servRequest.setRequest(resMsg.getBytes());

			ServiceResponse res = BaseEasHandlerUtil.getServiceResponse(servRequest);
			if("00010000000002".equals(mo.getExtAttributeHashMap().get("req_service_id")))
			{
				this.replyDataBuffer.put(res);
			}
			else
			{
				res.setReplyToManagerName("PIP_OUT");
				res.setReplyToQueueName("Q_BANCS_OUT");
				this.replyBuffer.put(res);
			}
			
		}

		else if (servRequest.getOrginalCon().equals("SyncDataReceive")) {
			
			HashMap<String,Object> hashmap = new HashMap<String,Object>();
			
			MsgObject mo = null;
			try {
				mo = dataFormatter.format(servRequest.getRequest(),hashmap);
			} catch (EsbException e) {
				
				Log.getInstance().stdError("dataFormatter.format format error",e);
				return;
			}
			Log.getInstance().stdDebug("data sycn mo ====================" + mo.toString());
			PublicPrint.printMsgObject(mo);
			servRequest.setMo(mo);
			servRequest.setExecId("10020000000003");
			servRequest.getMo().setServiceID("10020000000003");
			ServiceResponse res = BaseEasHandlerUtil.getServiceResponse(servRequest);
			BaseEasHandlerUtil.writeMoToBytes(res);
			
			this.syncReplyBuffer.put(res);
		} else {
			Log.getInstance().stdError("Unknown source of Message");
		}

	}

} // end of class
