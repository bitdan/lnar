package com.ibm.pip.adapter.sunda.handler;

import java.util.HashMap;

import oracle.net.ns.Communication;

import com.ibm.pip.adapter.connection.redisclient.SessionMap;
import com.ibm.pip.adapter.exec.IEventAppsExec;
import com.ibm.pip.adapter.filter.IDataFilter;
import com.ibm.pip.adapter.formatter.DataProcParameter;
import com.ibm.pip.adapter.formatter.IDataFormatter;
import com.ibm.pip.adapter.sunda.timer.CommunicationTimer;
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


public class SundaHandler extends BaseEasHandler {

	private IMsgBuffer replyBuffer = null;
	
	
	protected IDataFormatter IndataFormatter = null;
	protected IDataFormatter OutdataFormatter = null;


	/**
	 * 构造方法，设置接收和返回的缓冲区实例名称。
	 * 
	 * @throws EsbException
	 */
	public SundaHandler() throws EsbException {
		super();
		receiveBuffer = AbstractMsgReceiveSend
				.getMsgBufferInstance("ReceiveBuffer.Instance");
		replyBuffer = AbstractMsgReceiveSend
				.getMsgBufferInstance("SendBuffer.Instance");
		LOG_PREFIX = "SundaHandler";
		
		IndataFormatter = (IDataFormatter) cm.get("Formatter.Instance");
		if(IndataFormatter==null){
//			throw new EsbException(FrameworkErr.FORMATTER_INIT_ERRCODE,FrameworkErr.FORMATTER_INIT_ERRDESC);
			Log.getInstance().stdWarn(FrameworkErr.FORMATTER_INIT_ERRCODE + "-lack IndataFormatter");
		}
		
		OutdataFormatter = (IDataFormatter) cm.get("Formatter.Instance");
		if(OutdataFormatter==null){
//			throw new EsbException(FrameworkErr.FORMATTER_INIT_ERRCODE,FrameworkErr.FORMATTER_INIT_ERRDESC);
			Log.getInstance().stdWarn(FrameworkErr.FORMATTER_INIT_ERRCODE + "-lack OutdataFormatter");
		}

	}
	


	/* (non-Javadoc)
	 * @see com.ibm.pip.framework.handler.BaseEasHandler#NeedForwardReq(com.ibm.pip.framework.messageobject.ServiceRequest)
	 */
	@Override
	protected boolean NeedForwardReq(ServiceRequest servReq) {
		return false;
	}



	@Override
	protected void sendResponse(ServiceResponse servResponse) {
		// TODO Auto-generated method stub
		replyBuffer.put(servResponse);
	}



	@Override
	public void release() {
		// TODO Auto-generated method stub
		
	}
	
	//corebus -> sap
	public void providerMessageProc(ServiceRequest servRequest) {
		DataBean databean = null;
		ServiceResponse servResponse = null;
		IEventAppsExec exec = null;


		if (!BaseEasHandlerUtil.readBytesToMo(servRequest, MsgObject.initSP)) {
			pt.counter(" readBytesToMo ");
			return;
		}
		pt.counter(" readBytesToMo ");
		pt.setMO(servRequest.getMo());
		PublicPrint.printMsgObject(servRequest.getMo());


		exec = BaseEasHandlerUtil.initEventAppsExec(servRequest);

		try {
			pt.counter(" createProviderReversalMsg ");

			BaseEasHandlerUtil.executeBeforeSend(servRequest, exec);
			pt.counter(" executeBeforeSend ");

			databean = BaseEasHandlerUtil.formatMoToBytes(servRequest, OutdataFormatter);
			pt.counter(" formatMoToBytes ");

			if (!BaseEasHandlerUtil.providerClientConnection(databean, servRequest, cm)) {
				pt.counter(" providerClientConnection ");
				return;
			}
			pt.counter(" providerClientConnection ");


			if (databean.getReceiveMsg() != null)
				servResponse = BaseEasHandlerUtil.unformatBytesToMo(servRequest, databean, OutdataFormatter);
			pt.counter(" unformatBytesToMo ");

		} catch (EsbException esbEx) {
			Log.getInstance().bizError(esbEx, servRequest.getMo());
			servResponse = BaseEasHandlerUtil.getServiceResponse(servRequest);
			HandlerAE.handleRtnCode(servResponse, esbEx, HandlerAE.UN_ACCEPTABLE_EXCEPTION, appSysID, appSysName, cache);
			PublicPrint.printMsgObject(servResponse.getMo());
			servResponse.getMo().setRequestParameter("RESDATA", esbEx.getMessage());
			servResponse.getMo().setRequestParameter("ISERROR", "1");
			servResponse.getMo().setRequestParameter("RESCODE", esbEx.getReturnCode());
			BaseEasHandlerUtil.writeMoToBytes(servResponse);
			pt.counter(" exception ");
		}

		try {

			BaseEasHandlerUtil.executeAfterSend(servResponse, exec);
			pt.counter(" executeAfterSend ");
			if (servResponse != null) {

				BaseEasHandlerUtil.procMsgStatus(appSysID, appSysName, servResponse, cache);
				PublicPrint.printMsgObject(servResponse.getMo());
				BaseEasHandlerUtil.writeMoToBytes(servResponse);
				pt.counter(" writeMoToBytes ");
				send(servResponse);
			}
			return;
		} catch (EsbException esbEx) {
			Log.getInstance().bizError(esbEx, servResponse.getMo());
			pt.counter(" exception ");
			return;
		}
	}
	
