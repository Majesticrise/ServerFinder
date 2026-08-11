package com.scanner;

import java.util.Random;

public final class IpGenerator {
    private static final Random RAND = new Random();

    public static boolean isPublicIp(String ip) {
        String[] parts = ip.split("\\.");
        if (parts.length != 4) return false;
        try {
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            int c = Integer.parseInt(parts[2]);
            int d = Integer.parseInt(parts[3]);
            // 过滤私有、回环、链路本地、组播、保留等
            if (a == 0) return false;                       // 0.0.0.0/8
            if (a == 10) return false;                      // 10.0.0.0/8
            if (a == 127) return false;                     // 127.0.0.0/8
            if (a == 169 && b == 254) return false;         // 169.254.0.0/16
            if (a == 172 && b >= 16 && b <= 31) return false; // 172.16.0.0/12
            if (a == 192 && b == 168) return false;         // 192.168.0.0/16
            if (a >= 224 && a <= 239) return false;         // 224.0.0.0/4 组播
            if (a >= 240) return false;
            if (a == 100 && b >= 64 && b <= 127) return false; // 过滤 CGNAT// 240.0.0.0/4 保留
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public static String randomPublicIp() {
        while (true) {
            int a = RAND.nextInt(256);
            int b = RAND.nextInt(256);
            int c = RAND.nextInt(256);
            int d = RAND.nextInt(256);
            String ip = a + "." + b + "." + c + "." + d;
            if (isPublicIp(ip)) return ip;
        }
    }
}