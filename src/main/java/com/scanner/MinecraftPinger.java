package com.scanner;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class MinecraftPinger {

    /**
     * Server List Ping 的 protocolVersion 在 status 查询里只被原样回显，
     * 服务器不做版本校验，任何现代 MC 服务器都会返回相同 JSON。
     */
    private static final int DEFAULT_PROTOCOL = 763;   // 1.20.1

    /** 单个包长度上限：packetId(1) + jsonLenVarInt(3) + json(131072) + 余量 */
    private static final int MAX_PACKET_LENGTH = 131_080;

    /** JSON 长度上限 */
    private static final int MAX_JSON_LENGTH = 131_072;

    private MinecraftPinger() {}

    // ================================================================
    // 对外 API
    // ================================================================

    public static boolean isMinecraftServer(int ip, int port, double timeoutSec) {
        return isMinecraftServer(IpGenerator.ipToString(ip), port, timeoutSec, null);
    }

    public static boolean isMinecraftServer(int ip, int port, double timeoutSec, Proxy proxy) {
        return isMinecraftServer(IpGenerator.ipToString(ip), port, timeoutSec, proxy);
    }

    public static boolean isMinecraftServer(String ip, int port, double timeoutSec) {
        return isMinecraftServer(ip, port, timeoutSec, null);
    }

    public static boolean isMinecraftServer(String ip, int port, double timeoutSec, Proxy proxy) {
        return pingModern(ip, port, timeoutSec, DEFAULT_PROTOCOL, proxy);
    }

    // ================================================================
    // 核心探测
    // ================================================================

    private static boolean pingModern(String ip, int port, double timeoutSec,
                                      int protocolVersion, Proxy proxy) {
        // ---- 1. timeout 校验：防止 (int) 转换后变成 0 导致无限阻塞 ----
        if (timeoutSec <= 0) return false;
        long ms = Math.round(timeoutSec * 1000);
        if (ms < 1) ms = 1;
        if (ms > Integer.MAX_VALUE) ms = Integer.MAX_VALUE;
        int timeoutMillis = (int) ms;

        try (Socket socket = proxy == null ? new Socket() : new Socket(proxy)) {
            socket.connect(new InetSocketAddress(ip, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // ---- Handshake ----
            ByteArrayOutputStream baos = new ByteArrayOutputStream(32);
            DataOutputStream handshakeOut = new DataOutputStream(baos);
            writeVarInt(handshakeOut, 0);                 // Packet ID: Handshake
            writeVarInt(handshakeOut, protocolVersion);   // Protocol Version
            writeString(handshakeOut, ip);                // Server Address
            handshakeOut.writeShort(port);                // Server Port
            writeVarInt(handshakeOut, 1);                 // Next State: 1 (Status)
            byte[] handshakeData = baos.toByteArray();
            writeVarInt(out, handshakeData.length);
            out.write(handshakeData);
            out.flush();

            // ---- Status Request ----
            baos.reset();
            DataOutputStream requestOut = new DataOutputStream(baos);
            writeVarInt(requestOut, 0);                   // Packet ID: Request
            byte[] requestData = baos.toByteArray();
            writeVarInt(out, requestData.length);
            out.write(requestData);
            out.flush();

            // ---- 读响应 ----
            // 1. 包总长
            int packetLength = readVarInt(in);
            if (packetLength < 2 || packetLength > MAX_PACKET_LENGTH) return false;

            // 2. Packet ID（必须为 0）
            int packetId = readVarInt(in);
            if (packetId != 0) return false;

            // 3. JSON 长度
            int jsonLength = readVarInt(in);
            if (jsonLength < 2 || jsonLength > MAX_JSON_LENGTH) return false;

            // 4. JSON 内容
            byte[] jsonBytes = new byte[jsonLength];
            in.readFully(jsonBytes);
            String json = new String(jsonBytes, StandardCharsets.UTF_8);

            // 5. 快速预检
            if (json.length() < 20
                    || !json.contains("\"version\"")
                    || !json.contains("\"players\"")) {
                return false;
            }

            // 6. 精确解析
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            return obj.has("version");

        } catch (Exception e) {
            return false;
        }
    }

    // ================================================================
    // VarInt / String 工具
    // ================================================================

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        do {
            int temp = value & 0x7F;
            value >>>= 7;
            if (value != 0) temp |= 0x80;
            out.writeByte(temp);
        } while (value != 0);
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int result = 0;
        int shift = 0;
        byte b;
        do {
            b = in.readByte();
            result |= (b & 0x7F) << shift;
            shift += 7;
            // 合法 VarInt 最多 5 字节。读满 5 字节后若还有 continuation bit，立即报错。
            if (shift >= 35 && (b & 0x80) != 0) {
                throw new IOException("VarInt too long");
            }
        } while ((b & 0x80) != 0);
        return result;
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }
}