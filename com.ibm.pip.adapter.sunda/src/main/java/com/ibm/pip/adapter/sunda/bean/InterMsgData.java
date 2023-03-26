package com.ibm.pip.adapter.sunda.bean;

import java.io.Serializable;
import java.util.Date;

/**
 * ESB内部各工程间数据传输载体
 * @author xuhk
 *
 */
public class InterMsgData implements Serializable {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	private String ip;//IP地址
	private String className;//类名
	private String methodName;//方法名
	private String operation;//操作描述
	private int time;//耗费时间

	private String reqAppId;//请求系统
	private String applicationId;//应用ID
	private String transactionID;//交易ID
	private String msgFrom;//消息来源
	private String msgTo;//消息到达
	private String msgContent;//消息内容
	private String methodNameEn;//方法英文名
	private String methodNameCn;//方法中文名
	private Date createTime;//时间
	private int msgOrder;//消息顺序

	private String funStrs;//方法及参数

	public String getIp() {
		return ip;
	}

	public void setIp(String ip) {
		this.ip = ip;
	}

	public String getClassName() {
		return className;
	}

	public void setClassName(String className) {
		this.className = className;
	}

	public String getMethodName() {
		return methodName;
	}

	public void setMethodName(String methodName) {
		this.methodName = methodName;
	}

	public String getOperation() {
		return operation;
	}

	public void setOperation(String operation) {
		this.operation = operation;
	}

	public int getTime() {
		return time;
	}

	public void setTime(int time) {
		this.time = time;
	}

	public String getReqAppId() {
		return reqAppId;
	}

	public void setReqAppId(String reqAppId) {
		this.reqAppId = reqAppId;
	}

	public String getApplicationId() {
		return applicationId;
	}

	public void setApplicationId(String applicationId) {
		this.applicationId = applicationId;
	}

	public String getTransactionID() {
		return transactionID;
	}

	public void setTransactionID(String transactionID) {
		this.transactionID = transactionID;
	}

	public String getMsgFrom() {
		return msgFrom;
	}

	public void setMsgFrom(String msgFrom) {
		this.msgFrom = msgFrom;
	}

	public String getMsgTo() {
		return msgTo;
	}

	public void setMsgTo(String msgTo) {
		this.msgTo = msgTo;
	}

	public String getMsgContent() {
		return msgContent;
	}

	public void setMsgContent(String msgContent) {
		this.msgContent = msgContent;
	}

	public String getFunStrs() {
		return funStrs;
	}

	public void setFunStrs(String funStrs) {
		this.funStrs = funStrs;
	}

	public String getMethodNameEn() {
		return methodNameEn;
	}

	public void setMethodNameEn(String methodNameEn) {
		this.methodNameEn = methodNameEn;
	}

	public Date getCreateTime() {
		return createTime;
	}

	public void setCreateTime(Date createTime) {
		this.createTime = createTime;
	}

	public int getMsgOrder() {
		return msgOrder;
	}

	public void setMsgOrder(int msgOrder) {
		this.msgOrder = msgOrder;
	}

	public String getMethodNameCn() {
		return methodNameCn;
	}

	public void setMethodNameCn(String methodNameCn) {
		this.methodNameCn = methodNameCn;
	}

}
