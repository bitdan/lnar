package com.ibm.pip.adapter.sunda.utils.sap;

import java.util.concurrent.atomic.AtomicInteger;

import com.sap.conn.jco.ext.JCoSessionReference;

public class RfcSessionReference implements JCoSessionReference {
	static AtomicInteger atomicInt = new AtomicInteger(0);
	private String id = "session-" + String.valueOf(atomicInt.addAndGet(1));;
	
	@Override
	public void contextFinished() {
		// TODO Auto-generated method stub
	}

	@Override
	public void contextStarted() {
		// TODO Auto-generated method stub
	}

	@Override
	public String getID() {
		// TODO Auto-generated method stub
		return id;
	}
}
