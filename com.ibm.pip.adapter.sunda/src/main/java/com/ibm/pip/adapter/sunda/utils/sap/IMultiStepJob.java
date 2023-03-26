package com.ibm.pip.adapter.sunda.utils.sap;

public interface IMultiStepJob {
	public boolean runNextStep();
	 
	String getName();
 
	public void cleanUp();
}
