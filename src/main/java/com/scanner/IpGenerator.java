package com.scanner;

import java.util.concurrent.ThreadLocalRandom;

public final class IpGenerator {

    private IpGenerator() {}

    // ================================================================
    // 公网判定：首字节 switch 分派
    // ================================================================
    public static boolean isPublicIp(int ip) {
        int a = ip >>> 24;
        switch (a) {
            case 0: case 10: case 127:
                return false;
            case 100:
                return (ip & 0xFFC00000) != 0x64400000;
            case 169:
                return (ip & 0xFFFF0000) != 0xA9FE0000;
            case 172:
                return (ip & 0xFFF00000) != 0xAC100000;
            case 192:
                if ((ip & 0xFFFF0000) == 0xC0A80000) return false;
                if ((ip & 0xFFFFFF00) == 0xC0000000) return false;
                if ((ip & 0xFFFFFF00) == 0xC0000200) return false;
                return (ip & 0xFFFFFF00) != 0xC0586300;
            case 198:
                if ((ip & 0xFFFE0000) == 0xC6120000) return false;
                return (ip & 0xFFFFFF00) != 0xC6336400;
            case 203:
                return (ip & 0xFFFFFF00) != 0xCB007100;
            default:
                return a < 224;
        }
    }

    // ================================================================
    // 核心 API：返回 int，零分配
    // ================================================================
    public static int randomPublicIpInt() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        while (true) {
            int ip = rng.nextInt();
            if (isPublicIp(ip)) return ip;
        }
    }

    public static void fillPublicIps(int[] out) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int i = 0, n = out.length;
        while (i < n) {
            int ip = rng.nextInt();
            if (isPublicIp(ip)) out[i++] = ip;
        }
    }

    // ================================================================
    // 兼容 API：字符串
    // ================================================================
    public static String randomPublicIp() {
        return ipToString(randomPublicIpInt());
    }

    public static String ipToString(int ip) {
        int a = ip >>> 24;
        int b = (ip >>> 16) & 0xFF;
        int c = (ip >>>  8) & 0xFF;
        int d =  ip         & 0xFF;
        return a + "." + b + "." + c + "." + d;
    }
}