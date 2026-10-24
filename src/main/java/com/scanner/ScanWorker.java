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
        NetworkMonitor.getInstance().recordAttempt();
        int ip;
        try {
            ip = IpGenerator.randomPublicIpInt();
        } catch (Exception e) {
            // 几乎不可能，防御性
            ip = 0;
        }
        ScanResult result;
        try {
            if (config.useProxy) {
                result = scanViaProxy(ip);
            } else {
                result = scanDirect(ip);
            }
        } catch (Throwable t) {
            result = new ScanResult(ip, false, port);
        }
        resultConsumer.accept(result);
    }

    private ScanResult scanDirect(int ip) {
        try {
            if (!PortChecker.isPortOpen(ip, port, timeout, null)) {
                return new ScanResult(ip, false, port);
            }
            boolean mc = MinecraftPinger.isMinecraftServer(ip, port, timeout, null);
            return new ScanResult(ip, mc, port);
        } catch (Exception e) {
            return new ScanResult(ip, false, port);
        }
    }

    private ScanResult scanViaProxy(int ip) {
        Proxy proxy = ProxyManager.getInstance().getProxy();
        if (proxy == null) {
            return scanDirect(ip);   // 没代理就直连
        }
        proxyTaskCounter.incrementAndGet();
        try {
            double proxyTimeout = config.proxyTimeout > 0 ? config.proxyTimeout : timeout;
            boolean open;
            try {
                open = PortChecker.isPortOpen(ip, port, proxyTimeout, proxy);
            } catch (Exception e) {
                // 走到这里说明端口检查抛了意外异常（PortChecker 内部已吞掉 IOException）。
                // 区分不了是代理失效还是目标无响应，先探活代理，活着就归还，避免误杀好代理。
                if (ProxyManager.isProxyAlive(proxy, 2000)) {
                    ProxyManager.getInstance().returnProxy(proxy);
                }
                return new ScanResult(ip, false, port);
            }
            boolean mc = open && MinecraftPinger.isMinecraftServer(ip, port, proxyTimeout, proxy);
            // 成功走完代理路径 → 归还
            ProxyManager.getInstance().returnProxy(proxy);
            return new ScanResult(ip, mc, port);
        } finally {
            proxyTaskCounter.decrementAndGet();
        }
    }
}