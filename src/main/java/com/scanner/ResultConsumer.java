package com.scanner;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.IntSupplier;

public class ResultConsumer implements Runnable {
    private final Config config;
    private final BlockingQueue<ScanResult> queue;
    private final AtomicBoolean stopFlag;
    private final IntSupplier activeTasksSupplier;
    private final IntSupplier proxyTaskSupplier;
    private final ScanOrchestrator orchestrator;

    private long scannedCount = 0;
    private long foundCount = 0;
    private final long startTime = System.currentTimeMillis();
    private int lineCount = 0;
    private BufferedWriter fileWriter = null;
    private int pendingWrites = 0;
    private static final int FLUSH_THRESHOLD = 1;

    private static final String CLEAR_SCREEN = "\033[H\033[2J";
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private final ReentrantReadWriteLock fileLock = new ReentrantReadWriteLock();

    public ResultConsumer(Config config, BlockingQueue<ScanResult> queue, AtomicBoolean stopFlag,
                          IntSupplier activeTasksSupplier, IntSupplier proxyTaskSupplier,
                          ScanOrchestrator orchestrator) {
        this.config = config;
        this.queue = queue;
        this.stopFlag = stopFlag;
        this.activeTasksSupplier = activeTasksSupplier;
        this.proxyTaskSupplier = proxyTaskSupplier;
        this.orchestrator = orchestrator;

        if (config.outputFile != null) {
            try {
                File parent = new File(config.outputFile).getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                fileWriter = new BufferedWriter(new FileWriter(config.outputFile, true), 8192);
            } catch (IOException e) {
                System.err.println("无法打开输出文件: " + e.getMessage());
            }
        }

        if (config.enableFileDedup && config.fileDedupIntervalSeconds > 0 && config.outputFile != null) {
            deduplicateFile();
            scheduler.scheduleAtFixedRate(this::deduplicateFile,
                    config.fileDedupIntervalSeconds,
                    config.fileDedupIntervalSeconds,
                    TimeUnit.SECONDS);
            System.out.println("[去重] 已启动，间隔 " + config.fileDedupIntervalSeconds + " 秒");
        }
    }

