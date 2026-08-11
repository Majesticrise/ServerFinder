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

        NetworkMonitor.getInstance().recordAttempt();

        String ip = IpGenerator.randomPublicIp();
        boolean submitted = false;
        Proxy proxy = null;
        boolean proxyUsed = false;
        boolean proxyValid = false;

        try {
            if (config.useProxy) {
                proxy = ProxyManager.getInstance().getProxy();
                if (proxy != null) {
                    proxyUsed = true;
                    proxyTaskCounter.incrementAndGet();
                }
            }

            if (proxy != null) {
                double proxyTimeout = config.proxyTimeout > 0 ? config.proxyTimeout : timeout;
                boolean portOpen = false;

                try {
                    portOpen = PortChecker.isPortOpen(ip, port, proxyTimeout, proxy);
                    proxyValid = true;
                } catch (Exception e) {
                    // 代理连接失败，视为无效，丢弃
                    resultConsumer.accept(new ScanResult(ip, false, port));
                    submitted = true;
                    return; // 注意：此时 proxyUsed=true，但会在 finally 中递减计数，不归还（proxyValid=false）
                }

                if (portOpen) {
                    boolean isMc = MinecraftPinger.isMinecraftServer(ip, port, proxyTimeout, proxy);
                    resultConsumer.accept(new ScanResult(ip, isMc, port));
                } else {
                    resultConsumer.accept(new ScanResult(ip, false, port));
                }
                submitted = true;

                if (proxyValid) {
                    ProxyManager.getInstance().returnProxy(proxy);
                }
                // 计数递减交给 finally
                return;
            }

            // 直连
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
            // 代理计数递减（仅当代理被取出）
            if (proxyUsed) {
                proxyTaskCounter.decrementAndGet();
            }
            // 注意：有效代理已在 try 块内归还，无效代理不归还，不会重复归还
        }
    }
}