package com.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.net.util.IPAddressUtil;

import java.net.InetAddress;
import java.net.UnknownHostException;

public class InetUtils {

    private static final Logger log = LoggerFactory.getLogger(InetUtils.class);

    public static InetAddress localInetAddress;

    public static String uniqueInetTag;

    static {
        loadLocalInetAddress();
    }


    public static InetAddress getInetAddressByHost(String host) {
        try {
            InetAddress address = InetAddress.getByName(host);
            return address;
        } catch (UnknownHostException e) {
            e.printStackTrace();
            throw new RuntimeException("can not know the host: " + host);
        }
    }

    public static InetAddress getByStringIpAddress(String ipAddress) {
        InetAddress byAddress = null;
        try {
            byte[] bytes = IPAddressUtil.textToNumericFormatV4(ipAddress);
            if (bytes == null) {
                byAddress = getInetAddressByHost(ipAddress);
            } else {
                byAddress = InetAddress.getByAddress(bytes);
            }
        } catch (Exception e) {
            log.error("un know host :" + ipAddress);
            ApplicationExit.exit();
        }
        return byAddress;
    }


    /**
     * get local address
     */
    private static void loadLocalInetAddress() {
        try {
            localInetAddress = InetAddress.getByName("0.0.0.0");
        } catch (UnknownHostException e) {
            log.error("get local adress error");
            ApplicationExit.exit();

        }
    }


}
