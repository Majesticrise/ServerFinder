package com.scanner;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntSupplier;

public class ProxyManager {
    private static ProxyManager instance = null;
    private final ConcurrentLinkedQueue<Proxy> proxyQueue = new ConcurrentLinkedQueue<>();
    private ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ReentrantLock refreshLock = new ReentrantLock();
    private final AtomicBoolean isRefreshing = new AtomicBoolean(false);

    private static final String API_URL = "https://proxy.scdn.io/api/get_proxy.php?protocol=socks5&count=20";
    private static final String GITHUB_PROXY_URL = "https://raw.githubusercontent.com/HankNovic/ProxyClean/refs/heads/main/SOCKS5.txt";

    private final ConcurrentHashMap.KeySetView<String, Boolean> proxySet = ConcurrentHashMap.newKeySet();

    private IntSupplier activeTasksSupplier;
    private volatile boolean shouldCrawl = true;
    private final Object crawlLock = new Object();

    private ExecutorService crawlExecutor;
    private static final int CRAWL_THREADS = 6;

    private static final int MAX_POOL_SIZE = 1500;
    private static final int CRAWL_THRESHOLD_MAX = 1000;

    // 控制爬取并发数，防止 API 频率限制
    private final Semaphore crawlSemaphore = new Semaphore(2);

    private ProxyManager() {}

    public static synchronized ProxyManager getInstance() {
        if (instance == null) {
            instance = new ProxyManager();
        }
        return instance;
    }

    public synchronized void start(IntSupplier activeTasksSupplier) {
        if (running.get()) return;
        this.activeTasksSupplier = activeTasksSupplier;
        running.set(true);
        scheduler = Executors.newSingleThreadScheduledExecutor();
        crawlExecutor = Executors.newFixedThreadPool(CRAWL_THREADS);

        scheduler.schedule(this::refreshProxies, 1, TimeUnit.SECONDS);
        scheduler.schedule(this::fetchGitHubProxies, 1, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::refreshProxies, 180, 180, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::autoRefill, 20, 20, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::submitCrawlTasks, 3, 3, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::fetchGitHubProxies, 10, 10, TimeUnit.MINUTES);
        System.out.println("[代理] 管理器已启动（多线程裂变 + GitHub 代理池，最大容量 " + MAX_POOL_SIZE + "）");
    }

    public synchronized void shutdown() {
        if (!running.get()) return;
        running.set(false);
        if (scheduler != null) scheduler.shutdownNow();
        if (crawlExecutor != null) crawlExecutor.shutdownNow();
        proxyQueue.clear();
        proxySet.clear();
        System.out.println("[代理] 管理器已停止");
    }

    private void autoRefill() {
        if (!running.get()) return;
        if (proxyQueue.isEmpty() && !isRefreshing.get()) {
            System.out.println("[代理] 队列已空，立即补货...");
            refreshProxies();
        }
    }

    private void submitCrawlTasks() {
        if (!running.get() || !shouldCrawl) return;

        int activeTasks = activeTasksSupplier != null ? activeTasksSupplier.getAsInt() : 0;
        if (activeTasks > 0) {
            int threshold = Math.min((int) (activeTasks * 1.5), CRAWL_THRESHOLD_MAX);
            if (proxyQueue.size() >= threshold) {
                synchronized (crawlLock) {
                    if (shouldCrawl) {
                        shouldCrawl = false;
                        System.out.println("[裂变] 代理池充足 (" + proxyQueue.size() + " ≥ " + threshold + ")，暂停爬取");
                    }
                }
                return;
            } else {
                synchronized (crawlLock) {
                    if (!shouldCrawl) {
                        shouldCrawl = true;
                        System.out.println("[裂变] 代理池不足 (" + proxyQueue.size() + " < " + threshold + ")，恢复爬取");
                    }
                }
            }
        }

        for (int i = 0; i < 3; i++) {
            crawlExecutor.submit(this::crawlOne);
        }
    }

    public void returnProxy(Proxy proxy) {
        if (!running.get() || proxy == null) return;
        if (proxyQueue.size() >= MAX_POOL_SIZE) return;
        InetSocketAddress addr = (InetSocketAddress) proxy.address();
        String key = addr.getHostString() + ":" + addr.getPort();
        if (proxySet.add(key)) {
            proxyQueue.offer(proxy);
        }
    }

    private void crawlOne() {
        // 限流：尝试获取信号量，若已被占用则跳过本次
        if (!crawlSemaphore.tryAcquire()) {
            return;
        }

        try {
            // 再次检查运行状态和池容量，避免无效执行
            if (!running.get() || !shouldCrawl || proxyQueue.size() >= MAX_POOL_SIZE) {
                return;
            }

            Proxy proxy = proxyQueue.poll();
            boolean proxyUsed = false;
            boolean proxyValid = false;

            try {
                if (proxy == null) {
                    HttpURLConnection conn = (HttpURLConnection) new URL(API_URL).openConnection();
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    conn.setRequestMethod("GET");
                    int code = conn.getResponseCode();
                    if (code == 200) {
                        addProxiesFromResponse(conn, "裂变(直连)");
                    }
                    return;
                }

                proxyUsed = true;
                HttpURLConnection conn = (HttpURLConnection) new URL(API_URL).openConnection(proxy);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                conn.setRequestProperty("Accept", "*/*");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestMethod("GET");

                int code = conn.getResponseCode();
                if (code == 200) {
                    proxyValid = true;
                    addProxiesFromResponse(conn, "裂变");
                }
            } catch (Exception e) {
                // 代理失效，丢弃
            } finally {
                if (proxyUsed && proxyValid) {
                    returnProxy(proxy);
                }
            }
        } finally {
            // 释放信号量，允许下一个爬取任务执行
            crawlSemaphore.release();
        }
    }

