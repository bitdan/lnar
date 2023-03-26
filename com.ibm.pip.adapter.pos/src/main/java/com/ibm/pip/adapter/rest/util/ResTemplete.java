package com.ibm.pip.adapter.rest.util;

public class ResTemplete {
	
//	public static String restemplete = "{\n" + 
//			"    \"RESPONSE\": {\n" + 
//			"        \"RETURN_CODE\": \"$ResCode$\""+",\n" + 
//			"        \"RETURN_DATA\": $ResTemplete$" + 
//			"    }\n" + 
//			"}\n" + 
//			"";
//	
	
	public static String restemplete = "{\n" + 
			"	\"RESPONSE\": {\n" + 
			"		\"ESB_ATTRS\": {\n" + 
			"			\"RETURN_CODE\": \"$ResCode$\",\n" + 
			"			\"RETURN_DESC\": \"$ResDesc$\",\n" + 
			"			\"Type\": \"$Type$\",\n" + 
			"			\"Transaction_ID\": \"$Transaction_ID$\",\n" + 
			"			\"Application_ID\": \"$Application_ID$\"\n" + 
			"		},\n" + 
			"\n" + 
			"\n" + 
			"		\"RETURN_DATA\": $ResTemplete$\n" + 
			"	}\n" + 
			"}";
}
