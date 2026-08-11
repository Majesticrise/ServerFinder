package com.scanner;

import java.net.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class ScanWorker implements Runnable {
    private final Config config;
    private final int port;
    private final double timeout;
    private final Consumer<ScanResult> resultConsumer;
    private final AtomicBoolean stopFlag;
    private final AtomicInteger proxyTaskCounter;

    public ScanWorker(Config config, int port, double timeout,
                      Consumer<ScanResult> resultConsumer, AtomicBoolean stopFlag,
                      AtomicInteger proxyTaskCounter) {
        this.config = config;
        this.port = port;
        this.timeout = timeout;
        this.resultConsumer = resultConsumer;
        this.stopFlag = stopFlag;
        this.proxyTaskCounter = proxyTaskCounter;
    }

    @Override
    public void run() {
        if (stopFlag.get()) return;

        // 记录本次连接尝试
        NetworkMonitor.getInstance().recordAttempt();

        String ip = IpGenerator.randomPublicIp();
        boolean submitted = false;
        Proxy proxy = null;
        try {
            if (config.useProxy) {
                proxy = ProxyManager.getInstance().getProxy();
            }

            if (proxy != null) {
                // ---------- 代理模式 ----------
                proxyTaskCounter.incrementAndGet();
                double proxyTimeout = config.proxyTimeout > 0 ? config.proxyTimeout : timeout;
                boolean portOpen = false;
                boolean proxyValid = false;

                try {
                    portOpen = PortChecker.isPortOpen(ip, port, proxyTimeout, proxy);
                    proxyValid = true;   // 连接成功，标记代理有效
                } catch (Exception e) {
                    // 代理连接失败，代理无效，直接丢弃
                    resultConsumer.accept(new ScanResult(ip, false, port));
                    submitted = true;
                    proxyTaskCounter.decrementAndGet();
                    return;   // 不归还无效代理
                }

                if (portOpen) {
                    boolean isMc = MinecraftPinger.isMinecraftServer(ip, port, proxyTimeout, proxy);
                    resultConsumer.accept(new ScanResult(ip, isMc, port));
                } else {
                    resultConsumer.accept(new ScanResult(ip, false, port));
                }
                submitted = true;

                // 只有验证有效的代理才归还池中
                if (proxyValid) {
                    ProxyManager.getInstance().returnProxy(proxy);
                }
                proxyTaskCounter.decrementAndGet();
                return;
            }

            // ---------- 直连模式 ----------
            boolean portOpen = PortChecker.isPortOpen(ip, port, timeout, null);
            if (portOpen) {
                boolean isMc = MinecraftPinger.isMinecraftServer(ip, port, timeout, null);
                resultConsumer.accept(new ScanResult(ip, isMc, port));
            } else {
                resultConsumer.accept(new ScanResult(ip, false, port));
            }
            submitted = true;

        } catch (Exception e) {
            if (!submitted) {
                resultConsumer.accept(new ScanResult(ip, false, port));
                submitted = true;
            }
        } finally {
            if (!submitted) {
                resultConsumer.accept(new ScanResult(ip, false, port));
            }
        }
    }
}