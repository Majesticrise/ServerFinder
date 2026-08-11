package com.scanner;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.ReentrantLock;

public class NetworkMonitor {
    private static final NetworkMonitor INSTANCE = new NetworkMonitor();

    private final ConcurrentLinkedQueue<Long> errorTimestamps = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Long> timeoutTimestamps = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Long> attemptTimestamps = new ConcurrentLinkedQueue<>();

    private volatile int windowSeconds = 5;
    private volatile int highThreshold = 20;
    private volatile int lowThreshold = 5;

    private NetworkMonitor() {}

    public static NetworkMonitor getInstance() {
        return INSTANCE;
    }

    // ---- 资源耗尽异常（保留） ----
    public void recordError() {
        long now = System.currentTimeMillis();
        errorTimestamps.offer(now);
        cleanup(now);
    }

    public int getErrorCountInWindow() {
        long now = System.currentTimeMillis();
        cleanup(now);
        return errorTimestamps.size();
    }

    public boolean isNetworkConstrained() {
        return getErrorCountInWindow() >= highThreshold;
    }

    public boolean isNetworkRecovered() {
        return getErrorCountInWindow() <= lowThreshold;
    }

    private void cleanup(long now) {
        long cutoff = now - windowSeconds * 1000L;
        while (!errorTimestamps.isEmpty() && errorTimestamps.peek() < cutoff) {
            errorTimestamps.poll();
        }
    }

    // ---- 超时记录 ----
    public void recordTimeout() {
        long now = System.currentTimeMillis();
        timeoutTimestamps.offer(now);
        cleanupTimeout(now);
    }

    public int getTimeoutCountInWindow(int windowSec) {
        long now = System.currentTimeMillis();
        long cutoff = now - windowSec * 1000L;
        int count = 0;
        for (Long ts : timeoutTimestamps) {
            if (ts >= cutoff) count++;
        }
        return count;
    }

    private void cleanupTimeout(long now) {
        long cutoff = now - 120 * 1000L;
        while (!timeoutTimestamps.isEmpty() && timeoutTimestamps.peek() < cutoff) {
            timeoutTimestamps.poll();
        }
    }

    // ---- 尝试记录（新增） ----
    public void recordAttempt() {
        long now = System.currentTimeMillis();
        attemptTimestamps.offer(now);
        cleanupAttempt(now);
    }

    public int getAttemptCountInWindow(int windowSec) {
        long now = System.currentTimeMillis();
        long cutoff = now - windowSec * 1000L;
        int count = 0;
        for (Long ts : attemptTimestamps) {
            if (ts >= cutoff) count++;
        }
        return count;
    }

    private void cleanupAttempt(long now) {
        long cutoff = now - 120 * 1000L;
        while (!attemptTimestamps.isEmpty() && attemptTimestamps.peek() < cutoff) {
            attemptTimestamps.poll();
        }
    }

    // ---- 设置/清理 ----
    public void setWindowSeconds(int seconds) {
        this.windowSeconds = seconds;
    }

    public void setHighThreshold(int threshold) {
        this.highThreshold = threshold;
    }

    public void setLowThreshold(int threshold) {
        this.lowThreshold = threshold;
    }

    public void clear() {
        errorTimestamps.clear();
        timeoutTimestamps.clear();
        attemptTimestamps.clear();
    }
}