    @Override
    public void run() {
        if (config.displayMode == 2) {
            scheduler.scheduleAtFixedRate(this::printSummaryLine, 3, 3, TimeUnit.SECONDS);
        }

        try {
            while (!stopFlag.get()) {
                ScanResult result = queue.poll(500, TimeUnit.MILLISECONDS);
                if (result == null) {
                    if (stopFlag.get() && queue.isEmpty()) break;
                    continue;
                }
                if (result.isPoisonPill()) {
                    break;
                }
                processResult(result);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            scheduler.shutdownNow();
            flushAndClose();
            printFinalSummary();
        }
    }

    private void processResult(ScanResult result) {
        scannedCount++;
        orchestrator.incrementCompleted();

        long elapsed = System.currentTimeMillis() - startTime;
        double speed = elapsed > 0 ? scannedCount / (elapsed / 1000.0) : 0.0;

        if (config.displayMode == 1) {
            if (result.ip() == null) {
                outputLine("[" + scannedCount + "] " + result.port() + " - 端口未响应或扫描失败，当前速度 " +
                        String.format("%.1f", speed) + " IP/s");
            } else if (result.isMinecraft()) {
                foundCount++;
                orchestrator.incrementFound();
                String line = "[" + scannedCount + "] " + result.ip() + ":" + result.port() + " - 发现 Minecraft 服务器！";
                outputLine(line);
                saveResult(result);
            } else {
                outputLine("[" + scannedCount + "] " + result.ip() + ":" + result.port() + " - 仅端口开放，当前速度 " +
                        String.format("%.1f", speed) + " IP/s");
            }
        } else {
            if (result.isMinecraft()) {
                foundCount++;
                orchestrator.incrementFound();
                String line = "[" + scannedCount + "] " + result.ip() + ":" + result.port() + " - 发现 Minecraft 服务器！";
                outputLine(line);
                saveResult(result);
            }
        }
    }

    private void outputLine(String text) {
        if (config.autoClear && lineCount >= config.maxLines) {
            System.out.print(CLEAR_SCREEN);
            System.out.flush();
            lineCount = 0;
            System.out.println("Minecraft 服务器扫描器 CMD 版本 (Java)");
            System.out.println("==============================");
        }
        System.out.println(text);
        lineCount++;
        if (config.displayMode == 2) {
            printSummaryLine();
        }
    }

    private void printSummaryLine() {
        long elapsed = System.currentTimeMillis() - startTime;
        double speed = elapsed > 0 ? scannedCount / (elapsed / 1000.0) : 0.0;
        String totalStr = config.total == -1 ? "∞" : String.valueOf(config.total);
        int active = activeTasksSupplier.getAsInt();
        int proxyTasks = proxyTaskSupplier.getAsInt();
        double coverage = active > 0 ? (proxyTasks * 100.0 / active) : 0.0;

        String line = String.format("[%s] 已扫描 %d/%s，活跃 %d，代理 %d (%.1f%%)，发现 %d，速度 %.1f IP/s \n",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                scannedCount, totalStr, active, proxyTasks, coverage, foundCount, speed);
        System.out.print('\r' + line);
        System.out.flush();
    }

    private void saveResult(ScanResult result) {
        if (fileWriter == null || result.ip() == null) return;

        fileLock.writeLock().lock();
        try {
            fileWriter.write(result.ip() + ":" + result.port() + "\n");
            pendingWrites++;
            if (pendingWrites >= FLUSH_THRESHOLD) {
                fileWriter.flush();
                pendingWrites = 0;
            }
        } catch (IOException e) {
            System.err.println("写入文件失败: " + e.getMessage());
        } finally {
            fileLock.writeLock().unlock();
        }
    }

    private void flushAndClose() {
        if (fileWriter == null) return;

        fileLock.writeLock().lock();
        try {
            fileWriter.flush();
            fileWriter.close();
        } catch (IOException e) {
            System.err.println("关闭文件失败: " + e.getMessage());
        } finally {
            fileWriter = null;
            fileLock.writeLock().unlock();
        }
    }


    private void deduplicateFile() {
        String filename = config.outputFile;
        if (filename == null) return;
        File file = new File(filename);
        if (!file.exists() || file.length() == 0) return;

        fileLock.writeLock().lock();
        try {
            List<String> lines = Files.readAllLines(Paths.get(filename), StandardCharsets.UTF_8);
            if (lines.isEmpty()) return;

            LinkedHashMap<String, String> uniqueMap = new LinkedHashMap<>();
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;
                // 直接以完整行（即IP:PORT）为键
                if (!uniqueMap.containsKey(line)) {
                    uniqueMap.put(line, line);
                }
            }

            if (uniqueMap.size() < lines.size()) {
                try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(filename), StandardCharsets.UTF_8)) {
                    for (String line : uniqueMap.values()) {
                        writer.write(line);
                        writer.newLine();
                    }
                }
                System.out.println("[去重] 文件 " + filename + " 去重完成，行数从 " + lines.size() + " 减少至 " + uniqueMap.size());
            }
        } catch (IOException e) {
            System.err.println("[去重] 处理文件失败: " + e.getMessage());
        } finally {
            fileLock.writeLock().unlock();
        }
    }

    private void printFinalSummary() {
        long elapsed = System.currentTimeMillis() - startTime;
        double speed = elapsed > 0 ? scannedCount / (elapsed / 1000.0) : 0.0;
        System.out.println();
        System.out.printf("扫描结束：共扫描 %d 次，发现 %d 个 Minecraft 服务器，平均速度 %.1f IP/s。%n",
                scannedCount, foundCount, speed);
    }
}