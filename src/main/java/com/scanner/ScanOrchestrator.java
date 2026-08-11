package com.scanner;

import com.sun.management.OperatingSystemMXBean;

import java.lang.management.ManagementFactory;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class ScanOrchestrator {
    private final Config config;
    private final BlockingQueue<ScanResult> resultQueue;
    private final AtomicBoolean stopFlag = new AtomicBoolean(false);
    private final AtomicInteger activeTasks = new AtomicInteger(0);
    private final AdaptiveSemaphore semaphore;
    private final AtomicInteger proxyTaskCount = new AtomicInteger(0);
    private final Consumer<Integer> cacheSaver;

    private final AtomicLong totalCompleted = new AtomicLong(0);
    private final AtomicLong totalFound = new AtomicLong(0);

    private enum Phase { EXPONENTIAL_DOWN, GOLDEN_SECTION, LOCKED }
    private volatile Phase phase = Phase.EXPONENTIAL_DOWN;

    private int expCurrentConcurrency;
    private final double expFactor;
    private double bestMetric1 = -Double.MAX_VALUE;
    private double bestMetric2 = -Double.MAX_VALUE;
    private int bestConc1 = -1, bestConc2 = -1;
    private int expTestedCount = 0;

    private double goldA, goldB;
    private double goldX1, goldX2;
    private double goldF1, goldF2;
    private int goldIter;
    private boolean goldNeedTestX1, goldNeedTestX2;
    private static final double GOLDEN_RATIO = 0.618033988749895;
    private static final int GOLDEN_MAX_ITER = 25;

    private int bestConcurrency;
    private double bestMetric = -Double.MAX_VALUE;
    private Thread monitorThread = null;

    private final int absoluteMin;
    private final int absoluteMax;

    private static final double NOISE_THRESHOLD = 0.10;
    private static final double STABILITY_THRESHOLD = 0.05;

    public int getProxyTaskCount() {
        return proxyTaskCount.get();
    }

    public int getActiveTasks() {
        return activeTasks.get();
    }

    public ScanOrchestrator(Config config, BlockingQueue<ScanResult> resultQueue, Consumer<Integer> cacheSaver) {
        this.config = config;
        this.resultQueue = resultQueue;
        this.cacheSaver = cacheSaver;
        this.semaphore = new AdaptiveSemaphore(config.concurrency);

        absoluteMin = config.minConcurrency;
        absoluteMax = config.maxConcurrency;
        expFactor = config.expFactor;
    }

    public void incrementCompleted() {
        totalCompleted.incrementAndGet();
    }

    public void incrementFound() {
        totalFound.incrementAndGet();
    }

    private boolean validateCache(int cachedConcurrency) {
        System.out.println("[缓存] 开始健康检查，并发=" + cachedConcurrency + "，测试500个IP...");
        int testCount = 500;
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        AtomicInteger errors = new AtomicInteger(0);
        AtomicInteger attempts = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(testCount);
        AtomicBoolean testStop = new AtomicBoolean(false);

        semaphore.setMaxPermits(cachedConcurrency);
        long startTime = System.currentTimeMillis();
        for (int i = 0; i < testCount; i++) {
            try {
                semaphore.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            executor.submit(() -> {
                try {
                    if (testStop.get()) return;
                    attempts.incrementAndGet();
                    String ip = IpGenerator.randomPublicIp();
                    // 使用直连检测端口，记录超时或连接失败为错误
                    boolean open = PortChecker.isPortOpen(ip, config.port, config.timeout, null);
                    if (!open) {
                        errors.incrementAndGet();
                    }
                } finally {
                    semaphore.release();
                    latch.countDown();
                }
            });
        }

        try {
            latch.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        executor.shutdownNow();
        long elapsed = System.currentTimeMillis() - startTime;
        int totalAttempts = attempts.get();
        if (totalAttempts == 0) {
            System.out.println("[缓存] 测试未完成，视为无效");
            return false;
        }
        double failRate = (double) errors.get() / totalAttempts;
        double speed = totalAttempts / (elapsed / 1000.0);
        System.out.printf("[缓存] 健康检查完成: 尝试%d, 错误%d, 失败率%.2f%%, 速度%.1f IP/s\n",
                totalAttempts, errors.get(), failRate * 100, speed);
        // 认为失败率低于25%且速度大于10 IP/s 为健康
        return failRate < 0.25 && speed > 10;
    }

    public void start() throws InterruptedException {
        if (config.cachedBestConcurrency > 0) {
            if (!validateCache(config.cachedBestConcurrency)) {
                System.out.println("[缓存] 缓存值已失效，将进行指数下降粗扫。");
                config.cachedBestConcurrency = 0;
                if (cacheSaver != null) cacheSaver.accept(0);
                phase = Phase.EXPONENTIAL_DOWN;
                expCurrentConcurrency = absoluteMax;
                bestMetric1 = bestMetric2 = -Double.MAX_VALUE;
                bestConc1 = bestConc2 = -1;
                expTestedCount = 0;
            } else {
                System.out.println("[缓存] 缓存值有效，直接进入黄金分割搜索。");
                bestConcurrency = config.cachedBestConcurrency;
                bestMetric = -Double.MAX_VALUE;
                goldA = Math.max(absoluteMin, config.cachedBestConcurrency * 0.8);
                goldB = Math.min(absoluteMax, config.cachedBestConcurrency * 1.2);
                if (goldB - goldA < 50) {
                    goldA = Math.max(absoluteMin, config.cachedBestConcurrency - 100);
                    goldB = Math.min(absoluteMax, config.cachedBestConcurrency + 100);
                }
                phase = Phase.GOLDEN_SECTION;
                goldNeedTestX1 = true;
                goldNeedTestX2 = true;
                goldIter = 0;
                goldX1 = goldB - GOLDEN_RATIO * (goldB - goldA);
                goldX2 = goldA + GOLDEN_RATIO * (goldB - goldA);
            }
        } else {
            phase = Phase.EXPONENTIAL_DOWN;
            expCurrentConcurrency = absoluteMax;
            bestMetric1 = bestMetric2 = -Double.MAX_VALUE;
            bestConc1 = bestConc2 = -1;
            expTestedCount = 0;
            System.out.println("[自适应] 开始指数下降粗扫，初始并发 " + expCurrentConcurrency);
        }

        if (config.adaptive) {
            monitorThread = new Thread(this::adaptiveMonitor);
            monitorThread.setDaemon(true);
            monitorThread.start();
        }

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        Thread producer = new Thread(() -> {
            int count = 0;
            try {
                while ((config.total == -1 || count < config.total) && !stopFlag.get()) {
                    semaphore.acquire();
                    if (stopFlag.get()) {
                        semaphore.release();
                        break;
                    }
                    activeTasks.incrementAndGet();

                    ScanWorker worker = new ScanWorker(
                            config,
                            config.port,
                            config.timeout,
                            result -> {
                                try {
                                    resultQueue.put(result);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                } finally {
                                    incrementCompleted();
                                    if (result.isMinecraft()) {
                                        incrementFound();
                                    }
                                    activeTasks.decrementAndGet();
                                    semaphore.release();
                                }
                            },
                            stopFlag,
                            proxyTaskCount
                    );
                    executor.submit(worker);
                    count++;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                try {
                    resultQueue.put(ScanResult.POISON_PILL);
                } catch (InterruptedException ignored) {}
                executor.shutdown();
                if (monitorThread != null) {
                    monitorThread.interrupt();
                }
            }
        });
        producer.start();
        producer.join();
    }

    private void adaptiveMonitor() {
        OperatingSystemMXBean osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            return;
        }

        int cached = config.cachedBestConcurrency;
        if (cached > 0) {
            bestConcurrency = cached;
            bestMetric = -Double.MAX_VALUE;
            goldA = Math.max(absoluteMin, cached * 0.8);
            goldB = Math.min(absoluteMax, cached * 1.2);
            if (goldB - goldA < 50) {
                goldA = Math.max(absoluteMin, cached - 100);
                goldB = Math.min(absoluteMax, cached + 100);
            }
            phase = Phase.GOLDEN_SECTION;
            goldNeedTestX1 = true;
            goldNeedTestX2 = true;
            goldIter = 0;
            System.out.println("[自适应] 使用缓存值 " + cached + "，直接进入黄金分割搜索，区间 [" + (int)goldA + "," + (int)goldB + "]");
        } else {
            expCurrentConcurrency = absoluteMax;
            bestMetric1 = bestMetric2 = -Double.MAX_VALUE;
            bestConc1 = bestConc2 = -1;
            expTestedCount = 0;
            phase = Phase.EXPONENTIAL_DOWN;
            System.out.println("[自适应] 开始指数下降粗扫，初始并发 " + expCurrentConcurrency);
        }

        if (phase == Phase.GOLDEN_SECTION) {
            goldX1 = goldB - GOLDEN_RATIO * (goldB - goldA);
            goldX2 = goldA + GOLDEN_RATIO * (goldB - goldA);
        }

        while (!Thread.currentThread().isInterrupted()) {
            try {
                double memUsage = (double) (osBean.getTotalMemorySize() - osBean.getFreeMemorySize())
                        / osBean.getTotalMemorySize();
                if (memUsage > config.memoryEmergencyThreshold) {
                    int currentMax = semaphore.getMaxPermits();
                    int newMax = (int)(currentMax * config.memoryDowngradeFactor);
                    newMax = Math.max(newMax, absoluteMin);
                    semaphore.setMaxPermits(newMax);
                    System.out.println("[紧急] 内存超限，临时降速至 " + newMax + " (原 " + currentMax + ")");
                    Thread.sleep(5000);
                    continue;
                }

                switch (phase) {
                    case EXPONENTIAL_DOWN:
                        processExponentialDown();
                        break;
                    case GOLDEN_SECTION:
                        processGoldenSection();
                        break;
                    case LOCKED:
                        processLocked(osBean);
                        break;
                }
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private void processExponentialDown() throws InterruptedException {
        int concurrency = expCurrentConcurrency;
        semaphore.setMaxPermits(concurrency);
        System.out.println("[粗扫] 测试并发 " + concurrency + " (已测 " + expTestedCount + " 点)");
        double metric = testConcurrencyWithEarlyStop(concurrency);
        System.out.println("[粗扫结果] 并发=" + concurrency + " 指标=" + String.format("%.4f", metric));

        if (metric != Double.NEGATIVE_INFINITY) {
            if (metric > bestMetric1) {
                bestMetric2 = bestMetric1;
                bestConc2 = bestConc1;
                bestMetric1 = metric;
                bestConc1 = concurrency;
            } else if (metric > bestMetric2) {
                bestMetric2 = metric;
                bestConc2 = concurrency;
            }
        } else {
            System.out.println("[粗扫] 并发 " + concurrency + " 无发现，忽略");
        }

        expTestedCount++;
        expCurrentConcurrency = (int) (concurrency * expFactor);
        if (expCurrentConcurrency < absoluteMin) {
            expCurrentConcurrency = absoluteMin;
        }

        if (concurrency == absoluteMin || expTestedCount >= config.expMaxPoints) {
            finishExponentialDown();
        }
    }

    private void finishExponentialDown() {
        if (bestConc1 == -1 || bestConc2 == -1) {
            // 如果只有一个有效点，则以其为中心
            if (bestConc1 != -1) {
                goldA = Math.max(absoluteMin, bestConc1 * 0.7);
                goldB = Math.min(absoluteMax, bestConc1 * 1.3);
                System.out.println("[粗扫] 仅一个有效点，以 " + bestConc1 + " 为中心形成区间 [" + (int)goldA + "," + (int)goldB + "]");
                phase = Phase.GOLDEN_SECTION;
                goldNeedTestX1 = true;
                goldNeedTestX2 = true;
                goldIter = 0;
                bestMetric = bestMetric1;
                bestConcurrency = bestConc1;
                goldX1 = goldB - GOLDEN_RATIO * (goldB - goldA);
                goldX2 = goldA + GOLDEN_RATIO * (goldB - goldA);
                return;
            }
            // 完全无有效点，使用最低并发
            bestConcurrency = absoluteMin;
            bestMetric = -Double.MAX_VALUE;
            System.out.println("[粗扫] 无有效点，使用最低并发 " + bestConcurrency + " 并进入锁定");
            phase = Phase.LOCKED;
            return;
        }

        int low = Math.min(bestConc1, bestConc2);
        int high = Math.max(bestConc1, bestConc2);
        goldA = Math.max(absoluteMin, low * 0.8);
        goldB = Math.min(absoluteMax, high * 1.2);
        if (goldB - goldA < 50) {
            double mid = (goldA + goldB) / 2;
            goldA = Math.max(absoluteMin, mid - 100);
            goldB = Math.min(absoluteMax, mid + 100);
        }
        System.out.println("[粗扫完成] 最佳两点: " + bestConc1 + " (" + String.format("%.4f", bestMetric1) + "), "
                + bestConc2 + " (" + String.format("%.4f", bestMetric2) + "), 黄金分割区间 [" + (int)goldA + "," + (int)goldB + "]");
        phase = Phase.GOLDEN_SECTION;
        goldNeedTestX1 = true;
        goldNeedTestX2 = true;
        goldIter = 0;
        bestMetric = bestMetric1;
        bestConcurrency = bestConc1;
        goldX1 = goldB - GOLDEN_RATIO * (goldB - goldA);
        goldX2 = goldA + GOLDEN_RATIO * (goldB - goldA);
    }

    private void processGoldenSection() throws InterruptedException {
        if (goldNeedTestX1) {
            goldF1 = testConcurrencyWithEarlyStop((int)goldX1);
            System.out.println("[黄金分割] 测试 x1=" + (int)goldX1 + " 指标=" + String.format("%.4f", goldF1));
            goldNeedTestX1 = false;
            if (goldF1 > bestMetric && goldF1 != Double.NEGATIVE_INFINITY) {
                bestMetric = goldF1;
                bestConcurrency = (int)goldX1;
            }
            return;
        }
        if (goldNeedTestX2) {
            goldF2 = testConcurrencyWithEarlyStop((int)goldX2);
            System.out.println("[黄金分割] 测试 x2=" + (int)goldX2 + " 指标=" + String.format("%.4f", goldF2));
            goldNeedTestX2 = false;
            if (goldF2 > bestMetric && goldF2 != Double.NEGATIVE_INFINITY) {
                bestMetric = goldF2;
                bestConcurrency = (int)goldX2;
            }
            return;
        }

        double f1 = goldF1, f2 = goldF2;
        double maxF = Math.max(f1, f2);
        if (maxF > 0 && Math.abs(f1 - f2) / maxF < NOISE_THRESHOLD) {
            System.out.println("[黄金分割] 两点指标接近，复测以降低噪声...");
            double newF1 = testConcurrencyWithEarlyStop((int)goldX1);
            double newF2 = testConcurrencyWithEarlyStop((int)goldX2);
            f1 = (f1 + newF1) / 2;
            f2 = (f2 + newF2) / 2;
            goldF1 = f1;
            goldF2 = f2;
            if (f1 > bestMetric && f1 != Double.NEGATIVE_INFINITY) { bestMetric = f1; bestConcurrency = (int)goldX1; }
            if (f2 > bestMetric && f2 != Double.NEGATIVE_INFINITY) { bestMetric = f2; bestConcurrency = (int)goldX2; }
        }

        if (f1 > f2) {
            goldB = goldX2;
            goldX2 = goldX1;
            goldF2 = goldF1;
            goldX1 = goldB - GOLDEN_RATIO * (goldB - goldA);
            goldNeedTestX1 = true;
            goldNeedTestX2 = false;
        } else {
            goldA = goldX1;
            goldX1 = goldX2;
            goldF1 = goldF2;
            goldX2 = goldA + GOLDEN_RATIO * (goldB - goldA);
            goldNeedTestX1 = false;
            goldNeedTestX2 = true;
        }

        goldIter++;
        System.out.println("[黄金分割] 迭代 " + goldIter + " 区间: [" + (int)goldA + "," + (int)goldB + "]");

        if (goldB - goldA < config.goldenTolerance || goldIter >= GOLDEN_MAX_ITER) {
            int finalBest = (int)((goldA + goldB) / 2);
            double midMetric = testConcurrencyWithEarlyStop(finalBest);
            if (midMetric > bestMetric && midMetric != Double.NEGATIVE_INFINITY) {
                bestMetric = midMetric;
                bestConcurrency = finalBest;
            }
            System.out.println("[黄金分割完成] 最优并发 " + bestConcurrency + " 指标 " + String.format("%.4f", bestMetric));
            phase = Phase.LOCKED;
        }
    }

    private void processLocked(OperatingSystemMXBean osBean) throws InterruptedException {
        semaphore.setMaxPermits(bestConcurrency);
        System.out.println("[锁定] 运行于最优并发 " + bestConcurrency + "，每 " + config.lockedCheckInterval + " 秒验证一次");
        if (cacheSaver != null) {
            cacheSaver.accept(bestConcurrency);
        }

        while (phase == Phase.LOCKED && !Thread.currentThread().isInterrupted()) {
            Thread.sleep(config.lockedCheckInterval * 1000L);
            if (!quickValidate(osBean)) {
                System.out.println("[锁定] 最优并发可能改变，重启搜索");
                phase = Phase.EXPONENTIAL_DOWN;
                expCurrentConcurrency = absoluteMax;
                bestMetric1 = bestMetric2 = -Double.MAX_VALUE;
                bestConc1 = bestConc2 = -1;
                expTestedCount = 0;
                break;
            }
        }
    }


    private double testConcurrencyWithEarlyStop(int concurrency) throws InterruptedException {
        semaphore.setMaxPermits(concurrency);
        Thread.sleep(config.sampleWarmupSeconds * 1000L);

        long startCompleted = totalCompleted.get();
        long startFound = totalFound.get();
        long minTestMillis = config.minTestSeconds * 1000L;
        long maxTestMillis = config.maxTestSeconds * 1000L;
        long checkInterval = 2000;

        long elapsed = 0;
        double lastThroughput = 0;
        boolean earlyStop = false;
        NetworkMonitor nm = NetworkMonitor.getInstance();

        while (elapsed < maxTestMillis) {
            Thread.sleep(Math.min(checkInterval, maxTestMillis - elapsed));
            elapsed += checkInterval;
            long nowCompleted = totalCompleted.get();
            long nowFound = totalFound.get();
            long compDelta = nowCompleted - startCompleted;
            long foundDelta = nowFound - startFound;
            double throughput = (double) compDelta / (elapsed / 1000.0);

            if (elapsed >= minTestMillis) {
                // 如果有发现，检查稳定
                if (foundDelta > 0) {
                    if (lastThroughput > 0) {
                        double change = Math.abs(throughput - lastThroughput) / lastThroughput;
                        if (change < STABILITY_THRESHOLD) {
                            earlyStop = true;
                            break;
                        }
                    }
                    lastThroughput = throughput;
                } else {
                    // 无发现：结合错误率判断是否继续
                    int errRate = nm.getErrorCountInWindow(10); // 最近10秒错误数
                    // 若错误率较高（>20），网络可能不稳，延长测试；否则可早停
                    if (errRate < 20) {
                        earlyStop = true;
                        break;
                    }
                }
            }
        }

        long finalCompleted = totalCompleted.get();
        long finalFound = totalFound.get();
        long completedDelta = finalCompleted - startCompleted;
        long foundDelta = finalFound - startFound;

        double durationSec = (elapsed >= maxTestMillis && !earlyStop) ? config.maxTestSeconds : (elapsed / 1000.0);
        if (durationSec <= 0) durationSec = 1;

        if (foundDelta == 0) {
            return Double.NEGATIVE_INFINITY;
        } else {
            double throughput = completedDelta / durationSec;
            double discoveryRate = foundDelta / durationSec;
            return throughput * discoveryRate;
        }
    }

    private boolean quickValidate(OperatingSystemMXBean osBean) {
        int test1 = bestConcurrency;
        int test2 = (int) (bestConcurrency * 0.9);
        int test3 = (int) (bestConcurrency * 1.1);
        int[] tests = {test1, test2, test3};
        double[] metrics = new double[3];
        try {
            for (int i = 0; i < tests.length; i++) {
                metrics[i] = testConcurrencyWithEarlyStop(tests[i]);
            }
            // 忽略负无穷
            double best = Double.NEGATIVE_INFINITY;
            int bestIdx = 0;
            for (int i = 0; i < metrics.length; i++) {
                if (metrics[i] > best) {
                    best = metrics[i];
                    bestIdx = i;
                }
            }
            if (best == Double.NEGATIVE_INFINITY) {
                // 全部无发现，可能网络问题，返回true保持现状
                return true;
            }
            if (bestIdx == 0) {
                return true;
            } else {
                bestConcurrency = tests[bestIdx];
                bestMetric = best;
                return false;
            }
        } catch (InterruptedException e) {
            return true;
        } finally {
            semaphore.setMaxPermits(bestConcurrency);
        }
    }

    public void stop() {
        stopFlag.set(true);
    }
}