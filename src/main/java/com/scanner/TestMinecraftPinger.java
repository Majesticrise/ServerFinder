package com.scanner;

import java.net.InetAddress;
import java.net.Proxy;

public class TestMinecraftPinger {
    public static void main(String[] args) throws Exception {
        String testIp = "66.248.197.28";
        int port = 25565;
        double timeout = 9.0;

        String ip = InetAddress.getByName(testIp).getHostAddress();
        boolean result = MinecraftPinger.isMinecraftServer(ip, port, timeout);
        System.out.println("Hypixel 检测结果: " + (result ? "✅ 成功" : "❌ 失败"));

        // 可选：测试代理模式（如果启用了代理）
        Proxy proxy = ProxyManager.getInstance().getProxy();
        boolean resultWithProxy = MinecraftPinger.isMinecraftServer(ip, port, timeout, proxy);
        System.out.println("代理检测: " + resultWithProxy);
    }
}