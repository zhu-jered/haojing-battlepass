package com.haojing.battlepass.common.net;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用途：同步快照的序列化与分片重组（需求文档 §3："配置下发需支持增量同步，
 * 并对超大 payload 分片；非法/超限包直接丢弃并记日志"）。
 *
 * <p>为什么把分片逻辑放在 :common 而不是各端一份：分片边界、修订号比较、
 * 超限包丢弃这三件事任何一处两端不一致，表现都是"界面偶尔空白"或"数据错位"，
 * 而且极难复现。放在共享源码里，两端用的是同一份实现与同一份单元测试。
 *
 * <p>为什么用 JSON 而不是手写二进制布局：快照里全是嵌套的列表与可选字段
 * （任务组、商店商品、收藏册），手写布局在字段增删时极易错位；
 * 而 JSON 加上"通道 + 修订号"的外壳之后，多余/缺失字段会被 Gson 宽容处理，
 * 老客户端不会因为服务端多了一个字段而崩溃。
 */
public final class SyncFragments {

    /** 单个分片的最大字节数。 */
    public static final int MAX_PART_BYTES = 16 * 1024;

    /**
     * 单个快照允许的最大分片数。
     *
     * <p>这是一个防滥用上限：畸形或恶意客户端可以伪造一个 {@code partCount = 100000}
     * 的包，让服务端/客户端为它预留巨大的数组。超过上限的包直接丢弃（§14）。
     */
    public static final int MAX_PARTS = 64;

    /**
     * 单个快照允许的最大字节数（{@code MAX_PART_BYTES × MAX_PARTS}）。
     */
    public static final int MAX_TOTAL_BYTES = MAX_PART_BYTES * MAX_PARTS;

    /** 关闭 HTML 转义：快照里全是中文名，转义后既不可读也浪费带宽。 */
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private SyncFragments() {
    }

    /**
     * 把一段字节切成若干分片。
     *
     * @param data         原始数据（不能为空）
     * @param maxPartBytes 单片上限
     * @return 分片列表；数据为空时返回含一个空片的列表（保证 {@code partCount ≥ 1}）
     */
    public static List<byte[]> split(byte[] data, int maxPartBytes) {
        int limit = Math.max(1, maxPartBytes);
        List<byte[]> parts = new ArrayList<>();

        if (data == null || data.length == 0) {
            parts.add(new byte[0]);
            return parts;
        }

        for (int offset = 0; offset < data.length; offset += limit) {
            int length = Math.min(limit, data.length - offset);
            byte[] part = new byte[length];
            System.arraycopy(data, offset, part, 0, length);
            parts.add(part);
        }

        return parts;
    }

    /** @param value 任意 DTO @return JSON 文本。 */
    public static String toJson(Object value) {
        return GSON.toJson(value);
    }

    /**
     * 解析 JSON。失败时返回 null 而不是抛异常 ——
     * 网络来的数据永远不能让它把调用方打断（§14）。
     *
     * @param json JSON 文本
     * @param type 目标类型
     * @param <T>  类型
     * @return 解析结果；失败时为 null
     */
    public static <T> T fromJson(String json, Class<T> type) {
        if (json == null || json.isEmpty()) {
            return null;
        }

        try {
            return GSON.fromJson(json, type);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** @param data 原始数据 @return UTF-8 文本。 */
    public static String utf8(byte[] data) {
        return data == null ? "" : new String(data, StandardCharsets.UTF_8);
    }

    /** @param text 文本 @return UTF-8 字节。 */
    public static byte[] bytes(String text) {
        return text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 用途：接收端的分片重组器（客户端持有它；服务端不需要）。
     *
     * <p>为什么必须按"通道 + 修订号"隔离：同一个通道的新快照可能在旧快照还没发完时就开始发了
     * （例如玩家连着做了两次操作）。若不分修订号，两批分片会互相拼接成一段乱 JSON。
     * 这里的策略是"只认更高修订号，低修订号直接丢弃"。
     *
     * <p>本类**有状态且需要线程安全**：Fabric 的网络回调在客户端主线程执行，
     * 但测试与后台调用仍可能出现，因此所有方法都加锁。
     */
    public static final class Assembler {

        private final Map<String, Pending> pending = new HashMap<>();
        private final Map<String, String> complete = new LinkedHashMap<>();

        /**
         * 接收一个分片。
         *
         * @param channel   通道名
         * @param revision  修订号
         * @param partIndex 分片序号
         * @param partCount 分片总数
         * @param data      分片数据
         * @return 若本次收齐了整份快照，返回其 JSON 文本；否则返回 null
         */
        public synchronized String accept(String channel, int revision, int partIndex, int partCount, byte[] data) {
            if (channel == null || channel.isEmpty()
                    || partCount <= 0 || partCount > MAX_PARTS
                    || partIndex < 0 || partIndex >= partCount
                    || data == null) {
                // §14：非法/超限分片直接丢弃。
                return null;
            }

            Pending current = pending.get(channel);

            if (current == null || revision > current.revision) {
                current = new Pending(revision, partCount);
                pending.put(channel, current);
            } else if (revision < current.revision) {
                // 旧修订的分片迟到：丢弃（否则会把两批快照拼在一起）。
                return null;
            } else if (current.partCount != partCount) {
                // 同一修订却给了不同的分片总数：包被篡改或双方理解不一致，丢弃。
                return null;
            }

            if (current.parts[partIndex] == null) {
                current.parts[partIndex] = data;
                current.received++;
            }

            if (current.received < current.partCount) {
                return null;
            }

            int total = 0;

            for (byte[] part : current.parts) {
                total += part == null ? 0 : part.length;
            }

            byte[] joined = new byte[total];
            int offset = 0;

            for (byte[] part : current.parts) {
                if (part != null) {
                    System.arraycopy(part, 0, joined, offset, part.length);
                    offset += part.length;
                }
            }

            pending.remove(channel);

            String json = utf8(joined);
            complete.put(channel, json);
            return json;
        }

        /** @param channel 通道 @return 该通道最近一次收到的完整快照 JSON；没有则为 null。 */
        public synchronized String current(String channel) {
            return channel == null ? null : complete.get(channel);
        }

        /** @return 全部已完成通道的快照副本。 */
        public synchronized Map<String, String> snapshot() {
            return new LinkedHashMap<>(complete);
        }

        /** @return 仍在等分片的通道数。 */
        public synchronized int pendingChannels() {
            return pending.size();
        }

        /** 清空全部状态（断线时调用）。 */
        public synchronized void clear() {
            pending.clear();
            complete.clear();
        }

        /** 单个通道的重组中间状态。 */
        private static final class Pending {

            private final int revision;
            private final int partCount;
            private final byte[][] parts;
            private int received;

            private Pending(int revision, int partCount) {
                this.revision = revision;
                this.partCount = partCount;
                this.parts = new byte[partCount][];
            }
        }
    }
}
