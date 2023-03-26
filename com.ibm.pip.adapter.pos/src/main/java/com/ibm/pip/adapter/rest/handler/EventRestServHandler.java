package com.ibm.pip.adapter.rest.handler;

import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Map;

import org.apache.mina.core.session.IoSession;

import com.ibm.pip.adapter.connection.redisclient.SessionMap;
import com.ibm.pip.adapter.exec.IEventAppsExec;
import com.ibm.pip.adapter.formatter.IDataFormatter;
import com.ibm.pip.framework.buffer.AbstractMsgReceiveSend;
import com.ibm.pip.framework.buffer.IMsgBuffer;
import com.ibm.pip.framework.config.ConfigManager;
import com.ibm.pip.framework.connection.client.DataBean;
import com.ibm.pip.framework.exception.EsbException;
import com.ibm.pip.framework.exception.FrameworkErr;
import com.ibm.pip.framework.handler.BaseEasHandler;
import com.ibm.pip.framework.handler.BaseEasHandlerUtil;
import com.ibm.pip.framework.messageobject.IBMMSG;
import com.ibm.pip.framework.messageobject.ServiceRequest;
import com.ibm.pip.framework.messageobject.ServiceResponse;
import com.ibm.pip.framework.messageobject.domparser.MsgObject;
import com.ibm.pip.util.PublicPrint;
import com.ibm.pip.util.log.Log;

/**
 * @version 1.0
 * @author linjn
 * @WrittenDate:2016骞�1鏈�6鏃�
 * @ModifiedBy:
 * @ModifiedDate:
 */

public class EventRestServHandler extends BaseEasHandler {

	protected IMsgBuffer sendBuffer = null;
	protected IMsgBuffer sessionBuffer = null;
	
	protected IDataFormatter IndataFormatter = null;
	protected IDataFormatter OutdataFormatter = null;

	public EventRestServHandler() throws EsbException {
		super();
		receiveBuffer = AbstractMsgReceiveSend.getMsgBufferInstance("RESTReceiveBuffer.Instance");
		sendBuffer = AbstractMsgReceiveSend.getMsgBufferInstance("RESTSendBuffer.Instance");
		IndataFormatter = (IDataFormatter) cm.get("InFormatter.Instance");
		if(IndataFormatter==null){
//			throw new EsbException(FrameworkErr.FORMATTER_INIT_ERRCODE,FrameworkErr.FORMATTER_INIT_ERRDESC);
			Log.getInstance().stdWarn(FrameworkErr.FORMATTER_INIT_ERRCODE + "-lack IndataFormatter");
		}
		
		OutdataFormatter = (IDataFormatter) cm.get("OutFormatter.Instance");
		if(OutdataFormatter==null){
//			throw new EsbException(FrameworkErr.FORMATTER_INIT_ERRCODE,FrameworkErr.FORMATTER_INIT_ERRDESC);
			Log.getInstance().stdWarn(FrameworkErr.FORMATTER_INIT_ERRCODE + "-lack OutdataFormatter");
		}
	}

	// pos -> corebus
	public void reqPubMessageProc(ServiceRequest servRequest){
		DataBean databean = null;
		IEventAppsExec exec = null;

		HashMap<String,Object> hashmap = new HashMap<String,Object>();
		try{
			//解析请求报文
			BaseEasHandlerUtil.formatBytesToMo(servRequest,hashmap,IndataFormatter);
			pt.counter(" formatBytesToMo ");
			pt.setMO(servRequest.getMo());
			PublicPrint.printMsgObject(servRequest.getMo());

			// 初始化本地应用程序类
			exec = BaseEasHandlerUtil.initEventAppsExec(servRequest);

			//调用发送前的本地应用程序逻辑处理
			BaseEasHandlerUtil.executeBeforeSend(servRequest, exec);
			pt.counter(" executeBeforeSend ");

			
			//解析请求报文	,准备发送数据
			databean = BaseEasHandlerUtil.writeMoToBytes(servRequest);
			pt.counter(" writeMoToBytes ");

			//处理和各个系统之间的连接,如果失败直接退出
			BaseEasHandlerUtil.providerClientConnection(databean, servRequest, cm);
			pt.counter(" providerClientConnection ");

		} catch (EsbException esbEx) {
			Log.getInstance().bizError(esbEx,servRequest.getMo());
			esbEx.doMonitorOperation(servRequest.getMo());
			pt.counter(" exception ");
			return;
		} finally {
			if(hashmap!=null){
				hashmap.clear();
				hashmap = null;
			}
		}
	}
	
	
	

	//corebus -> pos
	public void providerPubMessageProc(ServiceRequest servRequest){
		DataBean databean = null;
		IEventAppsExec exec = null;

		HashMap<String,Object> hashmap = new HashMap<String,Object>();
		try{
			//转换服务请求为mo形式
			if(!BaseEasHandlerUtil.readBytesToMo(servRequest,MsgObject.initSP)){
				pt.counter(" readBytesToMo ");
				return;
			}
			pt.counter(" formatBytesToMo ");
			pt.setMO(servRequest.getMo());
			PublicPrint.printMsgObject(servRequest.getMo());

			// 初始化本地应用程序类
			exec = BaseEasHandlerUtil.initEventAppsExec(servRequest);

			//调用发送前的本地应用程序逻辑处理
			BaseEasHandlerUtil.executeBeforeSend(servRequest, exec);
			pt.counter(" executeBeforeSend ");

			//解析请求报文	,准备发送数据
			databean = BaseEasHandlerUtil.formatMoToBytes(servRequest, OutdataFormatter);
			pt.counter(" formatMoToBytes ");

			//处理和各个系统之间的连接,如果失败直接退出
			String[] targetCons= servRequest.getTargetCon().split(",");
			for(String targetCon : targetCons)
			{
				servRequest.setTargetCon(targetCon);
				BaseEasHandlerUtil.providerClientConnection(databean, servRequest, cm);
			}
			
			//BaseEasHandlerUtil.providerClientConnection(databean, servRequest, cm);
			pt.counter(" providerClientConnection ");

		} catch (EsbException esbEx) {
			Log.getInstance().bizError(esbEx,servRequest.getMo());
			esbEx.doMonitorOperation(servRequest.getMo());
			pt.counter(" exception ");
			return;
		} finally {
			if(hashmap!=null){
				hashmap.clear();
				hashmap = null;
			}
		}
	}
	

	@Override
	protected void sendResponse(ServiceResponse servResponse) {
		this.sendBuffer.put(servResponse);

	}

	@Override
	protected boolean NeedForwardReq(ServiceRequest servReq) throws EsbException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public void release() {
		// TODO Auto-generated method stub

	}
} // end of class