package com.scanner;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

public class NetworkMonitor {
    private static final NetworkMonitor INSTANCE = new NetworkMonitor();

    // 滑动窗口大小（秒）
    private static final int WINDOW_SECONDS = 120; // 存储最近120秒数据，便于获取不同窗口
    private static final int BUCKET_COUNT = WINDOW_SECONDS; // 每秒一个桶

    // 三个独立计数器：错误、超时、尝试
    private final AtomicLongArray errorBuckets = new AtomicLongArray(BUCKET_COUNT);
    private final AtomicLongArray timeoutBuckets = new AtomicLongArray(BUCKET_COUNT);
    private final AtomicLongArray attemptBuckets = new AtomicLongArray(BUCKET_COUNT);

    private final AtomicInteger currentSecond = new AtomicInteger(0);
    private final AtomicLong totalErrors = new AtomicLong(0);
    private final AtomicLong totalTimeouts = new AtomicLong(0);
    private final AtomicLong totalAttempts = new AtomicLong(0);

    // 定时任务，每秒移动窗口
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "NetworkMonitor-Shift");
        t.setDaemon(true);
        return t;
    });

    private NetworkMonitor() {
        // 启动定时移位，每秒一次
        scheduler.scheduleAtFixedRate(this::shift, 1, 1, TimeUnit.SECONDS);
    }

    public static NetworkMonitor getInstance() {
        return INSTANCE;
    }

    // 记录错误
    public void recordError() {
        int idx = currentSecond.get();
        errorBuckets.incrementAndGet(idx);
        totalErrors.incrementAndGet();
    }

    public void recordTimeout() {
        int idx = currentSecond.get();
        timeoutBuckets.incrementAndGet(idx);
        totalTimeouts.incrementAndGet();
    }

    public void recordAttempt() {
        int idx = currentSecond.get();
        attemptBuckets.incrementAndGet(idx);
        totalAttempts.incrementAndGet();
    }

    // 获取最近 windowSec 秒内的错误数
    public int getErrorCountInWindow(int windowSec) {
        if (windowSec <= 0) return 0;
        int limit = Math.min(windowSec, BUCKET_COUNT);
        long sum = 0;
        int head = currentSecond.get();
        for (int i = 0; i < limit; i++) {
            int idx = (head - i + BUCKET_COUNT) % BUCKET_COUNT;
            sum += errorBuckets.get(idx);
        }
        return (int) sum;
    }

    public int getTimeoutCountInWindow(int windowSec) {
        if (windowSec <= 0) return 0;
        int limit = Math.min(windowSec, BUCKET_COUNT);
        long sum = 0;
        int head = currentSecond.get();
        for (int i = 0; i < limit; i++) {
            int idx = (head - i + BUCKET_COUNT) % BUCKET_COUNT;
            sum += timeoutBuckets.get(idx);
        }
        return (int) sum;
    }

    public int getAttemptCountInWindow(int windowSec) {
        if (windowSec <= 0) return 0;
        int limit = Math.min(windowSec, BUCKET_COUNT);
        long sum = 0;
        int head = currentSecond.get();
        for (int i = 0; i < limit; i++) {
            int idx = (head - i + BUCKET_COUNT) % BUCKET_COUNT;
            sum += attemptBuckets.get(idx);
        }
        return (int) sum;
    }

    // 每秒移动窗口：将当前秒指针向前移动，并清零旧桶（实际上我们不清零，而是覆盖）
    private void shift() {
        int old = currentSecond.getAndUpdate(h -> (h + 1) % BUCKET_COUNT);
        long droppedErrors = errorBuckets.getAndSet(old, 0);
        long droppedTimeouts = timeoutBuckets.getAndSet(old, 0);
        long droppedAttempts = attemptBuckets.getAndSet(old, 0);
        totalErrors.addAndGet(-droppedErrors);
        totalTimeouts.addAndGet(-droppedTimeouts);
        totalAttempts.addAndGet(-droppedAttempts);
    }

    // 原有的其他方法（如 isNetworkConstrained）可基于新方法实现
    public boolean isNetworkConstrained() {
        return getErrorCountInWindow(5) >= 20; // 默认5秒内20个错误
    }

    public boolean isNetworkRecovered() {
        return getErrorCountInWindow(5) <= 5;
    }

    public void setWindowSeconds(int seconds) {
        // 此方法不再适用，因为窗口固定为120秒，但我们可以调整获取窗口的大小
    }

    public void clear() {
        // 重置所有
        for (int i = 0; i < BUCKET_COUNT; i++) {
            errorBuckets.set(i, 0);
            timeoutBuckets.set(i, 0);
            attemptBuckets.set(i, 0);
        }
        totalErrors.set(0);
        totalTimeouts.set(0);
        totalAttempts.set(0);
        currentSecond.set(0);
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }
}