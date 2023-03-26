package com.ibm.pip.adapter.sunda.utils;

import java.util.UUID;

/**
 * 字符串工具类
 * 
 * @author xuhk
 *
 */
public final class StringUtil {

	public static String getUUID() {
		return UUID.randomUUID().toString().replaceAll("-", "");
	}

//	public static Object fmt(Object obj) {
//		return obj == null ? "" : obj;
//	}
}
