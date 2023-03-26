package com.ibm.pip.adapter.sunda.utils.sendmq;

import java.util.HashMap;
import java.util.Map;

/**
 * POS静态数据
 */
public class RfcTableMappingUtil {

    // SPMS海外DN收货返回结果列Z_SPMS_GETHWDN
	public static final Map<String, String[]> rfcTableMapping = new HashMap<>();

	// 该名称不是输出表名是SAP映射结构名
	public static final String ZSHWDN_LIPS = "ZSHWDN_LIPS";
	public static final String EKKO = "EKKO";
    public static final String EKPO = "EKPO";
    public static final String ZSHWDN_LIKP = "ZSHWDN_LIKP";
    public static final String[] ZSHWDN_LIPS_Array = {"VBELN", "POSNR", "VGBEL", "VGPOS", "WERKS_FH", "LGORT", "LFIMG", "VRKME", "MEINS", "LGMNG", "PACKAGE_CODE", "SERIAL", "MATNR", "GNCKSL", "DEMAND_NO", "DEMAND_POSNR", "DEMANDER", "WERKS"};
    public static final String[] EKKO_Array = {"AEDAT", "LIFNR", "EKGRP", "ERNAM", "EBELN", "BSART", "EKORG"};//经确认"WERKS"不需要
    public static final String[] EKPO_Array = {"EBELN", "EBELP", "WERKS", "LGORT", "MATNR", "TXZ01", "MENGE", "MEINS", "RETPO"};//经确认"EINDT"不需要
    public static final String[] ZSHWDN_LIKP_Array = {"VBELN", "WERKS", "ERDAT", "LFDAT"};
    
	// Z_SPMS_GETDN
//    public static final Map<String, String[]> rfcGetDnTableMapping = new HashMap<>();
//	public static final String GETDN_IT_LIKP = "IT_LIKP";
//	public static final String GETDN_IT_LIPS = "IT_LIPS";
//	public static final String GETDN_IT_LIFNR = "IT_LIFNR";
//	public static final String GETDN_IT_HEAD = "IT_HEAD";
//	public static final String GETDN_IT_ITME = "IT_ITME";
//	public static final String GETDN_IT_XHEAD = "IT_XHEAD";
//	public static final String[] GETDN_IT_LIKP_Array = { "VBELN", "LFDAT", "LFART" ,"LIFEX","LIFNR","ERDAT","ZSPMS","PACKAGE_NO"};
//	public static final String[] GETDN_IT_LIPS_Array = { "VBELN", "POSNR", "ZEKGRP", "ZEKNAM", "MATNR", "LFIMG", "VRKME","WERKS","LGORT","UEBTO","UEBTK","UNTTO" };
//	public static final String[] GETDN_IT_LIFNR_Array = { "VBELN", "LIFNR", "ZTEXT"};
//	public static final String[] GETDN_IT_HEAD_Array = { "PACKAGE_NO", "SRM_DN", "SAP_DN", "LIFNR", "VOLUM_T", "BRGEW_T", "ZXDATE_F", "ZXDATE_T", "YSR", "CYRLXFS", "SHR", "SHRLXFS", "SHDZ", "SPMS_CONTRACT_NO", "SPMS_FLG" };
//	public static final String[] GETDN_IT_ITME_Array = { "PACKAGE_NO", "SERIAL", "PACKAGE_CODE", "PACKAGE_POSNR", "MATNR", "SPECS", "MENGE", "MEINS", "SRM_CONTRACT_NO", "EBELN", "EBELP", "VBELN", "POSNR", "DEMAND_NO", "DEMAND_POSNR", "DEMANDER", "MARK", "BRGEW", "NTGEW", "GNSHSL", "GNSJSL", "HWSHSL" };
//	public static final String[] GETDN_IT_XHEAD_Array = { "PACKAGE_NO", "PACKAGE_CODE", "OUTER_BOX_TEXTURE", "TOTAL_QTY", "BOX_BRGEW", "BOX_VOLUM", "LENGTH", "WIDTH", "HEIGHT", "COLOR" };

	
	//Z_SPMS_GETBOXCODE
	public static final Map<String, String[]> rfcGetBoxCodeTableMapping = new HashMap<>();
	public static final String GETBOXCODE_IT_HEAD = "IT_HEAD";
	public static final String GETBOXCODE_IT_ITME = "IT_ITME";
	public static final String GETBOXCODE_IT_XHEAD = "IT_XHEAD";
	public static final String[] GETBOXCODE_IT_HEAD_Array = { "PACKAGE_NO", "SRM_DN", "SAP_DN", "LIFNR", "VOLUM_T", "BRGEW_T", "ZXDATE_F", "ZXDATE_T", "YSR", "CYRLXFS", "SHR", "SHRLXFS", "SHDZ", "SPMS_CONTRACT_NO", "SPMS_FLG" };
	public static final String[] GETBOXCODE_IT_ITME_Array = { "PACKAGE_NO", "SERIAL", "PACKAGE_CODE", "PACKAGE_POSNR", "MATNR", "SPECS", "MENGE", "MEINS", "SRM_CONTRACT_NO", "EBELN", "EBELP", "VBELN", "POSNR", "DEMAND_NO", "DEMAND_POSNR", "DEMANDER", "MARK", "BRGEW", "NTGEW", "GNSHSL", "GNSJSL", "HWSHSL" };
	public static final String[] GETBOXCODE_IT_XHEAD_Array = { "PACKAGE_NO", "PACKAGE_CODE", "OUTER_BOX_TEXTURE", "TOTAL_QTY", "BOX_BRGEW", "BOX_VOLUM", "LENGTH", "WIDTH", "HEIGHT", "COLOR" };

    
	static {
		rfcTableMapping.put(ZSHWDN_LIPS, ZSHWDN_LIPS_Array);
		rfcTableMapping.put(EKKO, EKKO_Array);
		rfcTableMapping.put(EKPO, EKPO_Array);
		rfcTableMapping.put(ZSHWDN_LIKP, ZSHWDN_LIKP_Array);

		// Z_SPMS_GETDN
//		rfcGetDnTableMapping.put(GETDN_IT_LIKP, GETDN_IT_LIKP_Array);
//		rfcGetDnTableMapping.put(GETDN_IT_LIPS, GETDN_IT_LIPS_Array);
//		rfcGetDnTableMapping.put(GETDN_IT_LIFNR, GETDN_IT_LIFNR_Array);
//		rfcGetDnTableMapping.put(GETDN_IT_HEAD, GETDN_IT_HEAD_Array);
//		rfcGetDnTableMapping.put(GETDN_IT_ITME, GETDN_IT_ITME_Array);
//		rfcGetDnTableMapping.put(GETDN_IT_XHEAD, GETDN_IT_XHEAD_Array);

		// Z_SPMS_GETBOXCODE
		rfcGetBoxCodeTableMapping.put(GETBOXCODE_IT_HEAD, GETBOXCODE_IT_HEAD_Array);
		rfcGetBoxCodeTableMapping.put(GETBOXCODE_IT_ITME, GETBOXCODE_IT_ITME_Array);
		rfcGetBoxCodeTableMapping.put(GETBOXCODE_IT_XHEAD, GETBOXCODE_IT_XHEAD_Array);
	}
}
