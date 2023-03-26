package com.ibm.pip.adapter.sunda.utils;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

/**
 * 常量定义
 * @author xuhk
 *
 */
public final class NetworkUtil {

	public static String getIP() {
		String ip = null;
		try {
			Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
			while (nis.hasMoreElements()) {
				NetworkInterface ni = nis.nextElement();
				Enumeration<InetAddress> ias = ni.getInetAddresses();
				while (ias.hasMoreElements()) {
					InetAddress ia = ias.nextElement();
					// ia instanceof Inet6Address && !ia.equals("")
					if (ia instanceof Inet4Address && !ia.getHostAddress().equals("127.0.0.1")) {
						ip = ia.getHostAddress();
					}
				}
			}
		} catch (SocketException e) {
			e.printStackTrace();
		}
		return ip;
	}
}
