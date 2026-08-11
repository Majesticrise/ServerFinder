package com.scanner;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class Main {
    private static final java.io.BufferedReader console = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
    private static final String CONFIG_FILE = "scanner_config.json";
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public static void main(String[] args) {
        System.out.println("Minecraft 服务器扫描器 CMD 版本 (Java)");
        System.out.println("==============================");
        System.out.println();

        // ---- 加载配置 ----
        Config config = loadConfig();

        boolean isAdmin = isAdmin();
        if (isAdmin) {
            System.out.println("当前运行：管理员模式，SYN 扫描可用（但本实现暂未启用）。");
        } else {
            System.out.println("当前运行：非管理员模式，仅支持 TCP Connect 扫描。");
        }

        // ---- 交互式参数（覆盖配置文件中的值） ----
        System.out.println("请选择扫描模式：");
        System.out.println("1) 普通扫描（指定总数）");
        System.out.println("2) 持续扫描（无限循环，按 Ctrl+C 停止）");
        int mode = promptInt("输入 1 或 2（默认 2）: ", 2, 1, 2);
        config.total = (mode == 1) ? promptInt("生成数量（默认 102400）: ", 102400, 1, Integer.MAX_VALUE) : -1;

        config.concurrency = promptInt("并发数（默认 10240）: ", 10240, 1, Integer.MAX_VALUE);
        config.timeout = promptDouble("超时时间（秒，默认 2.5）: ", 2.5, 0.1);
        config.port = promptInt("端口（默认 25565）: ", 25565, 1, 65535);

        config.displayMode = promptInt(
                "请选择展示方式：1) 当前方式 2) 每3秒展示实时IP速度，确认服务器后立即展示（默认 2）: ",
                2, 1, 2);

        if (isAdmin) {
            boolean useSyn = promptYesNo("是否启用 SYN 扫描（需要管理员权限，但本版本尚未实现）", false);
            config.useSyn = useSyn;
            if (useSyn) System.out.println("注意：SYN 扫描功能本版本未实现，将回退到 TCP Connect。");
        } else {
            System.out.println("注意：当前非管理员，已禁用 SYN 扫描。");
            config.useSyn = false;
        }

        config.autoClear = promptYesNo("是否启用自动清屏", true);
        if (config.autoClear) {
            config.maxLines = promptInt("自动清屏阈值（行数，默认 128）: ", 128, 10, Integer.MAX_VALUE);
        }

        boolean save = promptYesNo("是否将发现的服务器保存到文件 (found_servers.txt)", true);
        config.outputFile = save ? "found_servers.txt" : null;

        config.adaptive = promptYesNo("是否启用自适应并发（根据发现速率自动调整）", true);

        boolean useProxy = promptYesNo("是否启用SOCKS5代理池（隐藏真实IP，避免封禁）", false);
        config.useProxy = useProxy;

        // ---- 保存用户交互后的配置（覆盖文件） ----
        saveConfig(config);

        // ---- 打印参数 ----
        System.out.println("\n扫描参数：");
        System.out.println("  模式: " + (config.total == -1 ? "持续扫描" : "普通扫描"));
        if (config.total != -1) System.out.println("  扫描总数: " + config.total);
        System.out.println("  并发数: " + config.concurrency);
        System.out.println("  超时时间: " + config.timeout + " 秒");
        System.out.println("  端口: " + config.port);
        System.out.println("  SYN 扫描: " + (config.useSyn ? "启用（未实现）" : "禁用"));
        System.out.println("  展示方式: " + config.displayMode);
        System.out.println("  自动清屏: " + (config.autoClear ? "启用" : "禁用"));
        if (config.autoClear) System.out.println("  清屏阈值: " + config.maxLines + " 行");
        System.out.println("  结果保存: " + (config.outputFile != null ? "是" : "否"));
        System.out.println("  自适应并发: " + (config.adaptive ? "启用（基于发现速率）" : "禁用"));
        System.out.println("  代理池: " + (config.useProxy ? "启用（SOCKS5）" : "禁用"));
        if (config.cachedBestConcurrency > 0) {
            System.out.println("  历史最优并发: " + config.cachedBestConcurrency);
        }
        System.out.println();

        // ---- 创建 orchestrator ----
        BlockingQueue<ScanResult> resultQueue = new LinkedBlockingQueue<>(10240);
        AtomicBoolean stopFlag = new AtomicBoolean(false);

        // 提供缓存保存回调
        Consumer<Integer> cacheSaver = (best) -> {
            config.cachedBestConcurrency = best;
            saveConfig(config);
        };

        ScanOrchestrator orchestrator = new ScanOrchestrator(config, resultQueue, cacheSaver);

        // ---- 根据代理启用状态启动管理器 ----
        if (config.useProxy) {
            ProxyManager.getInstance().start(orchestrator::getActiveTasks);
        } else {
            ProxyManager.getInstance().shutdown();
        }

        // ---- 创建消费者并传入 orchestrator ----
        ResultConsumer consumer = new ResultConsumer(
                config,
                resultQueue,
                stopFlag,
                orchestrator::getActiveTasks,
                orchestrator::getProxyTaskCount,
                orchestrator  // 传入引用
        );

        // 注册 Ctrl+C 钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stopFlag.set(true);
            System.out.println("\n正在停止扫描...");
        }));

        Thread consumerThread = new Thread(consumer);
        consumerThread.start();

        try {
            orchestrator.start();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            stopFlag.set(true);
            try {
                consumerThread.join();
            } catch (InterruptedException ignored) {}
            ProxyManager.getInstance().shutdown();
            saveConfig(config);
        }
    }

    // ---- 配置加载与保存 ----
    private static Config loadConfig() {
        File configFile = new File(CONFIG_FILE);
        Config config = new Config(); // 默认值

        if (!configFile.exists()) {
            // 生成默认配置文件
            try (FileWriter fw = new FileWriter(configFile)) {
                gson.toJson(config, fw);
                System.out.println("[配置] 已生成默认配置文件 " + CONFIG_FILE + "，请根据需要修改后再运行。");
                System.out.println("按 Enter 键继续（使用默认配置）...");
                try {
                    System.in.read();
                } catch (IOException ignored) {}
            } catch (IOException e) {
                System.err.println("[配置] 无法创建配置文件: " + e.getMessage());
            }
        } else {
            try (FileReader fr = new FileReader(configFile)) {
                config = gson.fromJson(fr, Config.class);
                System.out.println("[配置] 已加载外部配置文件");
            } catch (IOException e) {
                System.err.println("[配置] 加载失败，使用默认配置");
            }
        }

        // 兼容旧版缓存文件（如果存在且配置中未设置）
        if (config.cachedBestConcurrency <= 0) {
            File oldCache = new File("best_concurrency.txt");
            if (oldCache.exists()) {
                try (BufferedReader br = new BufferedReader(new FileReader(oldCache))) {
                    String line = br.readLine();
                    if (line != null) {
                        config.cachedBestConcurrency = Integer.parseInt(line.trim());
                        System.out.println("[缓存] 从旧文件迁移历史最优并发: " + config.cachedBestConcurrency);
                        // 迁移后删除旧文件（可选）
                        oldCache.delete();
                    }
                } catch (Exception e) {
                    System.err.println("[缓存] 迁移失败: " + e.getMessage());
                }
            }
        }

        return config;
    }

    private static void saveConfig(Config config) {
        try (FileWriter fw = new FileWriter(CONFIG_FILE)) {
            gson.toJson(config, fw);
        } catch (IOException e) {
            System.err.println("[配置] 保存失败: " + e.getMessage());
        }
    }

    // ---- 辅助输入方法（保持不变） ----
    private static int promptInt(String prompt, int defaultValue, int min, int max) {
        while (true) {
            System.out.print(prompt);
            try {
                String line = console.readLine().trim();
                if (line.isEmpty()) return defaultValue;
                int val = Integer.parseInt(line);
                if (val < min || val > max) {
                    System.out.printf("请输入 %d 到 %d 之间的整数。\n", min, max);
                    continue;
                }
                return val;
            } catch (Exception e) {
                System.out.println("请输入有效整数。");
            }
        }
    }

    private static double promptDouble(String prompt, double defaultValue, double min) {
        while (true) {
            System.out.print(prompt);
            try {
                String line = console.readLine().trim();
                if (line.isEmpty()) return defaultValue;
                double val = Double.parseDouble(line);
                if (val < min) {
                    System.out.printf("请输入大于等于 %.1f 的数字。\n", min);
                    continue;
                }
                return val;
            } catch (Exception e) {
                System.out.println("请输入有效数字。");
            }
        }
    }

    private static boolean promptYesNo(String prompt, boolean defaultYes) {
        while (true) {
            String suffix = defaultYes ? " [Y/n]: " : " [y/N]: ";
            System.out.print(prompt + suffix);
            try {
                String line = console.readLine().trim().toLowerCase();
                if (line.isEmpty()) return defaultYes;
                if (line.matches("[yes是]")) return true;
                if (line.matches("[no否]")) return false;
                System.out.println("请输入 Y/y 或 N/n。");
            } catch (Exception e) {
                System.out.println("输入错误。");
            }
        }
    }

    private static boolean isAdmin() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            try {
                Process p = Runtime.getRuntime().exec("net session");
                return p.waitFor() == 0;
            } catch (Exception e) {
                return false;
            }
        }
        return System.getProperty("user.name").equals("root") ||
                System.getenv("USER").equals("root");
    }
}