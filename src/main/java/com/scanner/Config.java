package com.scanner;

public class Config {
    // ---- 基础扫描参数 ----
    public int total = 102400;
    public int concurrency = 10240;
    public double timeout = 2.5;
    public int port = 25565;
    public boolean useSyn = false;
    public String outputFile = "found_servers.txt";
    public int displayMode = 2;
    public boolean autoClear = true;
    public int maxLines = 128;
    public boolean adaptive = true;
    public boolean useProxy = false;
    public double proxyTimeout = 1.5;

    // ---- 混合搜索自适应参数 ----
    // 指数下降粗扫
    public double expFactor = 0.7;               // 每次并发衰减比例
    public int expMaxPoints = 12;                // 粗扫最多测试点数

    // 黄金分割细扫
    public int goldenTolerance = 50;             // 区间宽度小于此值停止

    // 早停与采样时长
    public int minTestSeconds = 30;              // 最少测试时间（秒）
    public int maxTestSeconds = 120;             // 最多测试时间（秒，有发现时测满）
    public int sampleWarmupSeconds = 15;         // 切换并发后的稳定预热时间（秒）

    // 锁定与验证
    public int lockedCheckInterval = 600;        // 锁定后验证间隔（秒）
    public int cachedBestConcurrency = 0;        // 缓存最优并发，0 表示无缓存

    // ---- 内存安全 ----
    public double memoryEmergencyThreshold = 0.95;
    public double memoryDowngradeFactor = 0.8;
    public int minConcurrency = 100;                // 绝对并发下限
    public int maxConcurrency = 10240;              // 绝对并发上限（根据机器性能调整）

    // ---- 文件定期去重配置 ----
    public boolean enableFileDedup = true;
    public int fileDedupIntervalSeconds = 600; // 10分钟



}