    private void addProxiesFromResponse(HttpURLConnection conn, String source) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            JsonObject json = JsonParser.parseString(sb.toString()).getAsJsonObject();
            if (json.get("code").getAsInt() != 200) return;

            JsonObject data = json.getAsJsonObject("data");
            var proxiesArray = data.getAsJsonArray("proxies");
            if (proxiesArray == null || proxiesArray.isEmpty()) return;

            int added = 0;
            for (var elem : proxiesArray) {
                if (proxyQueue.size() >= MAX_POOL_SIZE) break;
                String addr = elem.getAsString();
                String[] parts = addr.split(":");
                if (parts.length == 2) {
                    try {
                        String host = parts[0];
                        int port = Integer.parseInt(parts[1]);
                        String key = host + ":" + port;
                        if (proxySet.add(key)) {
                            proxyQueue.offer(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(host, port)));
                            added++;
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
            if (added > 0) {
                System.out.println("[" + source + "] 新增 " + added + " 个代理，当前池大小: " + proxyQueue.size());
            }
        } catch (Exception ignored) {}
    }

    private void fetchGitHubProxies() {
        if (!running.get()) return;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(GITHUB_PROXY_URL).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("GET");

            int code = conn.getResponseCode();
            if (code != 200) {
                System.err.println("[GitHub代理] 拉取失败，HTTP " + code);
                return;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            String line;
            int added = 0;
            while ((line = reader.readLine()) != null && proxyQueue.size() < MAX_POOL_SIZE) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String addr = line.startsWith("socks5://") ? line.substring(9) : line;
                String[] parts = addr.split(":");
                if (parts.length == 2) {
                    try {
                        String host = parts[0];
                        int port = Integer.parseInt(parts[1]);
                        String key = host + ":" + port;
                        if (proxySet.add(key)) {
                            proxyQueue.offer(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(host, port)));
                            added++;
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
            reader.close();
            if (added > 0) {
                System.out.println("[GitHub代理] 新增 " + added + " 个代理，当前池大小: " + proxyQueue.size());
            }
        } catch (Exception e) {
            System.err.println("[GitHub代理] 拉取异常: " + e.getMessage());
        }
    }

    private void refreshProxies() {
        if (!running.get()) return;
        if (!refreshLock.tryLock()) return;
        if (!isRefreshing.compareAndSet(false, true)) return;

        try {
            int maxRetries = 3;
            int retryDelay = 2000;

            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(API_URL).openConnection();
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                    conn.setRequestProperty("Accept", "*/*");
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    conn.setRequestMethod("GET");

                    int code = conn.getResponseCode();
                    if (code == 456) {
                        System.err.println("[代理] 触发频率限制 (HTTP 456)，30分钟后重试");
                        break;
                    }
                    if (code != 200) {
                        System.err.println("[代理] API请求失败，HTTP " + code);
                        break;
                    }

                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();

                    JsonObject json = JsonParser.parseString(sb.toString()).getAsJsonObject();
                    if (json.get("code").getAsInt() != 200) {
                        System.err.println("[代理] API返回错误: " + json.get("message").getAsString());
                        break;
                    }

                    JsonObject data = json.getAsJsonObject("data");
                    var proxiesArray = data.getAsJsonArray("proxies");
                    if (proxiesArray == null || proxiesArray.isEmpty()) {
                        System.err.println("[代理] 获取到空列表");
                        break;
                    }

                    int added = 0;
                    for (var elem : proxiesArray) {
                        if (proxyQueue.size() >= MAX_POOL_SIZE) break;
                        String addr = elem.getAsString();
                        String[] parts = addr.split(":");
                        if (parts.length == 2) {
                            try {
                                String host = parts[0];
                                int port = Integer.parseInt(parts[1]);
                                String key = host + ":" + port;
                                if (proxySet.add(key)) {
                                    proxyQueue.offer(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(host, port)));
                                    added++;
                                }
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                    if (added > 0) {
                        System.out.println("[补货] 追加 " + added + " 个代理，当前池大小: " + proxyQueue.size());
                    } else {
                        System.err.println("[补货] 获取到 " + proxiesArray.size() + " 个代理，但均已存在或池已满");
                    }
                    break;

                } catch (Exception e) {
                    if (attempt < maxRetries) {
                        System.err.println("[代理] 刷新失败 (尝试 " + attempt + "/" + maxRetries + ")，2秒后重试...");
                        Thread.sleep(retryDelay);
                    } else {
                        System.err.println("[代理] 刷新失败: " + e.getMessage());
                    }
                }
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            isRefreshing.set(false);
            refreshLock.unlock();
        }
    }

    public Proxy getProxy() {
        if (!running.get()) return null;
        Proxy proxy = proxyQueue.poll();
        if (proxy != null) {
            InetSocketAddress addr = (InetSocketAddress) proxy.address();
            String key = addr.getHostString() + ":" + addr.getPort();
            proxySet.remove(key);
        }
        return proxy;
    }
}