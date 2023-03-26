package com.ibm.pip.adapter.data.util;

public class ResTemplete {
	
	public static String restempleteData = "{\n" + 
			" \"REQUEST\": {\n" + 
			"  \"ESB_ATTRS\": {\n" + 
			"   \"Application_ID\": \"00020000000004\",\n" + 
			"   \"Transaction_ID\": \"$Transaction_ID$\"\n" + 
			"  },\n" + 
			"  \"REQUEST_DATA\": {\n" + 
			"   \"rfcName\": \"$rfcName$\",\n" + 
			"   \"date\": \"$date$\",\n" + 
			"   \"tableName\": \"$tableName$\",\n" + 
			"   \"isSyn\": \"$isSyn$\"\n" + 
			"  }\n" + 
			" }\n" + 
			"}";

}
