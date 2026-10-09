package com.haojing.battlepass.server.reward;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用途：验证 COMMAND 类奖励的命令白名单判定（需求文档 §9：
 * 「COMMAND 必须走管理员命令白名单，禁止将 GUI 输入直接作为命令执行」）。
 *
 * <p>这是本模组唯一的安全边界，因此用例刻意覆盖各种"看起来像白名单里的命令"的绕过写法：
 * 前导斜杠、大小写、带参数、命名空间前缀。
 */
class CommandWhitelistTest {

    private static final List<String> WHITELIST = List.of("give", "title", "playsound");

    @Test
    void 取命令根会剥掉斜杠与命名空间() {
        assertEquals("give", CommandWhitelist.rootOf("give @s minecraft:diamond 1"));
        assertEquals("give", CommandWhitelist.rootOf("/give @s minecraft:diamond 1"));
        assertEquals("give", CommandWhitelist.rootOf("  //give   @s x"));
        assertEquals("give", CommandWhitelist.rootOf("minecraft:give @s x"));
        assertEquals("give", CommandWhitelist.rootOf("/minecraft:give"));
        assertEquals("give", CommandWhitelist.rootOf("give"));
        assertEquals("", CommandWhitelist.rootOf(""));
        assertEquals("", CommandWhitelist.rootOf("   "));
        assertEquals("", CommandWhitelist.rootOf("/"));
        assertEquals("", CommandWhitelist.rootOf(null));
    }

    @Test
    void 白名单内外的命令判定() {
        assertTrue(CommandWhitelist.isAllowed("give @s minecraft:diamond 1", WHITELIST));
        assertTrue(CommandWhitelist.isAllowed("/GIVE @s minecraft:diamond 1", WHITELIST), "大小写与前导斜杠都不应影响判定");
        assertTrue(CommandWhitelist.isAllowed("minecraft:title @s title {\"text\":\"hi\"}", WHITELIST));
        assertFalse(CommandWhitelist.isAllowed("fill 0 0 0 1 1 1 stone", WHITELIST), "未列入白名单的命令必须被拒绝");
        assertFalse(CommandWhitelist.isAllowed("op Notch", WHITELIST));
        assertFalse(CommandWhitelist.isAllowed("kill @a", WHITELIST));
    }

    @Test
    void 空命令与空白名单一律拒绝() {
        assertFalse(CommandWhitelist.isAllowed("", WHITELIST));
        assertFalse(CommandWhitelist.isAllowed("   ", WHITELIST));
        assertFalse(CommandWhitelist.isAllowed("/", WHITELIST));
        assertFalse(CommandWhitelist.isAllowed(null, WHITELIST));
        assertFalse(CommandWhitelist.isAllowed("give @s x", List.of()), "空名单等于禁止一切命令奖励");
        assertFalse(CommandWhitelist.isAllowed("give @s x", null));
    }

    @Test
    void 白名单条目自身也被规范化() {
        // 管理员手抄时带斜杠、带参数、带空白都应能命中
        assertTrue(CommandWhitelist.isAllowed("give @s x", List.of(" /Give ", "title")));
        assertTrue(CommandWhitelist.isAllowed("title @s title x", List.of("minecraft:title")));
        assertFalse(CommandWhitelist.isAllowed("give @s x", java.util.Arrays.asList("  ", "", null)),
                "空白条目不应命中任何命令");
    }

    @Test
    void 带参数的白名单条目按第一个词匹配() {
        // 有人会把整条命令抄进白名单，此时应只取其命令根
        assertTrue(CommandWhitelist.isAllowed("give @s diamond", List.of("give @s diamond")));
    }
}
