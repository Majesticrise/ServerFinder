package com.scanner;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
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

    /** 内存去重：key = (ip << 16) | (port & 0xFFFF)。百万条 ~ 50 MB。 */
    private final Set<Long> seen = ConcurrentHashMap.newKeySet();

    private static final String CLEAR_SCREEN = "\033[H\033[2J";
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ResultConsumer-Reporter");
                t.setDaemon(true);
                return t;
            });

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

        if (result.isMinecraft()) {
            foundCount++;
            orchestrator.incrementFound();
            outputLine("[" + scannedCount + "] " + result.ipString() + ":" + result.port()
                    + " - 发现 Minecraft 服务器！");
            saveResult(result);
        } else if (config.displayMode == 1) {
            long elapsed = System.currentTimeMillis() - startTime;
            double speed = elapsed > 0 ? scannedCount / (elapsed / 1000.0) : 0.0;
            outputLine("[" + scannedCount + "] " + result.ipString() + ":" + result.port()
                    + " - 仅端口开放，当前速度 " + String.format("%.1f", speed) + " IP/s");
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

    /**
     * 内存去重 + 写文件。单线程调用（只有 consumer 线程会走到这里），无需加锁。
     */
    private void saveResult(ScanResult result) {
        if (fileWriter == null) return;

        long key = ((long) result.ip() << 16) | (result.port() & 0xFFFFL);
        if (!seen.add(key)) return;     // 已写过，跳过

        try {
            fileWriter.write(result.ipString());
            fileWriter.write(':');
            fileWriter.write(Integer.toString(result.port()));
            fileWriter.write('\n');
            if (++pendingWrites >= FLUSH_THRESHOLD) {
                fileWriter.flush();
                pendingWrites = 0;
            }
        } catch (IOException e) {
            System.err.println("写入文件失败: " + e.getMessage());
        }
    }

    private void flushAndClose() {
        if (fileWriter == null) return;
        try {
            fileWriter.flush();
            fileWriter.close();
        } catch (IOException e) {
            System.err.println("关闭文件失败: " + e.getMessage());
        } finally {
            fileWriter = null;
        }
    }

    private void printFinalSummary() {
        long elapsed = System.currentTimeMillis() - startTime;
        double speed = elapsed > 0 ? scannedCount / (elapsed / 1000.0) : 0.0;
        System.out.println();
        System.out.printf("扫描结束：共扫描 %d 次，发现 %d 个 Minecraft 服务器，平均速度 %.1f IP/s。%n",
                scannedCount, foundCount, speed);
        if (config.outputFile != null) {
            System.out.println("结果已写入 " + config.outputFile);
        }
    }
}