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
    // 三个主流版本：1.20.1(763), 1.12.2(340), 1.8.9(47)
    private static final int[] PROTOCOL_VERSIONS = {763, 340, 47};

    // ---------- 直连（兼容原接口） ----------
    public static boolean isMinecraftServer(String ip, int port, double timeoutSec) {
        return isMinecraftServer(ip, port, timeoutSec, null);
    }

    // ---------- 支持代理 ----------
    public static boolean isMinecraftServer(String ip, int port, double timeoutSec, Proxy proxy) {
        // 按版本优先级串行尝试（避免并行造成的性能浪费）
        for (int ver : PROTOCOL_VERSIONS) {
            try {
                if (pingModern(ip, port, timeoutSec, ver, proxy)) {
                    return true;
                }
            } catch (Exception ignored) {
                // 单个版本失败，继续下一个
            }
        }
        return false;
    }

    // ---------- 核心探测（已修复 JSON 读取逻辑） ----------
    private static boolean pingModern(String ip, int port, double timeoutSec, int protocolVersion, Proxy proxy) {
        try (Socket socket = proxy == null ? new Socket() : new Socket(proxy)) {
            int timeoutMillis = (int) (timeoutSec * 1000);
            socket.connect(new InetSocketAddress(ip, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // --- Handshake ---
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream handshakeOut = new DataOutputStream(baos);
            writeVarInt(handshakeOut, 0);                 // 包 ID: Handshake
            writeVarInt(handshakeOut, protocolVersion);   // 协议版本
            writeString(handshakeOut, ip);                // 地址
            handshakeOut.writeShort(port);                // 端口
            writeVarInt(handshakeOut, 1);                 // 状态: 1 (Status)
            byte[] handshakeData = baos.toByteArray();
            writeVarInt(out, handshakeData.length);
            out.write(handshakeData);
            out.flush();

            // --- Status Request ---
            baos.reset();
            DataOutputStream requestOut = new DataOutputStream(baos);
            writeVarInt(requestOut, 0);                   // 包 ID: Request
            byte[] requestData = baos.toByteArray();
            writeVarInt(out, requestData.length);
            out.write(requestData);
            out.flush();

            // --- 读取响应（修复点） ---
            // 1. 读取整个数据包长度（VarInt）- 用于校验，但不强制使用
            int packetLength = readVarInt(in);
            if (packetLength < 2) return false;          // 至少包含 packetId 和 JSON 长度

            // 2. 读取 packet ID（必须为 0）
            int packetId = readVarInt(in);
            if (packetId != 0) return false;

            // 3. 读取 JSON 字符串长度（VarInt）
            int jsonLength = readVarInt(in);
            if (jsonLength < 2 || jsonLength >131072) return false;

            // 4. 按长度读取 JSON 字符串
            byte[] jsonBytes = new byte[jsonLength];
            in.readFully(jsonBytes);
            String json = new String(jsonBytes, StandardCharsets.UTF_8);

            // 5. 快速预检（避免无效 JSON 解析）
            if (json.length() < 20 ||
                    !json.contains("\"version\"") ||
                    !json.contains("\"players\"")) {
                return false;
            }

            // 6. 精确解析并校验类型
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (!obj.has("version")) return false;
            return true;

        } catch (Exception e) {
            return false;
        }
    }

    // ---------- VarInt / String 工具（保持不变） ----------
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
            if (shift > 35) throw new IOException("VarInt too long");
        } while ((b & 0x80) != 0);
        return result;
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }
}