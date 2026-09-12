package com.scanner;

/**
 * 扫描结果记录，同时包含一个特殊的“哨兵”对象用于通知结束。
 */
public record ScanResult(int ip, boolean isMinecraft, int port) {

    /**
     * 哨兵对象：代表“扫描结束”信号，ip 为 null，port 为 -1。
     */
    public static final ScanResult POISON_PILL = new ScanResult(0, false, -1);

    /**
     * 判断当前对象是否为哨兵。
     */
    public boolean isPoisonPill() {
        return this == POISON_PILL;
    }
    /** 只在输出场景调用；热路径不要碰。 */
    public String ipString() {
        return IpGenerator.ipToString(ip);
    }
}