	//sap -> corebus
	public void requesterMessageProc(ServiceRequest servRequest) {

		DataBean databean = null;
		ServiceResponse servResponse = null;
		IEventAppsExec exec = null;

		HashMap<String, Object> hashmap = new HashMap<String, Object>();

	
		hashmap.put(IBMMSG.REQ_SERVICE_ID, servRequest.getExecId());
		try {
			BaseEasHandlerUtil.formatBytesToMo(servRequest, hashmap, IndataFormatter);
			pt.setMO(servRequest.getMo());
			pt.counter(" formatBytesToMo ");
			PublicPrint.printMsgObject(servRequest.getMo());
		} catch (EsbException esbEx) {
			Log.getInstance().bizError(esbEx, servResponse.getMo());
			return;
		} finally {
			if (hashmap != null) {
				hashmap.clear();
				hashmap = null;
			}
		}

//		servRequest.getMo().setReqExtAttribute(IBMMSG.REQ_SERVICE_ID, serv_id);
//		servRequest.setExecId(serv_id);

		exec = BaseEasHandlerUtil.initEventAppsExec(servRequest);

		try {
			
			BaseEasHandlerUtil.executeBeforeSend(servRequest, exec);
			pt.counter(" executeBeforeSend ");

			
			databean = BaseEasHandlerUtil.writeMoToBytes(servRequest);
			pt.counter(" writeMoToBytes ");


			if (!BaseEasHandlerUtil.requesterClientConnection(databean, servRequest, cm)) {
				pt.counter(" requesterClientConnection ");
				return;
			}
			pt.counter(" requesterClientConnection ");

	 
			if (databean.getReceiveMsg() != null/* || "".equals(databean.getReceiveMsg()) */)
				servResponse = BaseEasHandlerUtil.readBytesToMo(servRequest, databean);
			pt.counter(" readBytesToMo ");

		} catch (EsbException esbEx) {
			Log.getInstance().bizError(esbEx, servRequest.getMo());
			return;
		}

		try {
	
			BaseEasHandlerUtil.executeAfterSend(servResponse, exec);
			pt.counter(" executeAfterSend ");

			if (servResponse != null) {

				
				PublicPrint.printMsgObject(servResponse.getMo());
				BaseEasHandlerUtil.unformatMoToBytes(servResponse, IndataFormatter);
				pt.counter(" unformatMoToBytes ");

				send(servResponse);

			}
			return;
		} catch (EsbException esbEx) {
			Log.getInstance().bizError(esbEx, servResponse.getMo());
			return;
		}

	}
	
	
	public void fastChannelMessageProc(ServiceRequest servRequest)
	{
		String req = new String(servRequest.getRequest());
		CommunicationTimer tmp = new CommunicationTimer();
		tmp.runMQRequest(req);
		
	}
} // end of class
