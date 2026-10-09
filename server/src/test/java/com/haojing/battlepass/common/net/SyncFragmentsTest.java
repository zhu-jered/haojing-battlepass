package com.haojing.battlepass.common.net;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用途：验证同步的分片、重组与 JSON 编解码（需求文档 §3："对超大 payload 分片；
 * 非法/超限包直接丢弃并记日志"）。
 *
 * <p>为什么这部分必须单测：分片重组的错误表现是"界面偶尔空白""数据错位"，
 * 而且往往只在网络抖动或大快照时出现 —— 靠手动测试几乎不可能复现。
 */
class SyncFragmentsTest {

    @Test
    void 分片数量与边界正确() {
        byte[] data = new byte[10];

        List<byte[]> parts = SyncFragments.split(data, 4);

        assertEquals(3, parts.size());
        assertEquals(4, parts.get(0).length);
        assertEquals(4, parts.get(1).length);
        assertEquals(2, parts.get(2).length);
    }

    @Test
    void 空数据也会产生一个分片() {
        List<byte[]> parts = SyncFragments.split(new byte[0], 16);

        assertEquals(1, parts.size(), "必须保证 partCount ≥ 1，否则接收方永远收不齐");
        assertEquals(0, parts.get(0).length);
    }

    @Test
    void 单片上限为非法值时按最小片处理() {
        List<byte[]> parts = SyncFragments.split(new byte[3], 0);

        assertEquals(3, parts.size());
    }

    @Test
    void 文本与字节互转() {
        String text = "镐京方块协会 · 战令";

        assertArrayEquals(text.getBytes(StandardCharsets.UTF_8), SyncFragments.bytes(text));
        assertEquals(text, SyncFragments.utf8(SyncFragments.bytes(text)));
        assertEquals("", SyncFragments.utf8(null));
        assertEquals(0, SyncFragments.bytes(null).length);
    }

    @Test
    void JSON编解码与容错() {
        ModSnapshots.Player player = new ModSnapshots.Player();
        player.level = 7;
        player.starCoin = 120;
        player.themeName = "镐京初章";

        String json = SyncFragments.toJson(player);

        assertTrue(json.contains("镐京初章"), "关闭 HTML 转义后中文应保持可读");

        ModSnapshots.Player parsed = SyncFragments.fromJson(json, ModSnapshots.Player.class);

        assertNotNull(parsed);
        assertEquals(7, parsed.level);
        assertEquals(120, parsed.starCoin);

        assertNull(SyncFragments.fromJson("{ 这不是 JSON", ModSnapshots.Player.class), "坏 JSON 必须返回 null 而不是抛异常");
        assertNull(SyncFragments.fromJson("", ModSnapshots.Player.class));
        assertNull(SyncFragments.fromJson(null, ModSnapshots.Player.class));
    }

    @Test
    void 多分片按序重组() {
        SyncFragments.Assembler assembler = new SyncFragments.Assembler();
        byte[] payload = "这是一份需要分片的快照内容，用来验证重组结果与原文一致。".getBytes(StandardCharsets.UTF_8);
        List<byte[]> parts = SyncFragments.split(payload, 8);

        for (int index = 0; index < parts.size(); index++) {
            String completed = assembler.accept("tasks", 1, index, parts.size(), parts.get(index));

            if (index < parts.size() - 1) {
                assertNull(completed, "未收齐时不应返回结果");
            } else {
                assertEquals(SyncFragments.utf8(payload), completed);
            }
        }

        assertEquals(0, assembler.pendingChannels());
        assertEquals(SyncFragments.utf8(payload), assembler.current("tasks"));
    }

    @Test
    void 乱序到达也能正确重组() {
        SyncFragments.Assembler assembler = new SyncFragments.Assembler();
        byte[] payload = "abcdefghij".getBytes(StandardCharsets.UTF_8);
        List<byte[]> parts = SyncFragments.split(payload, 3);

        // "abcdefghij" 按 3 字节切分得到 4 片：abc / def / ghi / j
        assertEquals(4, parts.size());

        int[] order = {2, 0, 3, 1};
        String completed = null;

        for (int index : order) {
            completed = assembler.accept("shop", 2, index, parts.size(), parts.get(index));
        }

        assertEquals("abcdefghij", completed, "分片乱序到达也应能正确重组");
    }

    @Test
    void 旧修订的分片被丢弃() {
        SyncFragments.Assembler assembler = new SyncFragments.Assembler();

        // 先收到修订 5 的第一片
        assertNull(assembler.accept("player", 5, 0, 2, "AAA".getBytes(StandardCharsets.UTF_8)));

        // 修订 4 的迟到分片必须被丢弃，否则会把两批数据拼在一起
        assertNull(assembler.accept("player", 4, 1, 2, "BBB".getBytes(StandardCharsets.UTF_8)));

        assertEquals("AAA" + "CCC", assembler.accept("player", 5, 1, 2, "CCC".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void 同修订不同分片总数被拒绝() {
        SyncFragments.Assembler assembler = new SyncFragments.Assembler();

        assertNull(assembler.accept("tasks", 1, 0, 3, new byte[] {1}));
        assertNull(assembler.accept("tasks", 1, 1, 2, new byte[] {2}), "分片总数不一致：包不可信");
    }

    @Test
    void 非法与超限分片直接丢弃() {
        SyncFragments.Assembler assembler = new SyncFragments.Assembler();

        assertNull(assembler.accept(null, 1, 0, 1, new byte[] {1}));
        assertNull(assembler.accept("", 1, 0, 1, new byte[] {1}));
        assertNull(assembler.accept("tasks", 1, 0, 0, new byte[] {1}), "partCount 至少为 1");
        assertNull(assembler.accept("tasks", 1, 0, -3, new byte[] {1}));
        assertNull(assembler.accept("tasks", 1, 5, 2, new byte[] {1}), "序号越界");
        assertNull(assembler.accept("tasks", 1, -1, 2, new byte[] {1}));
        assertNull(assembler.accept("tasks", 1, 0, 1, null), "空数据不可信");
        assertNull(assembler.accept("tasks", 1, 0, SyncFragments.MAX_PARTS + 1, new byte[] {1}), "分片数超限");
        assertEquals(0, assembler.pendingChannels(), "被丢弃的分片不应留下中间状态");
    }

    @Test
    void 重复分片不会重复计数() {
        SyncFragments.Assembler assembler = new SyncFragments.Assembler();

        assertNull(assembler.accept("tasks", 1, 0, 2, "AA".getBytes(StandardCharsets.UTF_8)));
        assertNull(assembler.accept("tasks", 1, 0, 2, "AA".getBytes(StandardCharsets.UTF_8)), "同一片重复到达不应算两片");
        assertEquals("AABB", assembler.accept("tasks", 1, 1, 2, "BB".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void 清空与快照() {
        SyncFragments.Assembler assembler = new SyncFragments.Assembler();

        assembler.accept("tasks", 1, 0, 1, "T".getBytes(StandardCharsets.UTF_8));
        assembler.accept("shop", 1, 0, 1, "S".getBytes(StandardCharsets.UTF_8));

        assertEquals(2, assembler.snapshot().size());
        assertEquals("T", assembler.current("tasks"));
        assertNull(assembler.current("nothing"));

        assembler.clear();
        assertEquals(0, assembler.snapshot().size());
        assertNull(assembler.current("tasks"));
    }
}
