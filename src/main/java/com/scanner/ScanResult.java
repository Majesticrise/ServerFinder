package com.scanner;

/**
 * 扫描结果记录，同时包含一个特殊的“哨兵”对象用于通知结束。
 */
public record ScanResult(String ip, boolean isMinecraft, int port) {

    /**
     * 哨兵对象：代表“扫描结束”信号，ip 为 null，port 为 -1。
     */
    public static final ScanResult POISON_PILL = new ScanResult(null, false, -1);

    /**
     * 判断当前对象是否为哨兵。
     */
    public boolean isPoisonPill() {
        return this == POISON_PILL;
    }
}