package com.scanner;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Scanner;

public class TestMinecraftPinger {

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        System.out.print("服务器列表文件路径（每行 host:port）：");
        String filePath = scanner.nextLine().trim();

        System.out.print("最多测试多少行（默认全部，直接回车）：");
        String limitInput = scanner.nextLine().trim();
        int maxLines = limitInput.isEmpty() ? Integer.MAX_VALUE : Integer.parseInt(limitInput);

        System.out.print("超时秒数（默认 5.0）：");
        String timeoutInput = scanner.nextLine().trim();
        double timeout = timeoutInput.isEmpty() ? 5.0 : Double.parseDouble(timeoutInput);

        scanner.close();

        List<String> lines;
        try {
            lines = Files.readAllLines(Paths.get(filePath));
        } catch (Exception e) {
            System.err.println("读取文件失败: " + e.getMessage());
            return;
        }

        int limit = Math.min(lines.size(), maxLines);

        System.out.println();
        System.out.println("========== Minecraft 服务器 Ping 测试 ==========");
        System.out.println("文件行数: " + lines.size() + "，本次测试: " + limit + "，超时: " + timeout + "s");
        System.out.println();

        List<Long> reachableNs = new ArrayList<>();
        List<Long> unreachableNs = new ArrayList<>();

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
                System.out.printf("[%d/%d] %s (%s:%d) ... ", i + 1, limit, host, ip, port);

                long t0 = System.nanoTime();
                boolean result = MinecraftPinger.isMinecraftServer(ip, port, timeout);
                long elapsed = System.nanoTime() - t0;

                tested++;
                if (result) {
                    reachableNs.add(elapsed);
                    System.out.printf("✓ 可达 (%.1f ms)%n", elapsed / 1e6);
                } else {
                    unreachableNs.add(elapsed);
                    System.out.printf("✗ 不可达 (%.1f ms)%n", elapsed / 1e6);
                }
            } catch (Exception e) {
                System.err.println("异常: " + e.getMessage());
            }
        }

        System.out.println();
        System.out.println("========== 测试结束（共测试 " + tested + " 个，可达 " + reachableNs.size() + " 个） ==========");
        printStats("可达服务器", reachableNs);
        printStats("不可达（含超时）", unreachableNs);

        if (reachableNs.size() >= 3) {
            long p99 = pct(sorted(reachableNs), 0.99);
            long max = sorted(reachableNs).get(reachableNs.size() - 1);
            System.out.println();
            System.out.println("── 超时建议 ──");
            System.out.printf("  P99 × 1.5  = %s   （保守，误伤率 <1%%）%n", fmt(p99 * 3 / 2));
            System.out.printf("  MAX × 1.2  = %s   （最保守，不误伤任何已知样本）%n", fmt(max * 6 / 5));
            System.out.println("  样本数 ≥ 30 时建议才可靠，当前 " + reachableNs.size());
        }
    }

    private static List<Long> sorted(List<Long> in) {
        List<Long> copy = new ArrayList<>(in);
        Collections.sort(copy);
        return copy;
    }

    private static void printStats(String label, List<Long> times) {
        if (times.isEmpty()) {
            System.out.println();
            System.out.println("[" + label + "] 无样本");
            return;
        }
        List<Long> s = sorted(times);

        long min = s.get(0);
        long max = s.get(s.size() - 1);
        long sum = 0;
        for (long t : s) sum += t;
        double avg = (double) sum / s.size();

        System.out.println();
        System.out.println("[" + label + "] n=" + s.size());
        System.out.printf("  min    = %s%n", fmt(min));
        System.out.printf("  avg    = %s%n", fmt((long) avg));
        System.out.printf("  p50    = %s%n", fmt(pct(s, 0.50)));
        System.out.printf("  p75    = %s%n", fmt(pct(s, 0.75)));
        System.out.printf("  p90    = %s%n", fmt(pct(s, 0.90)));
        System.out.printf("  p95    = %s%n", fmt(pct(s, 0.95)));
        System.out.printf("  p99    = %s%n", fmt(pct(s, 0.99)));
        System.out.printf("  max    = %s%n", fmt(max));
    }

    private static long pct(List<Long> sorted, double q) {
        int idx = (int) Math.min(sorted.size() - 1, Math.max(0, Math.ceil(sorted.size() * q) - 1));
        return sorted.get(idx);
    }

    private static String fmt(long ns) {
        if (ns < 1_000) return ns + " ns";
        if (ns < 1_000_000) return String.format("%.1f µs", ns / 1e3);
        if (ns < 1_000_000_000) return String.format("%.1f ms", ns / 1e6);
        return String.format("%.2f s", ns / 1e9);
    }
}