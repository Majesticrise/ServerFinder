package com.scanner;

import java.util.concurrent.ThreadLocalRandom;

public final class IpGenerator {

//    public static boolean isPublicIp(String ip) {
//        String[] parts = ip.split("\\.");
//        if (parts.length != 4) return false;
//        try {
//            int a = Integer.parseInt(parts[0]);
//            int b = Integer.parseInt(parts[1]);
//            int c = Integer.parseInt(parts[2]);
//            int d = Integer.parseInt(parts[3]);
//            // 每一段必须 0~255
//            if ((a | b | c | d) < 0 || (a | b | c | d) > 255) {
//                return false;
//            }
//            int ipInt = (a << 24) | (b << 16) | (c << 8) | d;
//            return isPublicIp(ipInt);
//        } catch (NumberFormatException e) {
//            return false;
//        }
//    }

    /**
     * 高效校验：直接用 int 表示的 IP 与预定义掩码比较。
     */
    public static boolean isPublicIp(int ip) {
        // 0.0.0.0/8
        if ((ip & 0xFF000000) == 0x00000000) return false;
        // 10.0.0.0/8
        if ((ip & 0xFF000000) == 0x0A000000) return false;
        // 127.0.0.0/8
        if ((ip & 0xFF000000) == 0x7F000000) return false;
        // 169.254.0.0/16
        if ((ip & 0xFFFF0000) == 0xA9FE0000) return false;
        // 172.16.0.0/12
        if ((ip & 0xFFF00000) == 0xAC100000) return false;
        // 192.168.0.0/16
        if ((ip & 0xFFFF0000) == 0xC0A80000) return false;
        // 224.0.0.0/4 组播
        if ((ip & 0xF0000000) == 0xE0000000) return false;
        // 240.0.0.0/4 保留（E 类）
        if ((ip & 0xF0000000) == 0xF0000000) return false;
        // 100.64.0.0/10 CGNAT
        if ((ip & 0xFFC00000) == 0x64400000) return false;
        // 192.0.2.0/24 TEST-NET-1
        if ((ip & 0xFFFFFF00) == 0xC0000200) return false;
        // 198.51.100.0/24 TEST-NET-2
        if ((ip & 0xFFFFFF00) == 0xC6336400) return false;
        // 203.0.113.0/24 TEST-NET-3
        if ((ip & 0xFFFFFF00) == 0xCB007100) return false;
        // 198.18.0.0/15 基准测试
        if ((ip & 0xFFFE0000) == 0xC6120000) return false;
        // 192.0.0.0/24 IETF 协议分配
        if ((ip & 0xFFFFFF00) == 0xC0000000) return false;
        // 192.88.99.0/24 6to4 Relay Anycast (deprecated but reserved)
        return (ip & 0xFFFFFF00) != 0xC0586300;
    }

    /**
     * 直接基于 int 随机生成，避免任何字符串操作。
     */
    public static String randomPublicIp() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        while (true) {
            // 构造四个随机字节并拼成 int 型 IP
            int a = rng.nextInt(256);
            int b = rng.nextInt(256);
            int c = rng.nextInt(256);
            int d = rng.nextInt(256);
            int ip = (a << 24) | (b << 16) | (c << 8) | d;
            if (isPublicIp(ip)) {
                // 最后一步才转为字符串，尽量复用已有工具
                return a + "." + b + "." + c + "." + d;
            }
        }
    }
}