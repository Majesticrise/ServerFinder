package com.scanner;


import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 可动态调整许可数的信号量，用于控制并发任务数。
 */
public class AdaptiveSemaphore {
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition condition = lock.newCondition();
    private int maxPermits;
    private int usedPermits = 0;

    public AdaptiveSemaphore(int initialMaxPermits) {
        this.maxPermits = initialMaxPermits;
    }

    /**
     * 调整最大许可数（只能调整，不能减少已占用的许可）。
     * 如果新值小于当前已使用数，则只记录新值，不会强制中断任务。
     */
    public void setMaxPermits(int newMax) {
        lock.lock();
        try {
            if (newMax < 1) newMax = 1;
            boolean shouldSignal = newMax > usedPermits; // 只有新上限大于已使用时才唤醒
            this.maxPermits = newMax;
            if (shouldSignal) {
                condition.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }
    public int getMaxPermits() {
        lock.lock();
        try {
            return maxPermits;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 阻塞获取许可，直到有可用许可或线程中断。
     */
    public void acquire() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (usedPermits >= maxPermits) {
                condition.await();
            }
            usedPermits++;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 释放一个许可。
     */
    public void release() {
        lock.lock();
        try {
            if (usedPermits > 0) {
                usedPermits--;
                condition.signal();
            }
        } finally {
            lock.unlock();
        }
    }

}