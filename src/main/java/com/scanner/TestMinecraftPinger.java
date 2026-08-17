package com.scanner;

import java.net.InetAddress;
import java.net.Proxy;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Scanner;

public class TestMinecraftPinger {

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        System.out.print("请输入服务器列表文件路径（每行格式：主机:端口）：");
        String filePath = scanner.nextLine().trim();
        scanner.close();

        List<String> lines;
        try {
            lines = Files.readAllLines(Paths.get(filePath));
        } catch (Exception e) {
            System.err.println("❌ 读取文件失败: " + e.getMessage());
            return;
        }

        // 只取前50行（如果不足50行则全部取）
        int limit = Math.min(lines.size(), 50);
        double timeout = 5.0;

        Proxy proxy = null;
        try {
            proxy = ProxyManager.getInstance().getProxy();
        } catch (Exception e) {
            System.out.println("⚠ 代理获取失败: " + e.getMessage());
        }

        System.out.println("========== Minecraft 服务器 Ping 测试 (前 " + limit + " 个) ==========");
        System.out.println("超时: " + timeout + " 秒\n");

        int tested = 0;
        for (int i = 0; i < limit; i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;

            String[] parts = line.split(":");
            if (parts.length != 2) {
                System.err.println("格式错误，跳过: " + line);
                continue;
            }
            String host = parts[0];
            int port;
            try {
                port = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                System.err.println("端口格式错误，跳过: " + line);
                continue;
            }

            try {
                String ip = InetAddress.getByName(host).getHostAddress();
                System.out.print("测试 " + host + " (" + ip + ":" + port + ") ... ");
                boolean result = MinecraftPinger.isMinecraftServer(ip, port, timeout);
                System.out.println(result ? "✅ 可达" : "❌ 不可达");

                if (proxy != null) {
                    boolean resultProxy = MinecraftPinger.isMinecraftServer(ip, port, timeout, proxy);
                    System.out.println("  代理测试: " + (resultProxy ? "✅ 可达" : "❌ 不可达"));
                }
                tested++;
            } catch (Exception e) {
                System.err.println("测试 " + host + " 异常: " + e.getMessage());
            }
        }

        System.out.println("\n========== 测试结束 (共测试 " + tested + " 个) ==========");
    }
}