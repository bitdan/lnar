package com.ibm.pip.adapter.sunda.utils;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

import org.apache.commons.lang.StringUtils;

import com.ibm.pip.util.log.Log;

/**
 * 日期工具类
 * 
 * @author xuhk
 *
 */
public final class DateUtil {

	public static Date getDate(String dataStr) {
		Date date = null;
		SimpleDateFormat sdf = new SimpleDateFormat("EEE MMM dd HH:mm:ss Z yyyy", Locale.UK);
		try {
			date = sdf.parse(dataStr);
		} catch (ParseException e) {
			e.printStackTrace();
		}
		return date;
	}

	public static String formatDate(Date date, String format) {
		String res = "";
		if (date == null) {
			return res;
		}
		SimpleDateFormat sdf = new SimpleDateFormat(format);
		res = sdf.format(date);
		return res;
	}

	public static String convertDate(Object str) {
		return convertDate(str, "yyyyMMdd");
	}

	public static String convertDate(Object str, String fmt) {
		String res = "";
		try {
			if (str == null) {
				return res;
			}
			if (StringUtils.isEmpty(str.toString())) {
				return res;
			}

			SimpleDateFormat format = new SimpleDateFormat(fmt);
			Date date = new Date(str.toString());
			res = format.format(date);
		} catch (Exception e) {
			Log.getInstance().stdError(e);
		}
		return res;
	}

	public static String getCurrDate() {
		return getCurrDate("yyyy-MM-dd HH:mm:ss");
	}

	public static String getCurrDate(String format) {
		String res = "";
		SimpleDateFormat sdf = new SimpleDateFormat(format);
		Date date = new Date();
		res = sdf.format(date);
		return res;
	}

	public static String getCurrDate(int minute) {
		Calendar calendar = Calendar.getInstance(); // 创建Calendar 的实例
		calendar.add(Calendar.MINUTE, minute); // 当前时间加或减去指定分钟数
		calendar.getTimeInMillis();// 返回当前时间的毫秒数

		String res = "";
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
		res = sdf.format(calendar.getTime());
		return res;
	}

	public static void main(String[] args) {
		System.out.println(getCurrDate());
		System.out.println(getCurrDate(-10));
	}
}
